package kit.command;

import kit.core.KitEnv;
import kit.core.ClaudeHooks;
import kit.core.Installer;
import kit.core.LockFile;
import kit.core.Usage;
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
        printUsage(env, lock);
        return 0;
    }

    /** 스킬별 최근 30일 사용 횟수·비율과 마지막 사용일. 안 쓰는 스킬을 찾아 정리하기 위한 것 */
    private void printUsage(KitEnv env, LockFile lock) throws java.io.IOException {
        Usage.Summary sum = new Usage(env).summary(30);
        boolean tracking = new ClaudeHooks(env).enabled() || sum.firstRecord != null;
        stdout.println();
        if (!tracking) {
            stdout.println("스킬 사용 기록: 꺼져 있음 (kit auto on 으로 켜면 스킬별 사용 횟수와 마지막 사용일을 보여 줍니다)");
            return;
        }
        stdout.println("스킬 사용 (최근 30일 " + sum.total + "회)");
        java.util.List<String> names = new Installer(env, null, true).repoSkills();
        java.time.format.DateTimeFormatter day = java.time.format.DateTimeFormatter.ofPattern("yyyy-MM-dd")
                .withZone(java.time.ZoneId.systemDefault());
        int unused = 0;
        for (String name : names) {
            Usage.Stat st = sum.bySkill.get(name);
            int c = st == null ? 0 : st.count;
            String pct = sum.total == 0 ? "0%" : Math.round(c * 100.0 / sum.total) + "%";
            String last = st == null || st.lastUsed == null ? "쓴 적 없음" : "마지막 " + day.format(st.lastUsed);
            String off = lock.disabled.contains(name) ? " (꺼 둠)" : "";
            String mark = c == 0 && sum.coversDays(30) ? "  ← 정리 후보" : "";
            if (c == 0 && sum.coversDays(30)) {
                unused++;
            }
            stdout.println(String.format("  %-24s %3d회 %4s  %s%s%s", name, c, pct, last, off, mark));
        }
        if (unused > 0) {
            stdout.println("30일 동안 안 쓴 스킬 " + unused + "개: 필요 없으면 kit skill disable <이름> 으로 꺼 두세요");
        } else if (!sum.coversDays(30)) {
            stdout.println("기록한 지 30일이 안 돼서 정리 후보는 아직 판단하지 않습니다");
        }
    }
}
