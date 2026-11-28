package kit.core;

import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.concurrent.TimeUnit;

/**
 * Windows 시작 메뉴의 "팀 AI 키트" 바로가기 (누르면 kit ui 화면이 열리고, 창을 닫으면 꺼진다).
 * 설치·업데이트 때 없으면 만들고, 제거 때 지운다. 터미널을 쓰지 않는 팀원도 화면을 열 수 있게 하려는 것.
 */
public final class WindowsShortcut {
    public static final String NAME = "팀 AI 키트.lnk";

    private WindowsShortcut() {}

    public static boolean isWindows() {
        return System.getProperty("os.name", "").toLowerCase().contains("win");
    }

    public static Path path() {
        String appData = System.getenv("APPDATA");
        return appData == null ? null : Paths.get(appData, "Microsoft", "Windows", "Start Menu", "Programs", NAME);
    }

    /** 마지막으로 만들기에 실패한 이유 (없으면 null) */
    public static String lastError;

    /** 없으면 만든다. 만들었으면 true. 실패하면 lastError 에 이유를 남긴다 */
    public static boolean ensure(KitEnv env) {
        lastError = null;
        Path lnk = path();
        Path cmd = env.kitDir().resolve("bin").resolve("kit.cmd");
        if (!isWindows() || lnk == null || Files.exists(lnk) || !Files.exists(cmd)) {
            return false;
        }
        String ps = "$s = (New-Object -ComObject WScript.Shell).CreateShortcut('" + q(lnk) + "'); "
                + "$s.TargetPath = '" + q(cmd) + "'; $s.Arguments = 'ui'; "
                + "$s.WorkingDirectory = '" + q(cmd.getParent()) + "'; $s.Description = '팀 AI 키트 화면'; $s.Save()";
        try {
            Process p = new ProcessBuilder("powershell.exe", "-NoProfile", "-NonInteractive", "-Command", ps)
                    .redirectErrorStream(true).start();
            String out = new String(readAll(p.getInputStream()), java.nio.charset.Charset.defaultCharset()).trim();
            boolean done = p.waitFor(30, TimeUnit.SECONDS);
            if (done && p.exitValue() == 0 && Files.exists(lnk)) {
                return true;
            }
            lastError = !done ? "PowerShell 응답 없음 (30초)" : out.isEmpty() ? "종료 코드 " + p.exitValue() : out;
            return false;
        } catch (Exception e) {
            lastError = e.toString();
            return false;
        }
    }

    public static Path remove(boolean dryRun) throws java.io.IOException {
        Path lnk = path();
        if (!isWindows() || lnk == null || !Files.exists(lnk)) {
            return null;
        }
        if (!dryRun) {
            Files.delete(lnk);
        }
        return lnk;
    }

    private static byte[] readAll(java.io.InputStream in) throws java.io.IOException {
        java.io.ByteArrayOutputStream buf = new java.io.ByteArrayOutputStream();
        byte[] b = new byte[4096];
        int n;
        while ((n = in.read(b)) > 0) {
            buf.write(b, 0, n);
        }
        return buf.toByteArray();
    }

    private static String q(Path p) {
        return p.toString().replace("'", "''");   // PowerShell 작은따옴표 문자열
    }
}
