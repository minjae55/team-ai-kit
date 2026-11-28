package kit.core;

import kit.adapter.ToolAdapter;

import java.io.IOException;
import java.io.PrintStream;
import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Set;
import java.util.TreeSet;

/**
 * 동기화 로직 (kit sync, pin, unpin, rollback, 자동 업데이트 훅이 함께 쓴다).
 * - 고정 버전(pinned)이 있으면 그 버전에 머문다
 * - kit 명령 자체가 바뀐 버전이면 kit.jar도 다시 빌드해 교체한다
 * - quiet 모드(세션 시작 훅): 바뀐 게 있을 때만 한 줄을 출력한다 (이 출력은 AI 세션 맥락에 들어간다)
 */
public final class SyncService {

    public static final class Options {
        public boolean dryRun;
        public boolean quiet;
        /** 이 버전으로 맞춘다 (null이면 고정 버전, 그것도 없으면 최신 태그) */
        public String version;
        /** 마지막 확인 후 이 시간이 안 지났으면 아무것도 안 한다 (예: 6h) */
        public String ifOlderThan;
    }

    private final KitEnv env;
    private final Installer.Out out;
    private final PrintStream stdout;

    public SyncService(KitEnv env, Installer.Out out, PrintStream stdout) {
        this.env = env;
        this.out = out;
        this.stdout = stdout;
    }

    public int run(Options o) throws IOException {
        LockFile lock = LockFile.readOrNull(env.lockFile());
        if (lock == null) {
            if (!o.quiet) {
                stdout.println("[오류] 설치 기록이 없습니다. 먼저 kit install 을 실행하세요");
            }
            return 1;
        }
        if (o.ifOlderThan != null && lock.lastCheck != null && !o.dryRun) {
            try {
                if (Instant.parse(lock.lastCheck).plus(parse(o.ifOlderThan)).isAfter(Instant.now())) {
                    return 0;
                }
            } catch (RuntimeException ignored) {
                // 기록이 이상하면 그냥 확인한다
            }
        }

        String beforeVersion = lock.version;
        String beforeCommit = lock.commit;
        Set<String> beforeSkills = new TreeSet<String>();
        for (LockFile.ToolState st : lock.tools.values()) {
            beforeSkills.addAll(st.skills);
        }
        Installer.Out quietOut = new Installer.Out() {
            @Override public void info(String msg) { }
            @Override public void warn(String msg) { }
        };
        if (!o.quiet) {
            stdout.println(o.dryRun ? "[미리보기] 동기화" : "동기화");
            if (lock.pinned != null && o.version == null) {
                stdout.println("  고정된 버전 " + lock.pinned + " 에 머뭅니다 (해제: kit unpin)");
            }
        }
        Installer inst = new Installer(env, o.quiet ? quietOut : out, o.dryRun);
        String target = o.version != null ? o.version : lock.pinned;
        String ver = inst.prepareRepo(target);
        String toRef = "untagged".equals(ver) ? (o.dryRun ? "origin/HEAD" : "HEAD") : ver;

        for (String id : lock.tools.keySet().toArray(new String[0])) {
            ToolAdapter t = ToolAdapter.byId(id);
            if (t != null) {
                inst.installTool(t, lock, "copy".equals(lock.mode));
            }
        }
        // 설치 뒤에 새로 깔린 AI 도구(예: Gemini CLI)에도 자동으로 설치한다. 설치 때 일부러 뺀 도구는 건너뛴다
        List<String> added = new ArrayList<String>();
        for (ToolAdapter t : ToolAdapter.all()) {
            if (t.detect(env) && !lock.tools.containsKey(t.id()) && !lock.skipTools.contains(t.id())) {
                inst.installTool(t, lock, "copy".equals(lock.mode));
                added.add(t.displayName());
            }
        }

        boolean cliChanged = ChangeSummary.cliChanged(env.repoDir(), beforeCommit, toRef);
        List<String> changes = new ArrayList<String>();
        if (!o.dryRun && WindowsShortcut.ensure(env)) {
            changes.add("시작 메뉴에 '팀 AI 키트' 바로가기를 만들었습니다");
        } else if (WindowsShortcut.lastError != null) {
            changes.add("시작 메뉴 바로가기를 만들지 못했습니다: " + WindowsShortcut.lastError);
        }
        for (String name : added) {
            changes.add("새로 찾은 AI 도구에도 설치: " + name);
        }
        for (String line : ChangeSummary.lines(env.repoDir(), beforeCommit, toRef,
                beforeSkills, ChangeSummary.skillsAt(env.repoDir(), toRef))) {
            if (!line.startsWith("kit 명령 자체가")) {
                changes.add(line);
            }
        }
        boolean versionChanged = !ver.equals(beforeVersion);

        if (o.dryRun) {
            print(o, beforeVersion, ver, changes, Collections.<String>emptyList(), cliChanged ? "kit 명령도 함께 업데이트됩니다" : null);
            stdout.println("미리보기 끝. 아무것도 바꾸지 않았습니다");
            return 0;
        }

        if (versionChanged) {
            lock.previous = beforeVersion;
        }
        lock.version = ver;
        lock.commit = Git.shortCommit(env.repoDir());
        lock.installedAt = Instant.now().toString();
        lock.lastCheck = Instant.now().toString();
        lock.write(env.lockFile());

        String selfNote = null;
        if (cliChanged && SelfUpdate.installedByScript(env)) {
            if (o.quiet) {
                SelfUpdate.background(env);
                selfNote = "kit 명령은 뒤에서 업데이트 중";
            } else {
                String err = SelfUpdate.now(env);
                selfNote = err != null ? "kit 명령 업데이트 실패: " + err + " → ~/.kit/repo/install.sh"
                        : java.nio.file.Files.exists(env.kitDir().resolve("bin").resolve("kit.jar.new"))
                        ? "kit 명령은 이 명령이 끝나고 몇 초 뒤 새 버전으로 바뀝니다 (Windows). 바뀐 뒤 한 번 더 업데이트하면 새 기능까지 적용됩니다"
                        : "kit 명령도 새 버전으로 업데이트했습니다";
            }
        } else if (cliChanged) {
            selfNote = "kit 명령 자체가 바뀌었습니다. 적용하려면: ~/.kit/repo/install.sh";
        }

        List<String> notes = versionChanged ? ChangeSummary.changelogSection(env.repoDir(), ver) : Collections.<String>emptyList();
        print(o, beforeVersion, ver, changes, notes, selfNote);
        return 0;
    }

