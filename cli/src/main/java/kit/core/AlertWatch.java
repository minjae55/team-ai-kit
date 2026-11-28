package kit.core;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;

import java.io.File;
import java.io.IOException;
import java.io.OutputStream;
import java.net.HttpURLConnection;
import java.net.URL;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.text.SimpleDateFormat;
import java.time.Instant;
import java.util.ArrayList;
import java.util.Date;
import java.util.HashMap;
import java.util.Iterator;
import java.util.List;
import java.util.Map;
import java.util.concurrent.TimeUnit;

/**
 * 알림 → 자동 원인 분석 (로드맵 6단계).
 * Grafana·Alertmanager 웹훅을 받아, 같은 알림은 쿨다운 동안 한 번만, 하루 최대 N번까지
 * 프로젝트 폴더에서 claude -p 로 incident-analysis 를 읽기 전용으로 돌리고, 결과를 파일로 남기고 알린다.
 */
public final class AlertWatch {

    private static final ObjectMapper JSON = new ObjectMapper();

    public static final class Alert {
        public String name;
        public String key;
        public String text;
    }

    public static final class Options {
        public Path project;
        public int cooldownMinutes = 30;
        public int maxPerDay = 10;
        public boolean dryRun;
        public String claude = "claude";
        public Path slackWebhookFile;
        public boolean desktopNotify = true;
        public int timeoutMinutes = 10;
    }

    private final KitEnv env;
    private final Options o;
    private final Map<String, Long> lastRun = new HashMap<String, Long>();
    private final List<Long> runsToday = new ArrayList<Long>();

    public AlertWatch(KitEnv env, Options o) {
        this.env = env;
        this.o = o;
    }

    public Path alertsDir() {
        return env.kitDir().resolve("alerts");
    }

    /** Grafana(통합 알림)와 Alertmanager 웹훅 형식에서 firing 상태인 알림만 뽑는다 */
    public static List<Alert> parse(String body) throws IOException {
        List<Alert> out = new ArrayList<Alert>();
        JsonNode root = JSON.readTree(body);
        if (root == null) {
            return out;
        }
        for (JsonNode a : root.path("alerts")) {
            String status = a.path("status").asText(root.path("status").asText("firing"));
            if (!"firing".equals(status)) {
                continue;
            }
            Alert al = new Alert();
            al.name = a.path("labels").path("alertname").asText(root.path("title").asText("알림"));
            String fp = a.path("fingerprint").asText("");
            al.key = fp.isEmpty() ? al.name + a.path("labels").toString() : fp;
            StringBuilder t = new StringBuilder();
            t.append("알림: ").append(al.name).append("\n");
            t.append("시작: ").append(a.path("startsAt").asText("")).append("\n");
            t.append("라벨: ").append(fields(a.path("labels"))).append("\n");
            if (a.path("values").size() > 0) {
                t.append("값: ").append(fields(a.path("values"))).append("\n");
            } else if (!a.path("valueString").asText("").isEmpty()) {
                t.append("값: ").append(a.path("valueString").asText()).append("\n");
            }
            for (Iterator<Map.Entry<String, JsonNode>> it = a.path("annotations").fields(); it.hasNext(); ) {
                Map.Entry<String, JsonNode> e = it.next();
                t.append(e.getKey()).append(": ").append(e.getValue().asText()).append("\n");
            }
            al.text = t.toString();
            out.add(al);
        }
        return out;
    }

    private static String fields(JsonNode n) {
        List<String> parts = new ArrayList<String>();
        for (Iterator<Map.Entry<String, JsonNode>> it = n.fields(); it.hasNext(); ) {
            Map.Entry<String, JsonNode> e = it.next();
            parts.add(e.getKey() + "=" + e.getValue().asText());
        }
        return String.join(", ", parts);
    }

    /** 돌릴지 판단: 쿨다운, 하루 한도. 돌리면 null, 건너뛰면 이유 */
    public synchronized String admit(Alert a, long now) {
        Long last = lastRun.get(a.key);
        if (last != null && now - last < TimeUnit.MINUTES.toMillis(o.cooldownMinutes)) {
            return "같은 알림을 " + ((now - last) / 60000) + "분 전에 분석함 (쿨다운 " + o.cooldownMinutes + "분)";
        }
        runsToday.removeIf(t -> now - t > TimeUnit.DAYS.toMillis(1));
        if (runsToday.size() >= o.maxPerDay) {
            return "최근 24시간 분석 한도(" + o.maxPerDay + "번)에 도달";
        }
        lastRun.put(a.key, now);
        runsToday.add(now);
        return null;
    }

    /** 사람 없이 돌릴 때 허용하는 도구: 읽기와 팀 스킬의 읽기 전용 스크립트만. 비밀값 파일은 읽기도 막는다 */
    String[] command(String prompt) {
        String skills = env.home().resolve(".claude").resolve("skills").toString();
        String allowed = String.join(",",
                "Read", "Grep", "Glob", "Skill",
                "Bash(python3 " + skills + "/incident-analysis/scripts/prom_query.py *)",
                "Bash(bash " + skills + "/incident-analysis/scripts/db_query.sh *)",
                "Bash(bash " + skills + "/query-analysis/scripts/db_query.sh *)",
                "Bash(git log *)", "Bash(git show *)", "Bash(git diff *)", "Bash(date *)");
        String denied = String.join(",",
                "Edit", "Write", "NotebookEdit", "WebFetch", "WebSearch",
                "Read(**/.env*)", "Read(**/*.cnf)", "Read(**/application-local*)", "Read(**/*secret*)", "Read(**/*.pem)");
        return new String[]{o.claude, "-p", prompt, "--output-format", "json", "--max-turns", "30",
                "--permission-mode", "dontAsk", "--allowedTools", allowed, "--disallowedTools", denied};
    }

