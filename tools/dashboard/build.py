#!/usr/bin/env python3
"""개인 AI 작업 대시보드 v1: 내 PC의 기록을 읽어 HTML 한 장을 만든다 (서버·배포 없음, AI 토큰 사용 없음).

화면 (중요한 순서):
  1. API 목표 달성 현황과 추이   ← runs.json, targets.json (측정할 때마다 runs.json에 추가)
  2. 개선 사례 이력              ← comparison.json
  3. 알림 → 원인 파악 시간        ← Grafana 알림 기록 + incident-analysis 세션 시각
  4. AI 제안 검증 기록            ← ai-corrections.json, eval results.json
  5. 최근 요청·실행한 명령 로그    ← Claude Code 세션 기록 (~/.claude/projects)

사용: python3 tools/dashboard/build.py [--open]
주의: 결과 HTML에는 요청 내용 일부가 들어가므로 공유하지 않는다 (out/은 gitignore).
"""
import glob, html, json, os, re, subprocess, sys, urllib.request
from collections import Counter, defaultdict
from datetime import datetime, timedelta

HERE = os.path.dirname(os.path.abspath(__file__))
CFG = json.load(open(os.path.join(HERE, "sources.json")))
X = os.path.expanduser
NOW = datetime.now().astimezone()
DAYS = CFG.get("days", 7)
SINCE = NOW - timedelta(days=DAYS)
TAG_RE = re.compile(r"<(pasted_content|system-reminder|command-[a-z-]+|local-command-[a-z-]+)[^>]*>.*?</\1[^>]*>", re.S)


def esc(s):
    return html.escape(str(s), quote=True)


def ts(s):
    return datetime.fromisoformat(s.replace("Z", "+00:00")).astimezone() if s else None


def num(v):
    return int(v) if isinstance(v, float) and v.is_integer() else v


def load_json(path):
    try:
        return json.load(open(X(path)))
    except (OSError, ValueError, TypeError):
        return None


def load_globs(patterns):
    return [d for pat in patterns for f in sorted(glob.glob(X(pat))) if (d := load_json(f))]


# ---------- 세션 기록 ----------
def clean_prompt(text):
    had_paste = "<pasted_content" in text
    t = re.sub(r"\s+", " ", TAG_RE.sub("", text)).strip()
    if not t and had_paste:
        return "[붙여넣은 내용]"
    return (("[붙여넣기] " if had_paste else "") + t)[:90]


def is_real_prompt(e, text):
    if e.get("isMeta") or e.get("isSidechain"):
        return False
    t = text.lstrip()
    return bool(t) and not t.startswith(("<task-notification", "[Request interrupted", "<system-reminder", "Base directory for this skill"))


def parse_session(path):
    s = {"id": os.path.basename(path)[:8], "project": "", "title": "", "start": None, "end": None,
         "prompts": [], "skills": Counter(), "commands": [], "out_tokens": 0}
    results, pending, seen = {}, {}, set()
    for line in open(path, encoding="utf-8", errors="ignore"):
        try:
            e = json.loads(line)
        except ValueError:
            continue
        t = ts(e.get("timestamp"))
        if t:
            s["start"] = min(s["start"] or t, t)
            s["end"] = max(s["end"] or t, t)
        if e.get("cwd") and not s["project"]:
            s["project"] = os.path.basename(e["cwd"])
        if e.get("type") == "custom-title" and e.get("customTitle"):
            s["title"] = e["customTitle"]
        msg = e.get("message") or {}
        content = msg.get("content")
        blocks = content if isinstance(content, list) else []
        if e.get("type") == "user":
            texts = [content] if isinstance(content, str) else [b.get("text", "") for b in blocks if b.get("type") == "text"]
            for b in blocks:
                if b.get("type") == "tool_result":
                    results[b.get("tool_use_id")] = bool(b.get("is_error"))
            text = "\n".join(texts)
            if texts and t and is_real_prompt(e, text):
                s["prompts"].append((t, clean_prompt(text)))
        if e.get("type") == "assistant":
            u = msg.get("usage") or {}
            if msg.get("id") and msg["id"] not in seen and u:
                seen.add(msg["id"])
                s["out_tokens"] += u.get("output_tokens") or 0
            for b in blocks:
                if b.get("type") != "tool_use":
                    continue
                inp = b.get("input") or {}
                if b.get("name") == "Skill":
                    s["skills"][inp.get("skill", "?").split(":")[-1]] += 1
                if b.get("name") == "Bash" and t:
                    pending[b.get("id")] = (t, inp.get("description") or "(설명 없음)")
    s["commands"] = [{"time": t, "desc": d, "ok": not results.get(i, False), "project": s["project"]} for i, (t, d) in pending.items()]
    return s


