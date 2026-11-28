package kit.core;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;

import java.io.IOException;
import java.nio.file.DirectoryStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Iterator;
import java.util.Map;

/** 스킬별 검증 결과 (evals/&lt;스킬&gt;/verified.json, tools/eval/run.py 가 기록). 시나리오 수 대비 통과 수와 최근 날짜 */
public final class Verified {
    private static final ObjectMapper JSON = new ObjectMapper();

    public int passed;
    public int total;
    public String date;

    public static Verified read(Path repo, String skill) {
        Path dir = repo.resolve("evals").resolve(skill);
        Path file = dir.resolve("verified.json");
        if (!Files.exists(file)) {
            return null;
        }
        try {
            Verified v = new Verified();
            Path scenarios = dir.resolve("scenarios");
            if (Files.isDirectory(scenarios)) {
                try (DirectoryStream<Path> ds = Files.newDirectoryStream(scenarios)) {
                    for (Path p : ds) {
                        if (Files.exists(p.resolve("case.json"))) {
                            v.total++;
                        }
                    }
                }
            }
            JsonNode cases = JSON.readTree(file.toFile()).path("cases");
            for (Iterator<Map.Entry<String, JsonNode>> it = cases.fields(); it.hasNext(); ) {
                Map.Entry<String, JsonNode> e = it.next();
                if (!Files.exists(scenarios.resolve(e.getKey()).resolve("case.json"))) {
                    continue;   // 지워진 시나리오는 세지 않는다
                }
                if (e.getValue().path("passed").asBoolean()) {
                    v.passed++;
                }
                String d = e.getValue().path("date").asText(null);
                if (d != null && (v.date == null || d.compareTo(v.date) > 0)) {
                    v.date = d;
                }
            }
            return v;
        } catch (IOException e) {
            return null;
        }
    }

    public String label() {
        return "검증 " + passed + "/" + total + (date == null ? "" : " · " + date.substring(5));
    }
}