    static String prompt(Alert a) {
        String now = new SimpleDateFormat("yyyy-MM-dd'T'HH:mm:ssXXX").format(new Date());
        return "(현재 시각: " + now + ")\n\n아래 알림이 방금 왔다. incident-analysis 스킬로 원인을 분석해줘. "
                + "사람이 없는 자동 실행이라 읽기만 하고, 재시작·설정 변경·DB 쓰기는 제안만 해.\n\n" + a.text;
    }

    /** 분석 하나를 돌리고 결과 파일 경로를 돌려준다 */
    public Path analyze(Alert a) throws IOException, InterruptedException {
        Files.createDirectories(alertsDir());
        String stamp = new SimpleDateFormat("yyyyMMdd-HHmmss").format(new Date());
        Path outFile = alertsDir().resolve(stamp + "-" + a.name.replaceAll("[^\\w가-힣-]+", "_") + ".md");
        String p = prompt(a);
        String result;
        String meta = "";
        long started = System.currentTimeMillis();
        if (o.dryRun) {
            result = "(연습 실행: AI를 부르지 않았습니다. 실제로는 아래 요청을 보냅니다)\n\n" + p;
        } else {
            Path log = Files.createTempFile("kit-watch-", ".json");
            Process pr = new ProcessBuilder(command(p)).directory(o.project.toFile())
                    .redirectErrorStream(true).redirectOutput(log.toFile()).start();
            if (!pr.waitFor(o.timeoutMinutes, TimeUnit.MINUTES)) {
                pr.destroyForcibly();
                result = "분석 시간 초과 (" + o.timeoutMinutes + "분)";
            } else {
                String raw = new String(Files.readAllBytes(log), StandardCharsets.UTF_8);
                result = extract(raw);
                JsonNode n = lastJson(raw);
                if (n != null) {
                    meta = String.format(" · %d턴 · $%.3f(추정)", n.path("num_turns").asInt(), n.path("total_cost_usd").asDouble());
                }
            }
            Files.deleteIfExists(log);
        }
        long sec = (System.currentTimeMillis() - started) / 1000;
        String doc = "# 자동 분석: " + a.name + "\n\n> " + Instant.now() + " · " + sec + "초" + meta
                + " · 프로젝트 " + o.project + "\n\n## 받은 알림\n```\n" + a.text + "```\n\n" + result + "\n";
        Files.write(outFile, doc.getBytes(StandardCharsets.UTF_8));
        notifyAll(a, result, outFile);
        return outFile;
    }

    static JsonNode lastJson(String raw) {
        try {
            JsonNode whole = JSON.readTree(raw.trim());   // --output-format json 은 보통 한 덩어리
            if (whole != null && whole.has("result")) {
                return whole;
            }
        } catch (IOException ignored) {
            // 줄 단위로 다시 찾는다
        }
        JsonNode found = null;
        for (String line : raw.split("\n")) {
            line = line.trim();
            if (line.startsWith("{")) {
                try {
                    JsonNode n = JSON.readTree(line);
                    if (n.has("result")) {
                        found = n;
                    }
                } catch (IOException ignored) {
                    // 다른 출력은 건너뛴다
                }
            }
        }
        return found;
    }

    static String extract(String raw) {
        JsonNode n = lastJson(raw);
        if (n != null) {
            return n.path("result").asText();
        }
        return "분석 결과를 받지 못했습니다. claude 출력 끝부분:\n```\n" + raw.substring(Math.max(0, raw.length() - 1500)) + "\n```";
    }

    static String summary(String result) {
        int i = result.indexOf("## 요약");
        String s = i >= 0 ? result.substring(i + 5) : result;
        int j = s.indexOf("\n## ");
        s = (j > 0 ? s.substring(0, j) : s).trim();
        return s.length() > 600 ? s.substring(0, 600) + "…" : s;
    }

    private void notifyAll(Alert a, String result, Path file) {
        String sum = summary(result);
        if (o.desktopNotify && System.getProperty("os.name", "").toLowerCase().contains("mac")) {
            String msg = sum.replace("\"", "'").replace("\n", " ");
            try {
                new ProcessBuilder("osascript", "-e", "display notification \"" + (msg.length() > 180 ? msg.substring(0, 180) + "…" : msg)
                        + "\" with title \"kit 자동 분석: " + a.name.replace("\"", "'") + "\"")
                        .redirectErrorStream(true).redirectOutput(new File("/dev/null")).start();
            } catch (IOException ignored) {
                // 알림 실패는 분석 결과에 영향 없음
            }
        }
        if (o.slackWebhookFile != null) {
            try {
                String url = new String(Files.readAllBytes(o.slackWebhookFile), StandardCharsets.UTF_8).trim();
                Map<String, String> body = new HashMap<String, String>();
                body.put("text", "*kit 자동 분석: " + a.name + "*\n" + sum + "\n(전체: " + file + ")");
                HttpURLConnection c = (HttpURLConnection) new URL(url).openConnection();
                c.setRequestMethod("POST");
                c.setDoOutput(true);
                c.setRequestProperty("Content-Type", "application/json");
                try (OutputStream os = c.getOutputStream()) {
                    os.write(JSON.writeValueAsBytes(body));
                }
                c.getResponseCode();
            } catch (IOException ignored) {
                // 웹훅 주소는 출력하지 않는다
            }
        }
    }
}
