package kit.ui;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import kit.adapter.ToolAdapter;
import kit.core.ChangeSummary;
import kit.core.Git;
import kit.core.Installer;
import kit.core.KitEnv;
import kit.core.LockFile;
import kit.core.ManagedBlock;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.LinkOption;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Collections;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.TreeSet;

/** kit ui 화면에 보여 줄 데이터를 모은다. 모두 내 PC에 있는 파일에서 읽는다 (AI 토큰 사용 없음). */
public final class UiData {

    private static final ObjectMapper JSON = new ObjectMapper();
    private final KitEnv env;

    public UiData(KitEnv env) {
        this.env = env;
    }

    public Map<String, Object> state() throws IOException {
        Map<String, Object> m = new LinkedHashMap<String, Object>();
        LockFile lock = LockFile.readOrNull(env.lockFile());
        boolean repo = Git.isRepo(env.repoDir());
        m.put("user", userName());
        m.put("installed", lock != null);
        m.put("version", lock == null ? null : lock.version);
        String latest = repo ? Git.latestTag(env.repoDir()) : null;
        m.put("latest", latest);
        boolean update = lock != null && latest != null && !latest.equals(lock.version);
        m.put("updateAvailable", update);
        if (update) {
            m.put("updatePreview", ChangeSummary.lines(env.repoDir(), lock.commit, latest,
                    allSkills(lock), ChangeSummary.skillsAt(env.repoDir(), latest)));
        }
        m.put("tools", tools(lock));
        m.put("health", health(lock, repo));
        m.put("skills", repo ? skills(lock) : Collections.emptyList());
        m.put("news", repo ? news() : Collections.emptyList());
        List<SessionLog.Session> sessions = sessions();
        m.put("activity", activity(sessions));
        m.put("notes", notes(sessions));
        m.put("recommendations", new Recommender(env).recommend(sessions, dismissed()));
        m.put("metrics", new Metrics(env).read());
        return m;
    }

    private static String userName() {
        try {
            Git.Result r = Git.run(null, "config", "--global", "user.name");
            if (r.ok() && !r.out.trim().isEmpty()) {
                return r.out.trim();
            }
        } catch (IOException ignored) {
            // git이 없으면 시스템 사용자 이름
        }
        return System.getProperty("user.name", "");
    }

    /** 스킬 설명 속 따옴표 문구를 "이렇게 말하면 돼요" 예시로 쓴다 */
    static List<String> examples(String description) {
        List<String> out = new ArrayList<String>();
        java.util.regex.Matcher mt = java.util.regex.Pattern.compile("[\"“]([^\"”]{2,40})[\"”]").matcher(description);
        while (mt.find() && out.size() < 4) {
            out.add(mt.group(1));
        }
        return out;
    }

    public List<SessionLog.Session> sessions() throws IOException {
        return SessionLog.recent(env.home().resolve(".claude").resolve("projects"), 14);
    }

    /** [나중에]로 숨긴 후보 (~/.kit/ui-state.json) */
    public Set<String> dismissed() throws IOException {
        Set<String> out = new TreeSet<String>();
        Path f = env.kitDir().resolve("ui-state.json");
        if (Files.exists(f)) {
            for (JsonNode n : JSON.readTree(f.toFile()).path("dismissed")) {
                out.add(n.asText());
            }
        }
        return out;
    }

    public void dismiss(String id) throws IOException {
        Set<String> d = dismissed();
        d.add(id);
        Map<String, Object> state = new LinkedHashMap<String, Object>();
        state.put("dismissed", d);
        Files.createDirectories(env.kitDir());
        JSON.writerWithDefaultPrettyPrinter().writeValue(env.kitDir().resolve("ui-state.json").toFile(), state);
    }

    private static Set<String> allSkills(LockFile lock) {
        Set<String> s = new TreeSet<String>();
        for (LockFile.ToolState st : lock.tools.values()) {
            s.addAll(st.skills);
        }
        return s;
    }

