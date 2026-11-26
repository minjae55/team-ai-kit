package kit.command;

import kit.adapter.ToolAdapter;
import kit.core.Installer;
import kit.core.KitEnv;
import kit.core.LockFile;
import picocli.CommandLine.Command;
import picocli.CommandLine.Option;

import java.nio.file.Files;
import java.util.concurrent.Callable;

@Command(name = "uninstall", description = "키트가 설치한 것만 제거한다 (개인 스킬과 직접 쓴 지침은 그대로)")
public class UninstallCommand extends Common implements Callable<Integer> {

    @Option(names = "--purge", description = "받아 둔 키트 레포(~/.kit)까지 지운다")
    boolean purge;

    @Option(names = "--dry-run", description = "지울 내용만 보여 주고 아무것도 바꾸지 않는다")
    boolean dryRun;

    @Override
    public Integer call() throws Exception {
        KitEnv env = env();
        LockFile lock = LockFile.readOrNull(env.lockFile());
        if (lock == null) {
            stdout.println("설치 기록이 없어 지울 것이 없습니다");
            return 0;
        }
        stdout.println(dryRun ? "[미리보기] 제거" : "제거");
        Installer inst = new Installer(env, out(), dryRun);
        for (String id : lock.tools.keySet()) {
            inst.uninstallTool(id, ToolAdapter.byId(id), lock);
        }
        if (dryRun) {
            stdout.println("미리보기 끝. 아무것도 바꾸지 않았습니다");
            return 0;
        }
        Files.deleteIfExists(env.lockFile());
        if (purge) {
            stdout.println("  키트 폴더 삭제 " + env.kitDir());
            Installer.deleteTree(env.kitDir());
        }
        stdout.println("완료");
        return 0;
    }
}