    private void print(Options o, String before, String ver, List<String> changes, List<String> notes, String selfNote) {
        boolean same = ver.equals(before) && changes.isEmpty() && selfNote == null;
        if (o.quiet) {
            if (!same) {
                List<String> bits = new ArrayList<String>();
                for (String c : changes) {
                    if (!c.startsWith("변경 내역") && !c.startsWith("  ")) {
                        bits.add(c);
                    }
                }
                if (selfNote != null) {
                    bits.add(selfNote);
                }
                stdout.println("[팀 AI 키트] " + before + " → " + ver + " 업데이트됨" + (bits.isEmpty() ? "" : ": " + String.join(" · ", bits)));
            }
            return;
        }
        if (same) {
            stdout.println("이미 최신입니다: " + ver);
            return;
        }
        stdout.println();
        stdout.println("바뀐 내용 (" + before + " → " + ver + ")");
        for (String line : changes) {
            stdout.println("  " + line);
        }
        if (!notes.isEmpty()) {
            stdout.println("  CHANGELOG:");
            for (String line : notes) {
                stdout.println("    " + line);
            }
        }
        if (selfNote != null) {
            stdout.println("  " + selfNote);
        }
        if (!ver.equals(before)) {
            stdout.println("업데이트 완료: " + before + " → " + ver + ". 새 AI 세션부터 적용됩니다");
        }
    }

    /** 6h, 30m, 1d, 45s */
    static Duration parse(String s) {
        String t = s.trim().toLowerCase();
        long n = Long.parseLong(t.substring(0, t.length() - 1));
        switch (t.charAt(t.length() - 1)) {
            case 's': return Duration.ofSeconds(n);
            case 'm': return Duration.ofMinutes(n);
            case 'h': return Duration.ofHours(n);
            case 'd': return Duration.ofDays(n);
            default: throw new IllegalArgumentException("시간 형식: 30m, 6h, 1d");
        }
    }
}
