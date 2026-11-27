package kit.command;

import kit.core.Git;
import kit.core.KitEnv;
import kit.core.LockFile;
import picocli.CommandLine.Command;
import picocli.CommandLine.Parameters;

import java.util.concurrent.Callable;

/** 버전 고정과 되돌리기 */
public final class PinCommands {

    private PinCommands() {}

    @Command(name = "pin", description = "특정 버전에 고정한다 (자동 업데이트도 이 버전에 머묾)")
    public static class Pin extends Common implements Callable<Integer> {
        @Parameters(index = "0", description = "버전 태그 (예: v0.3.0)")
        String version;

        @Override
        public Integer call() throws Exception {
            KitEnv env = env();
            LockFile lock = LockFile.readOrNull(env.lockFile());
            if (lock == null) {
                error("설치 기록이 없습니다. 먼저 kit install 을 실행하세요");
                return 1;
            }
            Git.run(env.repoDir(), "fetch", "--tags", "--quiet", "origin");
            if (!Git.run(env.repoDir(), "rev-parse", "--verify", "--quiet", "refs/tags/" + version).ok()) {
                error("없는 버전입니다: " + version + " (받아 둔 태그: " + Git.run(env.repoDir(), "tag", "-l", "v*").out.replace('\n', ' ') + ")");
                return 1;
            }
            lock.pinned = version;
            lock.write(env.lockFile());
            return runSync(env, version, "고정했습니다: " + version + " (해제: kit unpin)");
        }
    }

    @Command(name = "unpin", description = "버전 고정을 풀고 최신 버전으로 맞춘다")
    public static class Unpin extends Common implements Callable<Integer> {
        @Override
        public Integer call() throws Exception {
            KitEnv env = env();
            LockFile lock = LockFile.readOrNull(env.lockFile());
            if (lock == null) {
                error("설치 기록이 없습니다");
                return 1;
            }
            lock.pinned = null;
            lock.write(env.lockFile());
            return runSync(env, null, "고정을 풀었습니다. 앞으로 최신 버전을 따라갑니다");
        }
    }

    @Command(name = "rollback", description = "직전 버전으로 되돌리고 그 버전에 고정한다")
    public static class Rollback extends Common implements Callable<Integer> {
        @Override
        public Integer call() throws Exception {
            KitEnv env = env();
            LockFile lock = LockFile.readOrNull(env.lockFile());
            if (lock == null || lock.previous == null) {
                error("되돌릴 직전 버전 기록이 없습니다");
                return 1;
            }
            String target = lock.previous;
            lock.pinned = target;
            lock.write(env.lockFile());
            return runSync(env, target, "되돌렸습니다: " + target + " 에 고정 (문제가 해결되면 kit unpin)");
        }
    }

}
