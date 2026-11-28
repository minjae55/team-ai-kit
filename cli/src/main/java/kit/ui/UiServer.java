package kit.ui;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.sun.net.httpserver.HttpExchange;
import com.sun.net.httpserver.HttpServer;
import kit.Main;
import kit.core.KitEnv;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.io.PrintStream;
import java.net.InetAddress;
import java.net.InetSocketAddress;
import java.net.URLDecoder;
import java.nio.charset.StandardCharsets;
import java.nio.file.Path;
import java.security.SecureRandom;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.Executors;

/**
 * 내 PC에서만 열리는 kit 화면 서버.
 * - 127.0.0.1 에만 열고, 실행할 때마다 새로 만드는 일회용 열쇠(token)가 있어야 응답한다
 * - Host 헤더가 127.0.0.1/localhost 가 아니면 거부한다 (다른 웹사이트가 이 화면을 조작하지 못하게)
 * - 버튼은 kit 명령(sync, install, skill)을 그대로 실행한다
 */
public final class UiServer {

    private static final ObjectMapper JSON = new ObjectMapper();

    private final KitEnv env;
    private final String token;
    private HttpServer server;

    public UiServer(KitEnv env) {
        this.env = env;
        byte[] b = new byte[18];
        new SecureRandom().nextBytes(b);
        StringBuilder sb = new StringBuilder();
        for (byte x : b) {
            sb.append(String.format("%02x", x));
        }
        this.token = sb.toString();
    }

    public String token() { return token; }

    public int start(int port) throws IOException {
        server = HttpServer.create(new InetSocketAddress(InetAddress.getLoopbackAddress(), port), 0);
        server.createContext("/", this::handle);
        server.setExecutor(Executors.newSingleThreadExecutor());
        server.start();
        return server.getAddress().getPort();
    }

    public void stop() {
        if (server != null) {
            server.stop(0);
        }
    }

    private void handle(HttpExchange ex) throws IOException {
        try {
            String host = ex.getRequestHeaders().getFirst("Host");
            if (host == null || !(host.startsWith("127.0.0.1:") || host.startsWith("localhost:"))) {
                send(ex, 403, "text/plain", "forbidden");
                return;
            }
            String path = ex.getRequestURI().getPath();
            Map<String, String> query = parse(ex.getRequestURI().getRawQuery());
            if ("/".equals(path)) {
                if (!token.equals(query.get("t"))) {
                    send(ex, 403, "text/plain; charset=utf-8", "kit ui 를 다시 실행해서 새 주소로 여세요");
                    return;
                }
                send(ex, 200, "text/html; charset=utf-8", page().replace("__KIT_TOKEN__", token));
                return;
            }
            if (!token.equals(ex.getRequestHeaders().getFirst("X-Kit-Token"))) {
                send(ex, 403, "application/json", "{\"error\":\"token\"}");
                return;
            }
            if ("/api/state".equals(path) && "GET".equals(ex.getRequestMethod())) {
                json(ex, 200, new UiData(env).state());
                return;
            }
            if (!"POST".equals(ex.getRequestMethod())) {
                send(ex, 405, "text/plain", "method");
                return;
            }
            Map<String, String> form = parse(new String(readAll(ex.getRequestBody()), StandardCharsets.UTF_8));
            if ("/api/rate".equals(path)) {
                String session = form.get("session");
                String rating = form.get("rating");
                if (session == null || !session.matches("[A-Za-z0-9-]+") || rating == null || !rating.matches("up|down|wrong")) {
                    send(ex, 400, "application/json", "{\"error\":\"rate\"}");
                    return;
                }
                String note = form.get("note");
                new Ratings(env).rate(session, form.get("skill"), rating, note == null ? "" : note.substring(0, Math.min(note.length(), 300)));
                Map<String, Object> res = new LinkedHashMap<String, Object>();
                res.put("code", 0);
                res.put("output", "wrong".equals(rating) ? "AI 실수 노트에 기록했어요. 팀에 알려 스킬을 고치면 모두가 같은 실수를 안 해요." : "평가를 남겼어요.");
                json(ex, 200, res);
                return;
            }
            if ("/api/dismiss".equals(path) || "/api/draft".equals(path)) {
                String id = form.get("id");
                if (id == null || !id.matches("[a-z0-9-]+")) {
                    send(ex, 400, "application/json", "{\"error\":\"id\"}");
                    return;
                }
                Map<String, Object> res = new LinkedHashMap<String, Object>();
                UiData data = new UiData(env);
                if ("/api/dismiss".equals(path)) {
                    data.dismiss(id);
                    res.put("code", 0);
                    res.put("output", "숨겼어요. 다시 보려면 ~/.kit/ui-state.json 에서 지우면 돼요");
                } else {
                    try {
                        Path skill = new Recommender(env).createDraft(id, data.sessions());
                        res.put("code", 0);
                        res.put("output", "개인 스킬 초안을 만들었어요: " + skill
                                + "\n\nAI 세션에서 \"이 스킬 초안을 다듬어줘\"라고 하면 절차를 채워 줘요."
                                + "\n충분히 써 보고 괜찮으면 팀 스킬로 PR을 올려 주세요.");
                    } catch (IOException e) {
                        res.put("code", 1);
                        res.put("output", e.getMessage());
                    }
                }
                json(ex, 200, res);
                return;
            }
            List<String> args = new ArrayList<String>();
            if ("/api/sync".equals(path)) {
                args.add("sync");
            } else if ("/api/install".equals(path)) {
                args.add("install");
                String tool = form.get("tool");
                if (tool != null && tool.matches("[a-z]+")) {
                    args.add("--tools");
                    args.add(tool);
                }
            } else if ("/api/auto".equals(path)) {
                args.add("auto");
                args.add("true".equals(form.get("on")) ? "on" : "off");
            } else if ("/api/export".equals(path)) {
                args.add("export");
            } else if ("/api/uninstall".equals(path)) {
                // 초기화: preview(미리보기, 아무것도 안 바꿈) → remove(키트 설치 제거) 또는 purge(~/.kit 까지 삭제)
                String mode = form.get("mode");
                args.add("uninstall");
                if ("preview".equals(mode)) {
                    args.add("--dry-run");
                } else if ("purge".equals(mode)) {
                    args.add("--purge");
                } else if (!"remove".equals(mode)) {
                    send(ex, 400, "application/json", "{\"error\":\"mode\"}");
                    return;
                }
            } else if ("/api/rollback".equals(path)) {
                args.add("rollback");
            } else if ("/api/unpin".equals(path)) {
                args.add("unpin");
            } else if ("/api/skill".equals(path)) {
                String name = form.get("name");
                if (name == null || !name.matches("[a-z0-9-]+")) {
                    send(ex, 400, "application/json", "{\"error\":\"name\"}");
                    return;
                }
                args.add("skill");
                args.add("true".equals(form.get("on")) ? "enable" : "disable");
                args.add(name);
            } else {
                send(ex, 404, "text/plain", "not found");
                return;
            }
            json(ex, 200, run(args));
        } catch (Exception e) {
            Map<String, Object> err = new LinkedHashMap<String, Object>();
            err.put("error", e.toString());
            json(ex, 500, err);
        }
    }

