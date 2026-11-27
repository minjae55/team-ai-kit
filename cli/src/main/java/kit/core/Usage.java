package kit.core;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;

import java.io.BufferedReader;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardOpenOption;
import java.time.Duration;
import java.time.Instant;
import java.util.LinkedHashMap;
import java.util.Map;

/**
 * 스킬 사용 기록 (~/.kit/usage.jsonl, 한 줄에 한 번). 내 PC에만 남는다.
 * 남기는 것: 시각, 도구, 스킬 이름, 세션 id, 방식(AI가 고름/직접 호출). 요청 내용은 남기지 않는다.
 */
public final class Usage {

    private static final ObjectMapper JSON = new ObjectMapper();
    private final Path file;

    public Usage(KitEnv env) {
        this.file = env.kitDir().resolve("usage.jsonl");
    }

    public void record(String tool, String skill, String session, String via) throws IOException {
        ObjectNode n = JSON.createObjectNode();
        n.put("time", Instant.now().toString());
        n.put("tool", tool);
        n.put("skill", skill);
        n.put("session", session == null ? "" : session);
        n.put("via", via);
        Files.createDirectories(file.getParent());
        Files.write(file, (JSON.writeValueAsString(n) + "\n").getBytes(StandardCharsets.UTF_8),
                StandardOpenOption.CREATE, StandardOpenOption.APPEND);
    }

    /** 스킬별 사용 요약: 최근 days일 횟수, 마지막 사용 시각(기간 무관) */
    public static final class Stat {
        public int count;
        public Instant lastUsed;
    }

    /** 스킬별 요약과 기록 시작 시각(가장 오래된 기록). 기록이 없으면 빈 맵 */
    public static final class Summary {
        public final Map<String, Stat> bySkill = new LinkedHashMap<String, Stat>();
        public Instant firstRecord;
        public int total;

        /** 기록한 기간이 days일 이상이어야 "그동안 안 썼다"고 말할 수 있다 */
        public boolean coversDays(int days) {
            return firstRecord != null && firstRecord.isBefore(Instant.now().minus(Duration.ofDays(days)));
        }
    }

    public Summary summary(int days) throws IOException {
        Summary sum = new Summary();
        if (!Files.exists(file)) {
            return sum;
        }
        Instant since = Instant.now().minus(Duration.ofDays(days));
        try (BufferedReader r = Files.newBufferedReader(file, StandardCharsets.UTF_8)) {
            String line;
            while ((line = r.readLine()) != null) {
                try {
                    JsonNode n = JSON.readTree(line);
                    Instant t = Instant.parse(n.path("time").asText());
                    String skill = n.path("skill").asText("");
                    if (skill.isEmpty()) {
                        continue;
                    }
                    if (sum.firstRecord == null || t.isBefore(sum.firstRecord)) {
                        sum.firstRecord = t;
                    }
                    Stat st = sum.bySkill.get(skill);
                    if (st == null) {
                        st = new Stat();
                        sum.bySkill.put(skill, st);
                    }
                    if (st.lastUsed == null || t.isAfter(st.lastUsed)) {
                        st.lastUsed = t;
                    }
                    if (!t.isBefore(since)) {
                        st.count++;
                        sum.total++;
                    }
                } catch (RuntimeException | IOException ignored) {
                    // 깨진 줄은 건너뛴다
                }
            }
        }
        return sum;
    }

    /** 최근 days일 동안 스킬별 사용 횟수 */
    public Map<String, Integer> counts(int days) throws IOException {
        Map<String, Integer> out = new LinkedHashMap<String, Integer>();
        if (!Files.exists(file)) {
            return out;
        }
        Instant since = Instant.now().minus(Duration.ofDays(days));
        try (BufferedReader r = Files.newBufferedReader(file, StandardCharsets.UTF_8)) {
            String line;
            while ((line = r.readLine()) != null) {
                try {
                    JsonNode n = JSON.readTree(line);
                    if (Instant.parse(n.path("time").asText()).isBefore(since)) {
                        continue;
                    }
                    String skill = n.path("skill").asText("");
                    Integer c = out.get(skill);
                    out.put(skill, c == null ? 1 : c + 1);
                } catch (RuntimeException | IOException ignored) {
                    // 깨진 줄은 건너뛴다
                }
            }
        }
        return out;
    }
}