sessions = []
for p in glob.glob(os.path.join(X(CFG["claude_projects_dir"]), "*", "*.jsonl")):
    if datetime.fromtimestamp(os.path.getmtime(p)).astimezone() >= SINCE:
        s = parse_session(p)
        if s["start"] and s["prompts"]:
            sessions.append(s)
sessions.sort(key=lambda s: s["start"], reverse=True)
by_id = {s["id"]: s for s in sessions}

# ---------- 기타 데이터 ----------
targets = (load_json(CFG.get("targets", "")) or {}).get("targets", [])
runs = sorted((load_json(CFG.get("runs", "")) or {}).get("runs", []), key=lambda r: r["time"])
improvements = load_globs(CFG.get("improvements", []))
eval_runs = [r for ev in load_globs(CFG.get("evals", [])) for r in ev.get("runs", [])]
eval_by_session = {r["session"]: r for r in eval_runs if r.get("session")}
corrections = (load_json(CFG.get("ai_corrections", "")) or {}).get("items", [])

alerts = None
try:
    with urllib.request.urlopen(CFG["grafana_url"] + "/api/annotations?type=alert&limit=500", timeout=5) as r:
        alerts = sorted(
            ({"time": datetime.fromtimestamp(a["time"] / 1000).astimezone(), "name": a.get("alertName", "")}
             for a in json.load(r) if str(a.get("newState", "")).lower() == "alerting"),
            key=lambda a: a["time"])
except Exception:
    alerts = None

# 알림 → 원인 파악: 알림 발생 후 30분 안에 시작한 incident-analysis 세션과 연결
responses = []
for s in sorted(sessions, key=lambda s: s["start"]):
    if "incident-analysis" not in s["skills"]:
        continue
    before = [a for a in alerts or [] if a["time"] <= s["start"] and s["start"] - a["time"] <= timedelta(minutes=30)]
    alert = before[-1] if before else None
    ev = eval_by_session.get(s["id"])
    responses.append({
        "alert": alert, "start": s["start"], "end": s["end"], "title": s["title"] or s["prompts"][0][1],
        "to_cause_min": (s["end"] - alert["time"]).total_seconds() / 60 if alert else None,
        "analysis_min": (s["end"] - s["start"]).total_seconds() / 60,
        "result": ev["result"] if ev else None, "case": ev["title"] if ev else "",
    })
alert_resp = [r for r in responses if r["to_cause_min"] is not None]


# ---------- 화면 조각 ----------
def card(label, value, sub="", tone=""):
    return f'<div class="metric"><div class="ml">{esc(label)}</div><div class="mv {tone}">{esc(value)}</div><div class="ms">{esc(sub)}</div></div>'


def spark(values, target=None, w=180, h=40):
    if not values:
        return ""
    hi = max(values + ([target] if target else [])) * 1.1 or 1
    xs = [i * (w - 8) / max(len(values) - 1, 1) + 4 for i in range(len(values))]
    ys = [h - 4 - v / hi * (h - 8) for v in values]
    pts = " ".join(f"{x:.1f},{y:.1f}" for x, y in zip(xs, ys))
    tl = f'<line x1="0" x2="{w}" y1="{h - 4 - target / hi * (h - 8):.1f}" y2="{h - 4 - target / hi * (h - 8):.1f}" class="tline"/>' if target else ""
    dots = "".join(f'<circle cx="{x:.1f}" cy="{y:.1f}" r="2.5" class="dot"/>' for x, y in zip(xs, ys))
    return f'<svg width="{w}" height="{h}" viewBox="0 0 {w} {h}" aria-hidden="true">{tl}<polyline points="{pts}" class="sline"/>{dots}</svg>'


