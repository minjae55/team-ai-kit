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
        Path tmp = bin.resolve("kit.jar.tmp");
        Files.copy(jar, tmp, StandardCopyOption.REPLACE_EXISTING);
        Files.move(tmp, bin.resolve("kit.jar"), StandardCopyOption.REPLACE_EXISTING, StandardCopyOption.ATOMIC_MOVE);
        Files.copy(cli.resolve("kit"), bin.resolve("kit"), StandardCopyOption.REPLACE_EXISTING);
        bin.resolve("kit").toFile().setExecutable(true);
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
