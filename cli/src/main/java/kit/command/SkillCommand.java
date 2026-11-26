package kit.command;

import kit.adapter.ToolAdapter;
import kit.core.Installer;
import kit.core.KitEnv;
import kit.core.LockFile;
import picocli.CommandLine.Command;
import picocli.CommandLine.Parameters;

import java.util.concurrent.Callable;

@Command(name = "skill", description = "팀 스킬을 내 AI에서 끄거나 켠다 (팀 레포와 다른 팀원에게는 영향 없음)",
        subcommands = {SkillCommand.Disable.class, SkillCommand.Enable.class})
public class SkillCommand implements Runnable {

    @picocli.CommandLine.ParentCommand
    kit.KitCommand parent;

    @Override
    public void run() {
        new picocli.CommandLine(this).usage(System.out);
    }

    abstract static class Toggle extends Common implements Callable<Integer> {

        @Parameters(index = "0", description = "스킬 이름")
        String name;

        abstract boolean enable();

        @Override
        public Integer call() throws Exception {
            KitEnv env = env();
            LockFile lock = LockFile.readOrNull(env.lockFile());
            if (lock == null) {
                error("설치 기록이 없습니다. 먼저 kit install 을 실행하세요");
                return 1;
            }
            Installer inst = new Installer(env, out(), false);
            if (!inst.repoSkills().contains(name)) {
                error("키트에 없는 스킬입니다: " + name);
                return 1;
            }
            if (enable()) {
                lock.disabled.remove(name);
            } else if (!lock.disabled.contains(name)) {
                lock.disabled.add(name);
            }
            for (String id : lock.tools.keySet().toArray(new String[0])) {
                ToolAdapter t = ToolAdapter.byId(id);
                if (t != null) {
                    inst.installTool(t, lock, "copy".equals(lock.mode));
                }
            }
            lock.write(env.lockFile());
            stdout.println((enable() ? "켰습니다: " : "껐습니다: ") + name + ". 새 AI 세션부터 적용됩니다");
            return 0;
        }
    }

    @Command(name = "disable", description = "스킬을 내 AI에서 끈다")
    static class Disable extends Toggle {
        @Override boolean enable() { return false; }
    }

    @Command(name = "enable", description = "꺼 둔 스킬을 다시 켠다")
    static class Enable extends Toggle {
        @Override boolean enable() { return true; }
    }
}
