package kit.core;

import java.io.File;
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

    /** 없으면 만든다. 만들었으면 true */
    public static boolean ensure(KitEnv env) {
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
                    .redirectErrorStream(true).redirectOutput(new File("NUL")).start();
            return p.waitFor(30, TimeUnit.SECONDS) && p.exitValue() == 0 && Files.exists(lnk);
        } catch (Exception e) {
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

    private static String q(Path p) {
        return p.toString().replace("'", "''");   // PowerShell 작은따옴표 문자열
    }
}