# 1. 목표 달성 현황
goal_cards, met, measured = "", 0, 0
target_apis = {t["api"] for t in targets}
for t in targets:
    load = [r for r in runs if r["api"] == t["api"] and r.get("kind") == "load"]
    if not load:
        goal_cards += f'<div class="goal"><div class="gn">{esc(t["name"])}</div><div class="ga">{esc(t["api"])}</div><div class="gs none">측정 없음</div><div class="muted">목표 p95 {t["p95_ms"]}ms · 다음에 측정할 대상</div></div>'
        continue
    last = load[-1]
    measured += 1
    ok = last["p95_ms"] <= t["p95_ms"] and last.get("error_rate", 0) <= t.get("error_rate", 1)
    met += ok
    prev = load[-2]["p95_ms"] if len(load) > 1 else None
    trend = "" if prev is None else (f'직전 {num(prev)}ms → {"개선" if last["p95_ms"] < prev else "악화"}')
    goal_cards += (f'<div class="goal"><div class="gn">{esc(t["name"])}</div><div class="ga">{esc(t["api"])}</div>'
                   f'<div class="gs {"good" if ok else "bad"}">{"달성" if ok else "미달"} · p95 {num(last["p95_ms"])}ms</div>'
                   f'<div class="muted">목표 {t["p95_ms"]}ms · {esc(trend)} · {last["time"][5:16].replace("T", " ")}</div>'
                   f'{spark([r["p95_ms"] for r in load], t["p95_ms"])}<div class="muted">측정 {len(load)}회 (점선 = 목표)</div></div>')
other_apis = sorted({r["api"] for r in runs} - target_apis)
for api in other_apis:
    load = [r for r in runs if r["api"] == api and r.get("kind") == "load"]
    last = load[-1] if load else None
    goal_cards += (f'<div class="goal"><div class="gn">{esc(api)}</div><div class="ga">목표 없음</div>'
                   f'<div class="gs none">{"p95 " + str(num(last["p95_ms"])) + "ms" if last else "일반 부하 측정 없음"}</div>'
                   f'<div class="muted">목표를 정하면 달성 여부를 판정합니다</div></div>')
run_rows = "".join(
    f'<tr><td class="muted nowrap">{r["time"][5:16].replace("T", " ")}</td><td class="nowrap">{esc(r["api"])}</td><td>{esc(r["scenario"])}</td>'
    f'<td class="muted nowrap">{ {"load": "일반", "stress": "한계", "fault": "장애 주입"}.get(r.get("kind"), r.get("kind", "")) }</td>'
    f'<td class="num">{num(r["p95_ms"])}ms</td></tr>' for r in reversed(runs[-10:]))

# 2. 개선 이력
imp_html, charts = "", []
for i, imp in enumerate(improvements):
    rows = "".join(f'<tr><td>{esc(r["metric"])}</td><td class="num">{esc(num(r["before"]))}{esc(r["unit"])}</td>'
                   f'<td class="num good">{esc(num(r["after"]))}{esc(r["unit"])}</td></tr>' for r in imp.get("results", []))
    cr = [r for r in imp.get("results", []) if r["unit"] == "ms"][:3]
    charts.append([f"imp{i}", [r["metric"] for r in cr], [r["before"] for r in cr], [r["after"] for r in cr]])
    imp_html += f'''<div class="imp"><h3>{esc(imp.get("title", ""))} <span class="muted">{esc(imp.get("id", "")[:10])}</span></h3>
  <p class="plain">{esc(imp.get("plain_summary", ""))}</p>
  <div class="two"><div class="chartbox"><canvas id="imp{i}" role="img" aria-label="개선 전후 비교"></canvas></div>
  <table><thead><tr><th>지표</th><th class="num">전</th><th class="num">후</th></tr></thead><tbody>{rows}</tbody></table></div>
  <p class="muted">변경: {esc(imp.get("change", ""))}<br>한계: {esc(" / ".join(imp.get("limits", [])))}</p></div>'''

