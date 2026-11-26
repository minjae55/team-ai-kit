package kit.core;

import java.nio.file.Path;
import java.nio.file.Paths;

/** 키트가 쓰는 경로 모음. 테스트에서는 home을 임시 폴더로 바꿔 실제 홈을 건드리지 않는다. */
public final class KitEnv {

    public static final String DEFAULT_REPO = "git@github.com:minjae55/team-ai-kit.git";

    private final Path home;
    private final String repoUrl;

    public KitEnv(Path home, String repoUrl) {
        this.home = home.toAbsolutePath().normalize();
        this.repoUrl = repoUrl;
    }

    /** --home, KIT_HOME_DIR 환경 변수, user.home 순서로 홈 폴더를 정한다. */
    public static KitEnv resolve(String homeOption, String repoOption) {
        String home = firstNonBlank(homeOption, System.getenv("KIT_HOME_DIR"), System.getProperty("user.home"));
        String repo = firstNonBlank(repoOption, System.getenv("KIT_REPO"), DEFAULT_REPO);
        return new KitEnv(Paths.get(home), repo);
    }

    private static String firstNonBlank(String... values) {
        for (String v : values) {
            if (v != null && !v.trim().isEmpty()) {
                return v.trim();
            }
        }
        return null;
    }

    public Path home() { return home; }
    public String repoUrl() { return repoUrl; }
    public Path kitDir() { return home.resolve(".kit"); }
    public Path repoDir() { return kitDir().resolve("repo"); }
    public Path lockFile() { return kitDir().resolve("kit.lock.json"); }
    public Path skillsSource() { return repoDir().resolve("kit").resolve("skills"); }
    public Path contextSource() { return repoDir().resolve("kit").resolve("context").resolve("AGENTS.md"); }

    /** 홈 아래 경로면 ~/... 로, 아니면 절대 경로로 표시한다 (지침 파일 import 줄에 사용). */
    public String display(Path p) {
        Path abs = p.toAbsolutePath().normalize();
        Path realHome = Paths.get(System.getProperty("user.home")).toAbsolutePath().normalize();
        if (home.equals(realHome) && abs.startsWith(home)) {
            return "~/" + home.relativize(abs).toString().replace('\\', '/');
        }
        return abs.toString();
    }
}
