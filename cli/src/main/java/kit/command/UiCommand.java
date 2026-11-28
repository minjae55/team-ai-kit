package kit.command;

import kit.core.KitEnv;
import kit.ui.UiServer;
import picocli.CommandLine.Command;
import picocli.CommandLine.Option;

import java.util.concurrent.Callable;
import java.util.concurrent.CountDownLatch;

@Command(name = "ui", description = "브라우저에서 kit 화면을 연다 (내 PC에서만 열림, 끝내려면 Ctrl+C)")
public class UiCommand extends Common implements Callable<Integer> {

    @Option(names = "--port", description = "포트 (기본: 비어 있는 포트 자동 선택)")
    int port;

    @Option(names = "--no-open", description = "브라우저를 자동으로 열지 않는다")
    boolean noOpen;

    @Override
    public Integer call() throws Exception {
        KitEnv env = env();
        UiServer server = new UiServer(env);
        int p = server.start(port);
        String url = "http://127.0.0.1:" + p + "/?t=" + server.token();
        stdout.println("kit 화면: " + url);
        stdout.println("이 창을 닫거나 Ctrl+C 를 누르면 화면도 꺼집니다");
        if (!noOpen) {
            open(url);
        }
        CountDownLatch done = new CountDownLatch(1);
        Runtime.getRuntime().addShutdownHook(new Thread(() -> {
            server.stop();
            done.countDown();
        }));
        done.await();
        return 0;
    }

    private void open(String url) {
        String os = System.getProperty("os.name", "").toLowerCase();
        if (os.contains("win")) {
            // Windows: Java 내장 기능으로 기본 브라우저를 열고, 안 되면 start 명령으로
            try {
                if (java.awt.Desktop.isDesktopSupported() && java.awt.Desktop.getDesktop().isSupported(java.awt.Desktop.Action.BROWSE)) {
                    java.awt.Desktop.getDesktop().browse(new java.net.URI(url));
                    return;
                }
            } catch (Exception | Error ignored) {
                // 아래 start 로
            }
            try {
                new ProcessBuilder("cmd", "/c", "start", "\"\"", url).start();
                return;
            } catch (Exception ignored) {
                // 아래 안내로
            }
            stdout.println("브라우저를 열지 못했습니다. 위 주소를 브라우저에 붙여 넣으세요");
            return;
        }
        String[] cmd = os.contains("mac") ? new String[]{"open", url} : new String[]{"xdg-open", url};
        try {
            new ProcessBuilder(cmd).inheritIO().start();
        } catch (Exception e) {
            stdout.println("브라우저를 열지 못했습니다. 위 주소를 직접 여세요");
        }
    }
}