# 3. 알림 대응
if alerts is None:
    alert_html = '<p class="muted">Grafana에 연결하지 못해 알림 기록을 읽지 못했습니다 (모니터링 컨테이너가 꺼져 있음).</p>'
else:
    rows = "".join(
        f'<tr><td class="nowrap">{r["alert"]["time"]:%m-%d %H:%M} {esc(r["alert"]["name"]) if r["alert"] else ""}</td>'
        f'<td class="muted nowrap">{r["start"]:%H:%M}</td><td class="num">{r["analysis_min"]:.1f}분</td>'
        f'<td class="num"><b>{r["to_cause_min"]:.1f}분</b></td>'
        f'<td class="{ {"pass": "good", "fail": "bad"}.get(r["result"], "muted") }">{ {"pass": "원인 맞힘", "fail": "원인 틀림"}.get(r["result"], "채점 없음") }</td></tr>'
        if r["alert"] else
        f'<tr><td class="muted">알림 없이 요청</td><td class="muted nowrap">{r["start"]:%H:%M}</td><td class="num">{r["analysis_min"]:.1f}분</td><td class="num">-</td>'
        f'<td class="{ {"pass": "good", "fail": "bad"}.get(r["result"], "muted") }">{ {"pass": "원인 맞힘", "fail": "원인 틀림"}.get(r["result"], "채점 없음") }</td></tr>'
        for r in reversed(responses))
    alert_html = (f'<table><thead><tr><th>알림 발생</th><th>분석 시작</th><th class="num">분석 시간</th><th class="num">알림 → 원인 파악</th><th>결과</th></tr></thead><tbody>{rows}</tbody></table>'
                  f'<p class="muted">최근 {DAYS}일 알림 {len([a for a in alerts if a["time"] >= SINCE])}건. 알림 발생 후 30분 안에 시작한 incident-analysis 세션과 연결합니다.</p>')

# 4. AI 제안 검증
corr_rows = "".join(
    f'<tr><td class="muted nowrap">{esc(c["date"][5:])}</td><td><div class="bad">AI: {esc(c["ai_said"])}</div><div class="good">실제: {esc(c["actual"])}</div>'
    f'<div class="muted">잡아낸 근거: {esc(c["caught_by"])} · 그대로 따랐다면: {esc(c["impact_if_followed"])}</div></td></tr>' for c in corrections)
eval_rows = "".join(
    f'<tr><td>{esc(r["title"])}</td><td class="muted">{esc(r["variant"])}</td><td class="nowrap {"good" if r["result"] == "pass" else "bad"}">{"통과" if r["result"] == "pass" else "불통과"}</td>'
    f'<td class="muted">{esc(r.get("note", ""))}</td></tr>' for r in eval_runs)
eval_pass = sum(1 for r in eval_runs if r["result"] == "pass")

# 5. 로그
commands = sorted((c for s in sessions for c in s["commands"]), key=lambda c: c["time"], reverse=True)
recent_prompts = sorted((p for s in sessions for p in s["prompts"]), key=lambda p: p[0], reverse=True)[:12]
prompt_rows = "".join(f'<tr><td class="muted nowrap">{t:%m-%d %H:%M}</td><td>{esc(x)}</td></tr>' for t, x in recent_prompts)
cmd_rows = "".join(f'<tr><td class="muted nowrap">{c["time"]:%m-%d %H:%M}</td><td>{esc(c["desc"])}</td><td class="muted nowrap">{esc(c["project"])}</td>'
                   f'<td class="nowrap {"good" if c["ok"] else "bad"}">{"성공" if c["ok"] else "실패"}</td></tr>' for c in commands[:15])
skills = Counter()
for s in sessions:
    skills.update(s["skills"])
out_tokens = sum(s["out_tokens"] for s in sessions)
avg_cause = sum(r["to_cause_min"] for r in alert_resp) / len(alert_resp) if alert_resp else None