    private List<Map<String, Object>> tools(LockFile lock) {
        List<Map<String, Object>> out = new ArrayList<Map<String, Object>>();
        for (ToolAdapter a : ToolAdapter.all()) {
            Map<String, Object> t = new LinkedHashMap<String, Object>();
            t.put("id", a.id());
            t.put("name", a.displayName());
            t.put("detected", a.detect(env));
            t.put("installed", lock != null && lock.tools.containsKey(a.id()));
            out.add(t);
        }
        return out;
    }

    /** doctor와 같은 점검을 화면용으로 (아무것도 바꾸지 않음) */
    private List<Map<String, Object>> health(LockFile lock, boolean repo) throws IOException {
        List<Map<String, Object>> out = new ArrayList<Map<String, Object>>();
        if (!repo) {
            out.add(item("error", "키트 레포가 없습니다"));
        }
        if (lock == null) {
            out.add(item("warn", "아직 설치하지 않았습니다"));
            return out;
        }
        for (Map.Entry<String, LockFile.ToolState> e : lock.tools.entrySet()) {
            ToolAdapter a = ToolAdapter.byId(e.getKey());
            if (a == null) {
                continue;
            }
            int broken = 0;
            for (String skill : e.getValue().skills) {
                Path p = a.skillsDir(env).resolve(skill);
                if (!Files.exists(p, LinkOption.NOFOLLOW_LINKS) || (Files.isSymbolicLink(p) && !Files.exists(p))) {
                    broken++;
                }
            }
            out.add(broken == 0 ? item("ok", a.displayName() + ": 스킬 " + e.getValue().skills.size() + "개 정상")
                    : item("error", a.displayName() + ": 스킬 " + broken + "개가 없거나 깨짐"));
            Path cf = env.home().getFileSystem().getPath(e.getValue().contextFile);
            out.add(ManagedBlock.has(cf) ? item("ok", a.displayName() + ": 팀 원칙 연결됨")
                    : item("error", a.displayName() + ": 팀 원칙 연결이 없음"));
        }
        for (ToolAdapter a : ToolAdapter.all()) {
            if (!lock.tools.containsKey(a.id())) {
                out.add(item(a.detect(env) ? "warn" : "info", a.displayName() + (a.detect(env) ? ": 있지만 키트 미설치" : ": 이 PC에 없음"))
                );
                out.get(out.size() - 1).put("installTool", a.detect(env) ? a.id() : null);
            }
        }
        return out;
    }

    private static Map<String, Object> item(String level, String text) {
        Map<String, Object> m = new LinkedHashMap<String, Object>();
        m.put("level", level);
        m.put("text", text);
        return m;
    }

    private List<Map<String, Object>> skills(LockFile lock) throws IOException {
        List<Map<String, Object>> out = new ArrayList<Map<String, Object>>();
        Installer inst = new Installer(env, null, true);
        Map<String, int[]> rated = new Ratings(env).bySkill();
        for (String name : inst.repoSkills()) {
            Map<String, Object> s = new LinkedHashMap<String, Object>();
            s.put("name", name);
            String full = fullDescription(env.skillsSource().resolve(name).resolve("SKILL.md"));
            s.put("description", description(env.skillsSource().resolve(name).resolve("SKILL.md")));
            s.put("examples", examples(full));
            int[] mine = rated.get(name);
            s.put("myUp", mine == null ? 0 : mine[0]);
            s.put("myDown", mine == null ? 0 : mine[1]);
            s.put("enabled", lock == null || !lock.disabled.contains(name));
            Path results = env.repoDir().resolve("evals").resolve(name).resolve("results.json");
            if (Files.exists(results)) {
                JsonNode root = JSON.readTree(results.toFile());
                Set<String> cases = new TreeSet<String>();
                String date = null;
                for (JsonNode r : root.path("runs")) {
                    cases.add(r.path("case").asText());
                    String d = r.path("date").asText(null);
                    if (d != null && (date == null || d.compareTo(date) > 0)) {
                        date = d;
                    }
                }
                s.put("verifiedCases", cases.size());
                s.put("verifiedDate", date);
            }
            out.add(s);
        }
        return out;
    }

