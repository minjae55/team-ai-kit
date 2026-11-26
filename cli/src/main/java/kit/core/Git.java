package kit.core;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.concurrent.TimeUnit;

/** 시스템 git 명령을 실행한다. 인증은 사용자의 git 설정(SSH 등)을 그대로 따른다. */
public final class Git {

    private Git() {}

    public static final class Result {
        public final int code;
        public final String out;
        Result(int code, String out) { this.code = code; this.out = out; }
        public boolean ok() { return code == 0; }
    }

    public static boolean available() {
        try {
            return run(null, "--version").ok();
        } catch (IOException e) {
            return false;
        }
    }

    public static Result run(Path dir, String... args) throws IOException {
        List<String> cmd = new ArrayList<String>();
        cmd.add("git");
        if (dir != null) {
            cmd.add("-C");
            cmd.add(dir.toString());
        }
        cmd.addAll(Arrays.asList(args));
        ProcessBuilder pb = new ProcessBuilder(cmd).redirectErrorStream(true);
        pb.environment().put("GIT_TERMINAL_PROMPT", "0");
        Process p = pb.start();
        String out = readAll(p.getInputStream());
        try {
            if (!p.waitFor(120, TimeUnit.SECONDS)) {
                p.destroyForcibly();
                return new Result(124, "git 시간 초과: " + String.join(" ", args));
            }
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            throw new IOException("git 실행이 중단됨", e);
        }
        return new Result(p.exitValue(), out.trim());
    }

    public static Result runOrThrow(Path dir, String... args) throws IOException {
        Result r = run(dir, args);
        if (!r.ok()) {
            throw new IOException("git " + String.join(" ", args) + " 실패: " + r.out);
        }
        return r;
    }

    public static boolean isRepo(Path dir) {
        return Files.isDirectory(dir.resolve(".git"));
    }

    /** v로 시작하는 태그 중 버전 순으로 가장 높은 것. 없으면 null. */
    public static String latestTag(Path repo) throws IOException {
        Result r = run(repo, "tag", "-l", "v*", "--sort=-v:refname");
        if (!r.ok() || r.out.isEmpty()) {
            return null;
        }
        return r.out.split("\\R")[0].trim();
    }

    public static String shortCommit(Path repo) throws IOException {
        return runOrThrow(repo, "rev-parse", "--short", "HEAD").out;
    }

    private static String readAll(InputStream in) throws IOException {
        ByteArrayOutputStream buf = new ByteArrayOutputStream();
        byte[] b = new byte[8192];
        int n;
        while ((n = in.read(b)) != -1) {
            buf.write(b, 0, n);
        }
        return new String(buf.toByteArray(), StandardCharsets.UTF_8);
    }
}
