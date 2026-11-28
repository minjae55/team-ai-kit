package kit;

import kit.core.AlertWatch;
import kit.core.KitEnv;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.OutputStream;
import java.net.HttpURLConnection;
import java.net.URL;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.stream.Collectors;
import java.util.stream.Stream;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

class WatchTest {

    @TempDir Path tmp;

    static final String GRAFANA = "{\"status\":\"firing\",\"title\":\"[FIRING:1] 슬로우 쿼리 발생\",\"alerts\":["
            + "{\"status\":\"firing\",\"labels\":{\"alertname\":\"슬로우 쿼리 발생\",\"instance\":\"mysqld-exporter:9104\"},"
            + "\"annotations\":{\"summary\":\"최근 5분 동안 1초를 넘긴 쿼리\"},\"startsAt\":\"2026-11-25T21:56:00+09:00\","
            + "\"values\":{\"A\":72},\"fingerprint\":\"abc123\"},"
            + "{\"status\":\"resolved\",\"labels\":{\"alertname\":\"커넥션 대기\"},\"fingerprint\":\"def\"}]}";

    @Test
    void parses_only_firing_alerts_with_labels_values_and_annotations() throws Exception {
        List<AlertWatch.Alert> a = AlertWatch.parse(GRAFANA);
        assertEquals(1, a.size(), "해결(resolved) 알림은 제외");
        assertEquals("슬로우 쿼리 발생", a.get(0).name);
        assertEquals("abc123", a.get(0).key);
        assertTrue(a.get(0).text.contains("A=72") && a.get(0).text.contains("summary: 최근 5분"), a.get(0).text);
    }

    @Test
    void same_alert_runs_once_per_cooldown_and_daily_cap_applies() throws Exception {
        AlertWatch.Options o = new AlertWatch.Options();
        o.cooldownMinutes = 30;
        o.maxPerDay = 2;
        AlertWatch w = new AlertWatch(new KitEnv(tmp, "x"), o);
        AlertWatch.Alert a = AlertWatch.parse(GRAFANA).get(0);
        long t = 1_000_000_000L;
        assertNull(w.admit(a, t));
        assertNotNull(w.admit(a, t + 10 * 60_000L), "쿨다운 안에서는 건너뜀");
        assertNull(w.admit(a, t + 31 * 60_000L), "쿨다운이 지나면 다시 분석");
        AlertWatch.Alert b = AlertWatch.parse(GRAFANA.replace("abc123", "zzz")).get(0);
        assertTrue(w.admit(b, t + 32 * 60_000L).contains("한도"), "24시간 한도");
    }

    @Test
    void webhook_runs_analysis_with_fake_claude_and_writes_result() throws Exception {
        Path home = tmp.resolve("home");
        Path project = Files.createDirectories(tmp.resolve("project"));
        Path fake = tmp.resolve("fake-claude.sh");
        Files.write(fake, ("#!/bin/sh\npwd > \"" + tmp.resolve("cwd.txt") + "\"\ncat <<'J'\n"
                + "{\"type\":\"result\",\"result\":\"## 요약\\n인덱스가 숨김 처리됨\\n## 원인 후보\\n1. INVISIBLE\",\"num_turns\":5,\"total_cost_usd\":0.3}\nJ\n")
                .getBytes(StandardCharsets.UTF_8));
        fake.toFile().setExecutable(true);
        kit.command.WatchCommandTestHook.prepare();
        int code = Main.run(new String[]{"--home", home.toString(), "watch", "--project", project.toString(), "--port", "47491",
                "--claude", fake.toString(), "--no-notify"});
        assertEquals(0, code);
        String token = new String(Files.readAllBytes(home.resolve(".kit/watch-token")), StandardCharsets.UTF_8).trim();
        try {
            assertEquals(403, post("http://127.0.0.1:47491/alert/wrong", GRAFANA), "토큰이 틀리면 거부");
            assertEquals(200, post("http://127.0.0.1:47491/alert/" + token, GRAFANA));
            assertEquals(200, post("http://127.0.0.1:47491/alert/" + token, GRAFANA), "같은 알림 두 번째는 받되 건너뜀");
            Path alerts = home.resolve(".kit/alerts");
            List<Path> files = null;
            for (int i = 0; i < 50; i++) {
                if (Files.isDirectory(alerts)) {
                    try (Stream<Path> s = Files.list(alerts)) {
                        files = s.collect(Collectors.toList());
                    }
                    if (!files.isEmpty()) {
                        break;
                    }
                }
                Thread.sleep(100);
            }
            assertNotNull(files);
            assertEquals(1, files.size(), "쿨다운으로 한 번만 분석");
            String doc = new String(Files.readAllBytes(files.get(0)), StandardCharsets.UTF_8);
            assertTrue(doc.contains("인덱스가 숨김 처리됨") && doc.contains("A=72") && doc.contains("5턴"), doc);
            assertEquals(project.toRealPath().toString(), new String(Files.readAllBytes(tmp.resolve("cwd.txt")), StandardCharsets.UTF_8).trim(),
                    "프로젝트 폴더에서 실행");
        } finally {
            kit.command.WatchCommandTestHook.stop();
        }
    }

    static int post(String url, String body) throws Exception {
        HttpURLConnection c = (HttpURLConnection) new URL(url).openConnection();
        c.setRequestMethod("POST");
        c.setDoOutput(true);
        try (OutputStream os = c.getOutputStream()) {
            os.write(body.getBytes(StandardCharsets.UTF_8));
        }
        return c.getResponseCode();
    }
}
