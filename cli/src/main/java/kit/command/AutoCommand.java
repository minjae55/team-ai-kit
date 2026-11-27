package kit.command;

import kit.core.ClaudeHooks;
import kit.core.KitEnv;
import picocli.CommandLine.Command;

import java.util.concurrent.Callable;

@Command(name = "auto", description = "자동 업데이트와 스킬 사용 기록을 켜거나 끈다 (Claude Code 훅)",
        subcommands = {AutoCommand.On.class, AutoCommand.Off.class, AutoCommand.Status.class})
public class AutoCommand implements Runnable {

    @picocli.CommandLine.ParentCommand
    kit.KitCommand parent;

    @Override
    public void run() {
        new picocli.CommandLine(this).usage(System.out);
    }

    @Command(name = "on", description = "켠다: 세션을 열 때 6시간마다 자동 업데이트, 스킬을 쓰면 사용 기록")
    static class On extends Common implements Callable<Integer> {
        @Override
        public Integer call() throws Exception {
            KitEnv env = env();
            new ClaudeHooks(env).enable();
            stdout.println("자동 업데이트와 사용 기록을 켰습니다 (~/.claude/settings.json 에 키트 훅 추가, 처음 바꿀 때 settings.json.kit-backup 으로 백업)");
            stdout.println("  - Claude 세션을 열 때 6시간에 한 번 kit sync (바뀐 게 있으면 세션에 한 줄 알림)");
            stdout.println("  - 스킬을 쓰면 ~/.kit/usage.jsonl 에 기록 (요청 내용은 남기지 않음)");
            stdout.println("끄려면: kit auto off");
            return 0;
        }
    }

    @Command(name = "off", description = "끈다: 키트 훅만 지운다 (다른 설정과 직접 만든 훅은 그대로)")
    static class Off extends Common implements Callable<Integer> {
        @Override
        public Integer call() throws Exception {
            new ClaudeHooks(env()).disable();
            stdout.println("자동 업데이트와 사용 기록을 껐습니다");
            return 0;
        }
    }

    @Command(name = "status", description = "켜져 있는지 보여 준다")
    static class Status extends Common implements Callable<Integer> {
        @Override
        public Integer call() throws Exception {
            stdout.println(new ClaudeHooks(env()).enabled() ? "켜짐" : "꺼짐");
            return 0;
        }
    }
}