page = f'''<!doctype html><html lang="ko"><head><meta charset="utf-8"><meta name="viewport" content="width=device-width,initial-scale=1">
<title>내 AI 작업 대시보드</title>
<style>
:root{{--bg:#f7f7f5;--card:#fff;--ink:#1d1d1b;--sub:#5f5e5a;--mut:#898781;--line:#e6e4dd;--good:#0f7a3d;--bad:#b42318;--blue:#2a78d6}}
@media (prefers-color-scheme:dark){{:root{{--bg:#151515;--card:#1f1f1f;--ink:#f0efec;--sub:#c3c2b7;--line:#2c2c2a;--good:#4ade80;--bad:#f87171}}}}
*{{box-sizing:border-box}}body{{margin:0;background:var(--bg);color:var(--ink);font:15px/1.6 -apple-system,"Apple SD Gothic Neo",sans-serif}}
main{{max-width:1100px;margin:0 auto;padding:24px}}h1{{font-size:22px;font-weight:600;margin:0}}h2{{font-size:17px;font-weight:600;margin:0 0 4px}}h3{{font-size:15px;font-weight:600;margin:0 0 4px}}
.head{{display:flex;justify-content:space-between;align-items:baseline;margin-bottom:16px}}.muted{{color:var(--mut);font-size:13px}}.desc{{color:var(--sub);font-size:13px;margin:0 0 12px}}
.metrics{{display:grid;grid-template-columns:repeat(auto-fit,minmax(160px,1fr));gap:12px;margin-bottom:16px}}
.metric{{background:var(--card);border:1px solid var(--line);border-radius:10px;padding:14px 16px}}.ml{{color:var(--sub);font-size:13px}}.mv{{font-size:26px;font-weight:600}}.ms{{color:var(--mut);font-size:12px}}
.card{{background:var(--card);border:1px solid var(--line);border-radius:12px;padding:18px 20px;margin-bottom:16px}}
.goals{{display:grid;grid-template-columns:repeat(auto-fit,minmax(230px,1fr));gap:12px;margin-bottom:12px}}
.goal{{border:1px solid var(--line);border-radius:10px;padding:12px 14px}}.gn{{font-weight:600}}.ga{{color:var(--mut);font-size:12px;font-family:ui-monospace,monospace}}
.gs{{font-size:18px;font-weight:600;margin:6px 0 2px}}.gs.none{{color:var(--mut)}}
.sline{{fill:none;stroke:var(--blue);stroke-width:2}}.dot{{fill:var(--blue)}}.tline{{stroke:var(--bad);stroke-dasharray:4 3;stroke-width:1}}
.imp+.imp{{border-top:1px solid var(--line);padding-top:14px;margin-top:14px}}
.grid2{{display:grid;grid-template-columns:minmax(0,1fr) minmax(0,1fr);gap:16px}}.two{{display:grid;grid-template-columns:minmax(0,1fr) minmax(0,1fr);gap:16px;align-items:center}}
@media (max-width:800px){{.grid2,.two{{grid-template-columns:1fr}}}}
.plain{{margin:0 0 10px;color:var(--sub)}}.chartbox{{position:relative;height:180px}}
table{{width:100%;border-collapse:collapse;font-size:14px}}th{{text-align:left;color:var(--sub);font-weight:500;border-bottom:1px solid var(--line);padding:6px 4px}}
td{{border-bottom:1px solid var(--line);padding:7px 4px;vertical-align:top}}.num{{text-align:right;white-space:nowrap}}.nowrap{{white-space:nowrap}}
.good{{color:var(--good)}}.bad{{color:var(--bad)}}td.good,td.bad,.gs.good,.gs.bad{{font-weight:600}}
details summary{{cursor:pointer;color:var(--sub);font-size:14px;margin-top:8px}}
</style></head><body><main>
<div class="head"><h1>내 AI 작업 대시보드</h1><span class="muted">최근 {DAYS}일 · {NOW:%Y-%m-%d %H:%M} 생성 · 내 PC 전용</span></div>
<div class="metrics">
  {card("API 목표", f"달성 {met}", f"미달 {measured - met} · 미측정 {len(targets) - measured} (목표 {len(targets)}개)", "good" if measured and met == measured else "")}
  {card("개선 사례", len(improvements), "전후 증거가 있는 개선")}
  {card("알림 → 원인 파악", f"{avg_cause:.1f}분" if avg_cause is not None else "-", f"평균 · {len(alert_resp)}건")}
  {card("AI 오류 잡아냄", len(corrections), "근거로 걸러낸 틀린 제안")}
  {card("스킬 검증 통과", f"{eval_pass} / {len(eval_runs)}", "incident-analysis eval")}
</div>

<section class="card"><h2>1. API 목표 달성 현황</h2><p class="desc">API별 최근 일반 부하 측정의 p95(느린 쪽 5% 응답 시간)를 목표와 비교합니다.</p>
<div class="goals">{goal_cards}</div>
<details><summary>최근 측정 기록 {len(runs)}건</summary><table><thead><tr><th>시각</th><th>API</th><th>시나리오</th><th>종류</th><th class="num">p95</th></tr></thead><tbody>{run_rows}</tbody></table></details></section>

<section class="card"><h2>2. 개선 사례</h2><p class="desc">고치기 전후를 같은 조건으로 측정한 결과입니다.</p>{imp_html or '<p class="muted">아직 없음</p>'}</section>

<section class="card"><h2>3. 알림 → 원인 파악 시간</h2><p class="desc">Slack 알림이 온 뒤 AI 분석(incident-analysis)으로 원인을 확인하기까지 걸린 시간입니다.</p>{alert_html}</section>

<section class="card"><h2>4. AI 제안 검증</h2><p class="desc">AI가 틀렸거나 부정확했던 제안과, 무엇으로 잡아냈는지입니다. 그대로 믿지 않고 근거로 확인한 기록입니다.</p>
<table><tbody>{corr_rows}</tbody></table>
<details><summary>스킬 검증(eval) 결과 {eval_pass}/{len(eval_runs)}</summary><table><thead><tr><th>시나리오</th><th>입력 방식</th><th>결과</th><th>메모</th></tr></thead><tbody>{eval_rows}</tbody></table></details></section>

<div class="grid2">
  <section class="card"><h2>최근 요청</h2><table><tbody>{prompt_rows}</tbody></table></section>
  <section class="card"><h2>최근 실행한 명령</h2><table><tbody>{cmd_rows}</tbody></table></section>
</div>
<p class="muted">활동: 세션 {len(sessions)} · 요청 {sum(len(s["prompts"]) for s in sessions)} · 명령 {len(commands)} · 스킬 {", ".join(f"{k} {v}" for k, v in skills.most_common(4))} · 출력 토큰 {out_tokens:,}<br>
명령은 원문 대신 설명만 표시합니다. 성공/실패는 명령 종료 코드 기준입니다.</p>
</main>
<script src="https://cdn.jsdelivr.net/npm/chart.js@4.4.1/dist/chart.umd.min.js"></script>
<script>
const dark=matchMedia('(prefers-color-scheme: dark)').matches, mut='#898781', grid=dark?'#2c2c2a':'#e6e4dd';
for(const [id,labels,before,after] of {json.dumps(charts, ensure_ascii=False)}){{new Chart(document.getElementById(id),{{type:'bar',data:{{labels,datasets:[{{label:'고치기 전',data:before,backgroundColor:'#b4b2a9',borderRadius:4,maxBarThickness:20}},{{label:'고친 뒤',data:after,backgroundColor:'#2a78d6',borderRadius:4,maxBarThickness:20}}]}},options:{{responsive:true,maintainAspectRatio:false,indexAxis:'y',plugins:{{legend:{{labels:{{color:mut,boxWidth:10}}}}}},scales:{{x:{{ticks:{{color:mut,callback:v=>v+'ms'}},grid:{{color:grid}}}},y:{{ticks:{{color:mut}},grid:{{display:false}}}}}}}}}});}}
</script></body></html>'''

out = X(CFG["output"])
os.makedirs(os.path.dirname(out), exist_ok=True)
open(out, "w", encoding="utf-8").write(page)
print(f"대시보드 생성: {out}")
print(f"  목표 {met}/{len(targets)}, 측정 {len(runs)}건, 개선 {len(improvements)}, 알림 대응 {len(alert_resp)}건(평균 {avg_cause}), AI 오류 {len(corrections)}, eval {eval_pass}/{len(eval_runs)}")
if "--open" in sys.argv:
    subprocess.run(["open", out])