    static String description(Path skillMd) throws IOException {
        String d = fullDescription(skillMd);
        int dot = d.indexOf(". ");
        return dot > 0 ? d.substring(0, dot + 1) : d;
    }

    static String fullDescription(Path skillMd) throws IOException {
        if (!Files.exists(skillMd)) {
            return "";
        }
        String text = new String(Files.readAllBytes(skillMd), StandardCharsets.UTF_8);
        int i = text.indexOf("description:");
        if (i < 0) {
            return "";
        }
        String rest = text.substring(i + "description:".length());
        StringBuilder sb = new StringBuilder();
        for (String line : rest.split("\\R")) {
            if (sb.length() > 0 && (line.startsWith("---") || line.matches("^[A-Za-z0-9_-]+:.*"))) {
                break;
            }
            sb.append(' ').append(line.trim());
        }
        return sb.toString().trim();
    }

    /** CHANGELOG.md 최근 절 4개 */
    private List<Map<String, Object>> news() throws IOException {
        List<Map<String, Object>> out = new ArrayList<Map<String, Object>>();
        Path f = env.repoDir().resolve("CHANGELOG.md");
        if (!Files.exists(f)) {
            return out;
        }
        Map<String, Object> cur = null;
        for (String line : new String(Files.readAllBytes(f), StandardCharsets.UTF_8).split("\\R")) {
            if (line.startsWith("## ")) {
                if (out.size() == 4) {
                    break;
                }
                String head = line.substring(3).trim();
                cur = new LinkedHashMap<String, Object>();
                int sp = head.indexOf(' ');
                cur.put("version", sp > 0 ? head.substring(0, sp) : head);
                cur.put("date", sp > 0 ? head.substring(sp).replaceAll("[()\\s]", "") : "");
                cur.put("items", new ArrayList<String>());
                out.add(cur);
            } else if (cur != null && line.startsWith("- ")) {
                @SuppressWarnings("unchecked")
                List<String> items = (List<String>) cur.get("items");
                items.add(line.substring(2));
            }
        }
        return out;
    }

    private List<Map<String, Object>> activity(List<SessionLog.Session> sessions) throws IOException {
        List<Map<String, Object>> out = new ArrayList<Map<String, Object>>();
        for (Path f : improvementFiles()) {
            JsonNode c = JSON.readTree(f.toFile());
            Map<String, Object> a = new LinkedHashMap<String, Object>();
            a.put("kind", "improve");
            a.put("title", c.path("title").asText());
            List<String> bits = new ArrayList<String>();
            for (JsonNode r : c.path("results")) {
                if (bits.size() == 2) break;
                bits.add(r.path("metric").asText() + " " + num(r.path("before")) + " → " + num(r.path("after")) + r.path("unit").asText());
            }
            a.put("detail", String.join(" · ", bits));
            // 정렬이 세션 시각과 맞도록 comparison.json을 만든 시각(ISO)을 쓴다
            a.put("time", java.time.Instant.ofEpochMilli(Files.getLastModifiedTime(f).toMillis()).toString());
            a.put("project", f.getParent().getParent().getParent().getParent().getFileName().toString());
            out.add(a);
        }
        com.fasterxml.jackson.databind.node.ObjectNode ratings = new Ratings(env).all();
        for (SessionLog.Session s : sessions) {
            Map<String, Object> a = new LinkedHashMap<String, Object>();
            a.put("kind", "ai");
            a.put("id", s.id);
            a.put("skills", new ArrayList<String>(s.skills));
            a.put("rating", ratings.path(s.id).path("rating").asText(null));
            a.put("title", s.title.isEmpty() ? s.firstPrompt : s.title);
            a.put("detail", (s.skills.isEmpty() ? "" : String.join(", ", s.skills) + " · ") + "요청 " + s.prompts + "개");
            a.put("time", s.start);
            a.put("project", s.project);
            out.add(a);
        }
        out.sort((x, y) -> String.valueOf(y.get("time")).compareTo(String.valueOf(x.get("time"))));
        return out.size() > 30 ? new ArrayList<Map<String, Object>>(out.subList(0, 30)) : out;
    }

