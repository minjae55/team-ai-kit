package kit;

import picocli.CommandLine;

public final class Main {

    private Main() {}

    public static void main(String[] args) {
        // Windows: 지난 업데이트 때 못 바꾼 kit.jar.new 가 남아 있으면, 이번 실행이 끝난 뒤 바꾸도록 다시 시도
        kit.core.SelfUpdate.deferredSwap(kit.core.KitEnv.resolve(null, null).kitDir().resolve("bin"));
        System.exit(run(args));
    }

    public static int run(String... args) {
        return new CommandLine(new KitCommand()).execute(args);
    }
}
