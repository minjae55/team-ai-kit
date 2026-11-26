package kit.command;

import kit.core.KitEnv;
import kit.core.LockFile;
import picocli.CommandLine.Command;

import java.util.Map;
import java.util.concurrent.Callable;

@Command(name = "list", description = "설치된 키트 버전과 스킬을 보여 준다")
public class ListCommand extends Common implements Callable<Integer> {

    @Override
    public Integer call() throws Exception {
        KitEnv env = env();
        LockFile lock = LockFile.readOrNull(env.lockFile());
        if (lock == null) {
            stdout.println("설치된 키트가 없습니다. kit install 로 설치하세요");
            return 0;
        }
        stdout.println("키트 " + lock.version + " (" + lock.commit + "), 방식 " + lock.mode + ", 설치 " + lock.installedAt);
        for (Map.Entry<String, LockFile.ToolState> e : lock.tools.entrySet()) {
            stdout.println("  " + e.getKey() + ": 스킬 " + e.getValue().skills + ", 지침 " + e.getValue().contextFile);
        }
        return 0;
    }
}
