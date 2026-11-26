package kit;

import picocli.CommandLine;

public final class Main {

    private Main() {}

    public static void main(String[] args) {
        System.exit(run(args));
    }

    public static int run(String... args) {
        return new CommandLine(new KitCommand()).execute(args);
    }
}
