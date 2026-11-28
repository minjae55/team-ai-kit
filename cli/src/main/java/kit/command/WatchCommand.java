package kit.command;

import com.sun.net.httpserver.HttpServer;
import kit.core.AlertWatch;
import kit.core.KitEnv;
import picocli.CommandLine.Command;
import picocli.CommandLine.Option;

import java.io.ByteArrayOutputStream;
import java.io.InputStream;
import java.io.OutputStream;
import java.net.InetSocketAddress;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.security.SecureRandom;
import java.text.SimpleDateFormat;
import java.util.Date;
import java.util.List;
import java.util.concurrent.Callable;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

@Command(name = "watch", description = "알림 웹훅을 받아 incident-analysis로 원인을 자동 분석한다 (읽기 전용, 같은 알림은 쿨다운 동안 한 번)")
public class WatchCommand extends Common implements Callable<Integer> {

    @Option(names = "--project", required = true, description = "분석할 프로젝트 폴더 (AGENTS.md가 있는 곳)")
    String project;

    @Option(names = "--port", description = "받을 포트 (기본 47400)")
    int port = 47400;

    @Option(names = "--listen-all", description = "모든 주소에서 받기 (Docker 안의 Grafana가 보내야 할 때). 주소의 토큰이 있어야만 받는다")
    boolean listenAll;

    @Option(names = "--cooldown", description = "같은 알림을 다시 분석하기까지 분 (기본 30)")
    int cooldown = 30;

    @Option(names = "--max-per-day", description = "24시간 최대 분석 횟수 (기본 10)")
    int maxPerDay = 10;

    @Option(names = "--slack-webhook-file", description = "Slack 웹훅 주소가 든 파일 경로 (주소는 화면에 출력하지 않음)")
    String slackFile;

    @Option(names = "--dry-run", description = "AI를 부르지 않고 받은 알림과 보낼 요청만 기록 (연결 시험용)")
    boolean dryRun;

    @Option(names = "--claude", description = "claude 실행 파일 (기본: claude)")
    String claude = "claude";

    @Option(names = "--no-notify", description = "데스크톱 알림을 띄우지 않음")
    boolean noNotify;

    /** 테스트용: 서버가 뜨면 내려가는 래치 */
    static CountDownLatch startedForTest;
    static HttpServer serverForTest;

    @Override
    public Integer call() throws Exception {
        KitEnv env = env();
        Path proj = Paths.get(project).toAbsolutePath().normalize();
        if (!Files.isDirectory(proj)) {
            stdout.println("프로젝트 폴더가 없습니다: " + proj);
            return 1;
        }
        AlertWatch.Options o = new AlertWatch.Options();
        o.project = proj;
        o.cooldownMinutes = cooldown;
        o.maxPerDay = maxPerDay;
        o.dryRun = dryRun;
        o.claude = claude;
        o.desktopNotify = !noNotify;
        o.slackWebhookFile = slackFile == null ? null : Paths.get(slackFile);
        AlertWatch watch = new AlertWatch(env, o);
        String token = token(env);
        ExecutorService worker = Executors.newSingleThreadExecutor();   // 한 번에 하나씩 분석

        HttpServer server = HttpServer.create(new InetSocketAddress(listenAll ? "0.0.0.0" : "127.0.0.1", port), 0);
        server.createContext("/", ex -> {
            String path = ex.getRequestURI().getPath();
            int code = 404;
            String reply = "not found";
            if ("/health".equals(path)) {
                code = 200;
                reply = "ok";
            } else if (("/alert/" + token).equals(path) && "POST".equals(ex.getRequestMethod())) {
                String body = read(ex.getRequestBody());
                try {
                    List<AlertWatch.Alert> alerts = AlertWatch.parse(body);
                    for (AlertWatch.Alert a : alerts) {
                        String skip = watch.admit(a, System.currentTimeMillis());
                        if (skip != null) {
                            log("건너뜀 " + a.name + ": " + skip);
                            continue;
                        }
                        log("분석 시작 " + a.name + (dryRun ? " (연습 실행)" : ""));
                        worker.submit(() -> {
                            try {
                                log("분석 끝 " + a.name + " → " + watch.analyze(a));
                            } catch (Exception e) {
                                log("분석 실패 " + a.name + ": " + e.getMessage());
                            }
                        });
                    }
                    if (alerts.isEmpty()) {
                        log("받음: firing 알림 없음 (해결 알림 등)");
                    }
                    code = 200;
                    reply = "ok";
                } catch (Exception e) {
                    code = 400;
                    reply = "bad request";
                }
            } else if (path.startsWith("/alert/")) {
                code = 403;
                reply = "forbidden";
            }
            byte[] b = reply.getBytes(StandardCharsets.UTF_8);
            ex.sendResponseHeaders(code, b.length);
            try (OutputStream os = ex.getResponseBody()) {
                os.write(b);
            }
        });
        server.start();
        String host = listenAll ? "host.docker.internal" : "127.0.0.1";
        stdout.println("알림 자동 분석 대기 중 (끝내려면 Ctrl+C)");
        stdout.println("  프로젝트: " + proj);
        stdout.println("  웹훅 주소: http://" + host + ":" + port + "/alert/" + token);
        stdout.println("    (Grafana가 Docker 안에 있으면 --listen-all 로 띄우고 host.docker.internal 주소를 쓴다)");
        stdout.println("  같은 알림 쿨다운 " + cooldown + "분, 24시간 최대 " + maxPerDay + "번" + (dryRun ? ", 연습 실행(AI 안 부름)" : ""));
        stdout.println("  결과: " + watch.alertsDir());
        if (startedForTest != null) {
            serverForTest = server;
            startedForTest.countDown();
            return 0;
        }
        Thread.currentThread().join();
        return 0;
    }

    private void log(String msg) {
        stdout.println(new SimpleDateFormat("HH:mm:ss").format(new Date()) + " " + msg);
    }

    static String token(KitEnv env) throws java.io.IOException {
        Path f = env.kitDir().resolve("watch-token");
        if (Files.exists(f)) {
            return new String(Files.readAllBytes(f), StandardCharsets.UTF_8).trim();
        }
        byte[] r = new byte[16];
        new SecureRandom().nextBytes(r);
        StringBuilder sb = new StringBuilder();
        for (byte x : r) {
            sb.append(String.format("%02x", x));
        }
        Files.createDirectories(f.getParent());
        Files.write(f, sb.toString().getBytes(StandardCharsets.UTF_8));
        return sb.toString();
    }

    private static String read(InputStream in) throws java.io.IOException {
        ByteArrayOutputStream out = new ByteArrayOutputStream();
        byte[] buf = new byte[8192];
        int n;
        while ((n = in.read(buf)) > 0) {
            out.write(buf, 0, n);
            if (out.size() > 1_000_000) {
                break;
            }
        }
        return new String(out.toByteArray(), StandardCharsets.UTF_8);
    }
}