    private static String num(JsonNode n) {
        double d = n.asDouble();
        return d == Math.rint(d) ? String.valueOf((long) d) : String.valueOf(d);
    }

    /** ~/.kit/ui.json 의 "improvements" 에 적힌 폴더 아래 comparison.json (예: 프로젝트의 docs/evidence) */
    private List<Path> improvementFiles() throws IOException {
        List<Path> out = new ArrayList<Path>();
        Path cfg = env.kitDir().resolve("ui.json");
        if (!Files.exists(cfg)) {
            return out;
        }
        for (JsonNode dir : JSON.readTree(cfg.toFile()).path("improvements")) {
            String raw = dir.asText().replaceFirst("^~", env.home().toString());
            Path base = env.home().getFileSystem().getPath(raw);
            if (!Files.isDirectory(base)) {
                continue;
            }
            try (java.nio.file.DirectoryStream<Path> ds = Files.newDirectoryStream(base)) {
                for (Path d : ds) {
                    Path c = d.resolve("comparison.json");
                    if (Files.exists(c)) {
                        out.add(c);
                    }
                }
            }
        }
        return out;
    }

    /** 레포의 ai-corrections.json (사람이 등록) + 세션 기록에서 2번 이상 실패한 명령 (자동 발견) */
    private List<Map<String, Object>> notes(List<SessionLog.Session> sessions) throws IOException {
        List<Map<String, Object>> out = new ArrayList<Map<String, Object>>();
        Path f = env.repoDir().resolve("evals").resolve("ai-corrections.json");
        if (Files.exists(f)) {
            for (JsonNode c : JSON.readTree(f.toFile()).path("items")) {
                Map<String, Object> n = new LinkedHashMap<String, Object>();
                n.put("source", "manual");
                n.put("text", c.path("ai_said").asText());
                n.put("actual", c.path("actual").asText());
                n.put("reflected", c.hasNonNull("fixed_in_skill") && !c.path("fixed_in_skill").asText().isEmpty());
                out.add(n);
            }
        }
        java.util.Iterator<Map.Entry<String, JsonNode>> it = new Ratings(env).all().fields();
        while (it.hasNext()) {
            JsonNode r = it.next().getValue();
            if ("wrong".equals(r.path("rating").asText())) {
                Map<String, Object> n = new LinkedHashMap<String, Object>();
                n.put("source", "manual");
                n.put("text", r.path("skill").asText("") + " 결과가 틀림 (내가 표시)");
                n.put("actual", r.path("note").asText(""));
                n.put("reflected", false);
                out.add(0, n);
            }
        }
        Map<String, Integer> failed = new HashMap<String, Integer>();
        for (SessionLog.Session s : sessions) {
            for (Map.Entry<String, Integer> e : s.failedCommands.entrySet()) {
                Integer n = failed.get(e.getKey());
                failed.put(e.getKey(), (n == null ? 0 : n) + e.getValue());
            }
        }
        List<Map.Entry<String, Integer>> sorted = new ArrayList<Map.Entry<String, Integer>>(failed.entrySet());
        sorted.sort((a, b) -> b.getValue() - a.getValue());
        for (Map.Entry<String, Integer> e : sorted) {
            if (e.getValue() < 2 || out.size() >= 8) {
                break;
            }
            Map<String, Object> n = new HashMap<String, Object>();
            n.put("source", "auto");
            n.put("text", e.getKey() + " — " + e.getValue() + "번 실패");
            n.put("reflected", false);
            out.add(n);
        }
        return out;
    }
}
