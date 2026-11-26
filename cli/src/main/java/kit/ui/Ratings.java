package kit.ui;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import kit.core.KitEnv;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Instant;
import java.util.Iterator;
import java.util.LinkedHashMap;
import java.util.Map;

/**
 * 스킬을 쓴 결과에 대한 내 평가 (~/.kit/ratings.json).
 * rating: up(도움됨) / down(별로) / wrong(AI가 틀림, note에 실제 원인)
 * 지금은 내 PC에만 저장한다. 팀 집계는 사용 기록을 모으는 3단계 이후.
 */
public final class Ratings {

    private static final ObjectMapper JSON = new ObjectMapper();
    private final Path file;

    public Ratings(KitEnv env) {
        this.file = env.kitDir().resolve("ratings.json");
    }

    public ObjectNode all() throws IOException {
        if (!Files.exists(file)) {
            return JSON.createObjectNode();
        }
        JsonNode n = JSON.readTree(file.toFile());
        return n instanceof ObjectNode ? (ObjectNode) n : JSON.createObjectNode();
    }

    public void rate(String session, String skill, String rating, String note) throws IOException {
        ObjectNode all = all();
        ObjectNode r = JSON.createObjectNode();
        r.put("skill", skill == null ? "" : skill);
        r.put("rating", rating);
        r.put("note", note == null ? "" : note);
        r.put("time", Instant.now().toString());
        all.set(session, r);
        Files.createDirectories(file.getParent());
        JSON.writerWithDefaultPrettyPrinter().writeValue(file.toFile(), all);
    }

    /** 스킬별 [up, down(wrong 포함)] */
    public Map<String, int[]> bySkill() throws IOException {
        Map<String, int[]> out = new LinkedHashMap<String, int[]>();
        ObjectNode all = all();
        Iterator<Map.Entry<String, JsonNode>> it = all.fields();
        while (it.hasNext()) {
            JsonNode r = it.next().getValue();
            String skill = r.path("skill").asText("");
            if (skill.isEmpty()) {
                continue;
            }
            int[] c = out.get(skill);
            if (c == null) {
                c = new int[2];
                out.put(skill, c);
            }
            if ("up".equals(r.path("rating").asText())) {
                c[0]++;
            } else {
                c[1]++;
            }
        }
        return out;
    }
}