    /** kit 명령을 같은 프로세스에서 실행하고 출력을 돌려준다 */
    private synchronized Map<String, Object> run(List<String> args) {
        List<String> all = new ArrayList<String>();
        all.add("--home");
        all.add(env.home().toString());
        all.add("--repo");
        all.add(env.repoUrl());
        all.addAll(args);
        PrintStream real = System.out;
        ByteArrayOutputStream buf = new ByteArrayOutputStream();
        int code;
        try {
            System.setOut(new PrintStream(buf, true, "UTF-8"));
            code = Main.run(all.toArray(new String[0]));
        } catch (IOException e) {
            code = 1;
        } finally {
            System.setOut(real);
        }
        Map<String, Object> m = new LinkedHashMap<String, Object>();
        m.put("code", code);
        m.put("output", new String(buf.toByteArray(), StandardCharsets.UTF_8));
        return m;
    }

    private static String page() throws IOException {
        try (InputStream in = UiServer.class.getResourceAsStream("/ui/index.html")) {
            if (in == null) {
                throw new IOException("화면 파일(ui/index.html)이 jar에 없습니다");
            }
            return new String(readAll(in), StandardCharsets.UTF_8);
        }
    }

    private static void json(HttpExchange ex, int code, Object body) throws IOException {
        send(ex, code, "application/json; charset=utf-8", JSON.writeValueAsString(body));
    }

    private static void send(HttpExchange ex, int code, String type, String body) throws IOException {
        byte[] b = body.getBytes(StandardCharsets.UTF_8);
        ex.getResponseHeaders().set("Content-Type", type);
        ex.getResponseHeaders().set("Cache-Control", "no-store");
        ex.sendResponseHeaders(code, b.length);
        try (OutputStream os = ex.getResponseBody()) {
            os.write(b);
        }
    }

    private static Map<String, String> parse(String raw) throws IOException {
        Map<String, String> m = new LinkedHashMap<String, String>();
        if (raw == null || raw.isEmpty()) {
            return m;
        }
        for (String pair : raw.split("&")) {
            int eq = pair.indexOf('=');
            String k = URLDecoder.decode(eq < 0 ? pair : pair.substring(0, eq), "UTF-8");
            String v = eq < 0 ? "" : URLDecoder.decode(pair.substring(eq + 1), "UTF-8");
            m.put(k, v);
        }
        return m;
    }

    private static byte[] readAll(InputStream in) throws IOException {
        ByteArrayOutputStream buf = new ByteArrayOutputStream();
        byte[] b = new byte[8192];
        int n;
        while ((n = in.read(b)) != -1) {
            buf.write(b, 0, n);
        }
        return buf.toByteArray();
    }
}
