package kit.ui;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import kit.core.KitEnv;

import java.io.IOException;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.LinkOption;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Collection;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;

/**
 * 세션 기록에서 "스킬 없이 반복한 일"을 찾는다. AI를 부르지 않고 키워드로만 센다 (토큰 사용 없음).
 * 패턴은 키트 레포의 kit/insights/patterns.json (팀이 고칠 수 있음), 없으면 jar에 든 기본값을 쓴다.
 */
public final class Recommender {

    private static final ObjectMapper JSON = new ObjectMapper();

    private final KitEnv env;

    public Recommender(KitEnv env) {
        this.env = env;
    }

    public List<Map<String, Object>> recommend(List<SessionLog.Session> sessions, Collection<String> dismissed) throws IOException {
        JsonNode cfg = patterns();
        int minPrompts = cfg.path("minPrompts").asInt(3);
        int minSessions = cfg.path("minSessions").asInt(2);
        Set<String> available = availableSkills();
        String context = teamContext();

        List<Map<String, Object>> out = new ArrayList<Map<String, Object>>();
        for (JsonNode p : cfg.path("patterns")) {
            String id = p.path("id").asText();
            if (dismissed.contains(id) || covered(p, available, context)) {
                continue;
            }
            List<String> keywords = new ArrayList<String>();
            for (JsonNode k : p.path("keywords")) {
                keywords.add(k.asText().toLowerCase(Locale.ROOT));
            }
            int prompts = 0;
            int matchedSessions = 0;
            int steps = 0;
            List<String> examples = new ArrayList<String>();
            for (SessionLog.Session s : sessions) {
                int hits = 0;
                for (String text : s.promptTexts) {
                    if (matches(text, keywords)) {
                        hits++;
                        if (examples.size() < 2 && text.length() <= 60 && !examples.contains(text)) {
                            examples.add(text);
                        }
                    }
                }
                if (hits > 0) {
                    prompts += hits;
                    matchedSessions++;
                    steps += s.prompts == 0 ? 0 : s.toolUses * hits / s.prompts;
                }
            }
            if (prompts < minPrompts || matchedSessions < minSessions) {
                continue;
            }
            Map<String, Object> r = new LinkedHashMap<String, Object>();
            r.put("id", id);
            r.put("kind", p.path("kind").asText("skill"));
            r.put("title", p.path("title").asText());
            r.put("description", p.path("description").asText());
            r.put("prompts", prompts);
            r.put("sessions", matchedSessions);
            r.put("avgSteps", prompts == 0 ? 0 : Math.round((double) steps / prompts));
            r.put("examples", examples);
            out.add(r);
        }
        out.sort((a, b) -> (Integer) b.get("prompts") - (Integer) a.get("prompts"));
        return out;
    }

    static boolean matches(String text, List<String> keywords) {
        String t = text.toLowerCase(Locale.ROOT);
        for (String k : keywords) {
            if (t.contains(k)) {
                return true;
            }
        }
        return false;
    }

    private boolean covered(JsonNode p, Set<String> available, String context) {
        for (JsonNode c : p.path("coveredBy")) {
            if (available.contains(c.asText())) {
                return true;
            }
        }
        String phrase = p.path("coveredByContext").asText("");
        return !phrase.isEmpty() && context.contains(phrase);
    }

    /** 이미 쓸 수 있는 스킬: 키트 레포의 팀 스킬 + 사용자 폴더의 개인 스킬 */
    private Set<String> availableSkills() throws IOException {
        Set<String> names = new HashSet<String>();
        for (Path dir : new Path[]{env.skillsSource(), env.home().resolve(".claude").resolve("skills"),
                env.home().resolve(".agents").resolve("skills")}) {
            if (!Files.isDirectory(dir)) {
                continue;
            }
            try (java.nio.file.DirectoryStream<Path> ds = Files.newDirectoryStream(dir)) {
                for (Path p : ds) {
                    if (Files.isDirectory(p) || Files.isSymbolicLink(p) && Files.exists(p)) {
                        names.add(p.getFileName().toString());
                    }
                }
            }
        }
        return names;
    }

    private String teamContext() throws IOException {
        Path f = env.contextSource();
        return Files.exists(f, LinkOption.NOFOLLOW_LINKS) || Files.exists(f)
                ? new String(Files.readAllBytes(f), StandardCharsets.UTF_8) : "";
    }

    private JsonNode patterns() throws IOException {
        Path f = env.repoDir().resolve("kit").resolve("insights").resolve("patterns.json");
        if (Files.exists(f)) {
            return JSON.readTree(f.toFile());
        }
        try (InputStream in = Recommender.class.getResourceAsStream("/insights/patterns.json")) {
            return in == null ? JSON.createObjectNode() : JSON.readTree(in);
        }
    }

    /** [스킬 초안 만들기]: 내 개인 스킬 폴더에 SKILL.md 초안을 만든다. 만든 경로를 돌려준다 */
    public Path createDraft(String id, List<SessionLog.Session> sessions) throws IOException {
        for (Map<String, Object> r : recommend(sessions, new HashSet<String>())) {
            if (!id.equals(r.get("id")) || !"skill".equals(r.get("kind"))) {
                continue;
            }
            Path dir = env.home().resolve(".claude").resolve("skills").resolve(id);
            if (Files.exists(dir, LinkOption.NOFOLLOW_LINKS)) {
                throw new IOException("이미 같은 이름의 스킬이 있습니다: " + dir);
            }
            @SuppressWarnings("unchecked")
            List<String> examples = (List<String>) r.get("examples");
            StringBuilder sb = new StringBuilder();
            sb.append("---\nname: ").append(id).append('\n');
            sb.append("description: ").append(r.get("description")).append(". ");
            sb.append("\"").append(examples.isEmpty() ? r.get("title") : String.join("\", \"", examples)).append("\" 같은 요청에 사용한다.\n---\n\n");
            sb.append("# ").append(r.get("title")).append("\n\n");
            sb.append("> kit ui가 만든 초안 (").append(r.get("prompts")).append("번 반복된 요청에서). ");
            sb.append("AI에게 \"이 스킬 초안을 다듬어줘\"라고 하면 절차를 채워 준다. 검증한 뒤 팀 스킬로 제안한다.\n\n");
            sb.append("## 절대 규칙\n- (지켜야 할 안전 규칙)\n\n");
            sb.append("## 절차\n1. (첫 단계)\n2. (다음 단계)\n\n");
            sb.append("## 결과 형식\n- (어떤 모양으로 답할지)\n\n");
            sb.append("## 예시 요청\n");
            for (String e : examples) {
                sb.append("- ").append(e).append('\n');
            }
            Files.createDirectories(dir);
            Path skill = dir.resolve("SKILL.md");
            Files.write(skill, sb.toString().getBytes(StandardCharsets.UTF_8));
            return skill;
        }
        throw new IOException("추천 목록에 없는 스킬 후보입니다: " + id);
    }
}
