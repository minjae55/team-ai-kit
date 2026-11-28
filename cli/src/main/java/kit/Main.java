package kit;

import picocli.CommandLine;

public final class Main {

    private Main() {}

    public static void main(String[] args) {
        java.nio.file.Path bin = kit.core.KitEnv.resolve(null, null).kitDir().resolve("bin");
        java.nio.file.Path next = bin.resolve("kit.jar.new");
        // Windows: 실행 중인 kit.jar 는 잠겨서 업데이트 때 못 바꿨다 → 받아 둔 새 버전으로 대신 실행하고, 끝나면 교체를 다시 시도
        if (kit.core.WindowsShortcut.isWindows() && java.nio.file.Files.exists(next) && System.getProperty("kit.delegated") == null) {
            try {
                java.util.List<String> cmd = new java.util.ArrayList<String>();
                cmd.add(java.nio.file.Paths.get(System.getProperty("java.home"), "bin", "java").toString());
                cmd.add("-Dkit.delegated=1");
                cmd.add("-jar");
                cmd.add(next.toString());
                cmd.addAll(java.util.Arrays.asList(args));
                int code = new ProcessBuilder(cmd).inheritIO().start().waitFor();
                kit.core.SelfUpdate.deferredSwap(bin);
                System.exit(code);
            } catch (Exception e) {
                // 새 버전 실행에 실패하면 지금 버전으로 계속
            }
        }
        if (System.getProperty("kit.delegated") == null) {
            kit.core.SelfUpdate.deferredSwap(bin);
        }
        System.exit(run(args));
    }

    public static int run(String... args) {
        return new CommandLine(new KitCommand()).execute(args);
    }
}
