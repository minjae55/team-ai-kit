package kit.core;

import java.io.File;
import java.io.IOException;
import java.nio.file.DirectoryStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.TimeUnit;

/**
 * kit 명령 자체(~/.kit/bin/kit.jar)를 키트 레포의 현재 버전으로 교체한다.
 * 레포에 빌드된 jar(dist/kit.jar, 배포 때 tools/release.sh가 넣음)가 있으면 복사만 하고, 없으면 소스에서 빌드한다.
 * install.sh로 설치한 경우(~/.kit/bin/kit.jar가 있을 때)만 동작한다.
 */
public final class SelfUpdate {

    private SelfUpdate() {}

    public static boolean installedByScript(KitEnv env) {
        return Files.exists(env.kitDir().resolve("bin").resolve("kit.jar"));
    }

    /** 지금 바로 빌드하고 교체한다 (수동 kit sync). 성공하면 null, 실패하면 이유 */
    public static String now(KitEnv env) {
        try {
            Path cli = env.repoDir().resolve("cli");
            Path dist = distJar(env);
            if (Files.isRegularFile(dist)) {
                install(dist, cli, env.kitDir().resolve("bin"));
                return null;
            }
            ProcessBuilder pb = new ProcessBuilder("sh", "gradlew", "--quiet", "fatJar").directory(cli.toFile())
                    .redirectErrorStream(true).redirectOutput(env.kitDir().resolve("self-update.log").toFile());
            Process p = pb.start();
            if (!p.waitFor(10, TimeUnit.MINUTES) || p.exitValue() != 0) {
                return "빌드 실패 (자세한 내용: ~/.kit/self-update.log)";
            }
            Path jar = newestJar(cli.resolve("build").resolve("libs"));
            if (jar == null) {
                return "빌드 결과 jar를 찾지 못함";
            }
            install(jar, cli, env.kitDir().resolve("bin"));
            return null;
        } catch (IOException e) {
            return e.getMessage();
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            return "중단됨";
        }
    }

    /**
     * 뒤에서 빌드·교체를 시작하고 바로 돌아온다 (자동 업데이트 훅). 세션 시작을 막지 않기 위해서다.
     * 빌드에 1~2분 걸릴 수 있어서, 교체된 kit은 다음 실행부터 적용된다.
     */
    public static void background(KitEnv env) throws IOException {
        if (Files.isRegularFile(distJar(env))) {  // 복사만 하면 되니 바로 끝난다
            String err = now(env);
            if (err != null) {
                throw new IOException(err);
            }
            return;
        }
        Path cli = env.repoDir().resolve("cli");
        Path bin = env.kitDir().resolve("bin");
        String script = "cd " + q(cli) + " && sh gradlew --quiet fatJar"
                + " && jar=$(ls build/libs/kit-*-all.jar | sort -V | tail -1)"
                + " && cp \"$jar\" " + q(bin.resolve("kit.jar.tmp"))
                + " && mv -f " + q(bin.resolve("kit.jar.tmp")) + " " + q(bin.resolve("kit.jar"))
                + " && cp kit " + q(bin.resolve("kit")) + " && chmod +x " + q(bin.resolve("kit"));
        new ProcessBuilder("sh", "-c", "nohup sh -c " + q(script) + " > " + q(env.kitDir().resolve("self-update.log")) + " 2>&1 &")
                .redirectErrorStream(true).redirectOutput(new File("/dev/null")).start();
    }

    public static Path distJar(KitEnv env) {
        return env.repoDir().resolve("dist").resolve("kit.jar");
    }

    private static void install(Path jar, Path cli, Path bin) throws IOException {
        Files.createDirectories(bin);
        if (WindowsShortcut.isWindows()) {
            installWindows(jar, cli, bin);
            return;
        }
        Path tmp = bin.resolve("kit.jar.tmp");
        Files.copy(jar, tmp, StandardCopyOption.REPLACE_EXISTING);
        Files.move(tmp, bin.resolve("kit.jar"), StandardCopyOption.REPLACE_EXISTING, StandardCopyOption.ATOMIC_MOVE);
        Files.copy(cli.resolve("kit"), bin.resolve("kit"), StandardCopyOption.REPLACE_EXISTING);
        bin.resolve("kit").toFile().setExecutable(true);
    }

    /**
     * Windows는 실행 중인 kit.jar 를 잠가서 덮어쓸 수 없다. 새 jar를 kit.jar.new 로 두고,
     * 바꿀 수 없으면 이 프로세스가 끝난 뒤 몇 초 있다가 바꾸는 작업을 따로 띄운다 (다음 kit 실행 때도 남아 있으면 다시 시도).
     */
    private static void installWindows(Path jar, Path cli, Path bin) throws IOException {
        Path next = bin.resolve("kit.jar.new");
        Files.copy(jar, next, StandardCopyOption.REPLACE_EXISTING);
        try {
            Files.move(next, bin.resolve("kit.jar"), StandardCopyOption.REPLACE_EXISTING);
        } catch (IOException locked) {
            deferredSwap(bin);
        }
        Files.copy(cli.resolve("kit"), bin.resolve("kit"), StandardCopyOption.REPLACE_EXISTING);
    }

    /** kit.jar.new 가 있으면, 잠시 뒤(지금 kit 이 끝난 뒤) kit.jar 로 바꾸는 cmd 작업을 띄운다 */
    public static void deferredSwap(Path bin) {
        Path next = bin.resolve("kit.jar.new");
        if (!WindowsShortcut.isWindows() || !Files.exists(next)) {
            return;
        }
        String cmd = "ping -n 4 127.0.0.1 >nul & move /y \"" + next + "\" \"" + bin.resolve("kit.jar") + "\" >nul";
        try {
            new ProcessBuilder("cmd", "/c", cmd).redirectErrorStream(true).redirectOutput(new File("NUL")).start();
        } catch (IOException ignored) {
            // 다음 실행 때 다시 시도
        }
    }

    private static String q(Object s) {
        return "'" + s.toString().replace("'", "'\\''") + "'";
    }

    private static Path newestJar(Path libs) throws IOException {
        List<Path> jars = new ArrayList<Path>();
        if (!Files.isDirectory(libs)) {
            return null;
        }
        try (DirectoryStream<Path> ds = Files.newDirectoryStream(libs, "kit-*-all.jar")) {
            for (Path p : ds) {
                jars.add(p);
            }
        }
        if (jars.isEmpty()) {
            return null;
        }
        jars.sort((a, b) -> Long.compare(a.toFile().lastModified(), b.toFile().lastModified()));
        return jars.get(jars.size() - 1);
    }
}
