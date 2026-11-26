package kit.command;

import kit.adapter.ToolAdapter;
import kit.core.ChangeSummary;
import kit.core.Git;
import kit.core.Installer;
import kit.core.KitEnv;
import kit.core.LockFile;
import picocli.CommandLine.Command;
import picocli.CommandLine.Option;

import java.util.List;
import java.util.Set;
import java.util.TreeSet;
import java.util.concurrent.Callable;

@Command(name = "sync", description = "설치된 도구를 키트 최신 버전으로 맞추고, 무엇이 바뀌었는지 보여 준다")
public class SyncCommand extends Common implements Callable<Integer> {

    @Option(names = "--dry-run", description = "바꿀 내용만 보여 주고 아무것도 바꾸지 않는다")
    boolean dryRun;

    @Override
    public Integer call() throws Exception {
        KitEnv env = env();
        LockFile lock = LockFile.readOrNull(env.lockFile());
        if (lock == null) {
            error("설치 기록이 없습니다. 먼저 kit install 을 실행하세요");
            return 1;
        }
        String beforeVersion = lock.version;
        String beforeCommit = lock.commit;
        Set<String> beforeSkills = new TreeSet<String>();
        for (LockFile.ToolState st : lock.tools.values()) {
            beforeSkills.addAll(st.skills);
        }

        stdout.println(dryRun ? "[미리보기] 동기화" : "동기화");
        Installer inst = new Installer(env, out(), dryRun);
        String ver = inst.prepareRepo(null);
        String toRef = "untagged".equals(ver) ? (dryRun ? "origin/HEAD" : "HEAD") : ver;

        for (String id : lock.tools.keySet().toArray(new String[0])) {
            ToolAdapter t = ToolAdapter.byId(id);
            if (t == null) {
                warn("알 수 없는 도구 기록: " + id);
                continue;
            }
            inst.installTool(t, lock, "copy".equals(lock.mode));
        }

        List<String> changes = ChangeSummary.lines(env.repoDir(), beforeCommit, toRef,
                beforeSkills, ChangeSummary.skillsAt(env.repoDir(), toRef));
        List<String> notes = ver.equals(beforeVersion)
                ? java.util.Collections.<String>emptyList()
                : ChangeSummary.changelogSection(env.repoDir(), ver);

        if (changes.isEmpty() && ver.equals(beforeVersion)) {
            stdout.println("이미 최신입니다: " + ver);
        } else {
            stdout.println();
            stdout.println("바뀐 내용 (" + beforeVersion + " → " + ver + ")");
            for (String line : changes) {
                stdout.println("  " + line);
            }
            if (!notes.isEmpty()) {
                stdout.println("  CHANGELOG:");
                for (String line : notes) {
                    stdout.println("    " + line);
                }
            }
        }

        if (dryRun) {
            stdout.println("미리보기 끝. 아무것도 바꾸지 않았습니다");
            return 0;
        }
        lock.version = ver;
        lock.commit = Git.shortCommit(env.repoDir());
        lock.installedAt = now();
        lock.write(env.lockFile());
        if (!ver.equals(beforeVersion)) {
            stdout.println("업데이트 완료: " + beforeVersion + " → " + ver + ". 새 AI 세션부터 적용됩니다");
        }
        return 0;
    }
}
