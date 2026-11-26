package kit.ui;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import kit.core.KitEnv;

import java.io.IOException;
import java.io.InputStream;
import java.net.HttpURLConnection;
import java.net.URL;
import java.net.URLEncoder;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * 시스템 현황: ~/.kit/ui.json 의 "prometheus" 주소와 "metrics" 목록을 읽어 최근 30분 값을 가져온다.
 * 예: {"prometheus":"http://localhost:9090","metrics":[{"label":"협의 목록 p95","query":"...","unit":"ms","scale":1000}]}
 */
public final class Metrics {

    private static final ObjectMapper JSON = new ObjectMapper();
    private final KitEnv env;

    public Metrics(KitEnv env) {
        this.env = env;
    }

    public Map<String, Object> read() throws IOException {
        Map<String, Object> out = new LinkedHashMap<String, Object>();
        Path cfg = env.kitDir().resolve("ui.json");
        if (!Files.exists(cfg)) {
            out.put("configured", false);
            return out;
        }
        JsonNode c = JSON.readTree(cfg.toFile());
        String prom = c.path("prometheus").asText("");
        if (prom.isEmpty() || !c.path("metrics").isArray() || c.path("metrics").size() == 0) {
            out.put("configured", false);
            return out;
        }
        out.put("configured", true);
        out.put("title", c.path("metricsTitle").asText("시스템 현황"));
        List<Map<String, Object>> items = new ArrayList<Map<String, Object>>();
        boolean reachable = true;
        long end = System.currentTimeMillis() / 1000;
        long start = end - 30 * 60;
        for (JsonNode m : c.path("metrics")) {
            Map<String, Object> item = new LinkedHashMap<String, Object>();
            item.put("label", m.path("label").asText());
            item.put("unit", m.path("unit").asText(""));
            double scale = m.path("scale").asDouble(1);
            boolean lowerIsBetter = m.path("lowerIsBetter").asBoolean(true);
            item.put("lowerIsBetter", lowerIsBetter);
            List<Double> points = new ArrayList<Double>();
            if (reachable) {
                try {
                    String url = prom.replaceAll("/+$", "") + "/api/v1/query_range?query="
                            + URLEncoder.encode(m.path("query").asText(), "UTF-8")
                            + "&start=" + start + "&end=" + end + "&step=60";
                    JsonNode res = get(url);
                    JsonNode values = res.path("data").path("result").path(0).path("values");
                    for (JsonNode v : values) {
                        String s = v.path(1).asText();
                        if (!"NaN".equals(s) && !s.contains("Inf")) {
                            points.add(Double.parseDouble(s) * scale);
                        }
                    }
                } catch (IOException e) {
                    reachable = false;
                }
            }
            item.put("points", points);
            item.put("value", points.isEmpty() ? null : points.get(points.size() - 1));
            item.put("first", points.isEmpty() ? null : points.get(0));
            items.add(item);
        }
        out.put("reachable", reachable);
        out.put("items", items);
        return out;
    }

    private static JsonNode get(String url) throws IOException {
        HttpURLConnection conn = (HttpURLConnection) new URL(url).openConnection();
        conn.setConnectTimeout(1500);
        conn.setReadTimeout(3000);
        if (conn.getResponseCode() != 200) {
            throw new IOException("prometheus " + conn.getResponseCode());
        }
        try (InputStream in = conn.getInputStream()) {
            return JSON.readTree(in);
        }
    }
}
