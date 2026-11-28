package kit.command;

import kit.adapter.ToolAdapter;
import kit.core.Git;
import kit.core.Installer;
import kit.core.KitEnv;
import kit.core.LockFile;
import picocli.CommandLine.Command;
import picocli.CommandLine.Option;

import java.util.List;
import java.util.concurrent.Callable;

@Command(name = "install", description = "팀 스킬과 공통 지식을 이 PC의 AI 도구에 설치한다")
public class InstallCommand extends Common implements Callable<Integer> {

    @Option(names = "--tools", description = "설치할 도구 (claude,codex,gemini,antigravity). 생략하면 감지된 도구 전부")
    String tools;

    @Option(names = "--version", description = "설치할 키트 버전 태그 (예: v0.1.0). 생략하면 최신 태그")
    String version;

    @Option(names = "--copy", description = "링크 대신 복사로 설치")
    boolean copy;

    @Option(names = "--dry-run", description = "바꿀 내용만 보여 주고 아무것도 바꾸지 않는다")
    boolean dryRun;

    @Override
    public Integer call() throws Exception {
        KitEnv env = env();
        if (!Git.available()) {
            error("git이 없습니다. git을 설치한 뒤 다시 실행하세요");
            return 1;
        }
        List<ToolAdapter> picked = selectTools(env, tools);
        if (errors > 0) {
            return 1;
        }
        if (picked.isEmpty()) {
            error("감지된 AI 도구가 없습니다 (Claude Code, Codex, Gemini CLI, Antigravity). --tools 로 지정하세요");
            return 1;
        }
        if (!copy && System.getProperty("os.name", "").toLowerCase().contains("win")) {
            copy = true;   // Windows는 링크(심볼릭 링크)에 관리자 권한·개발자 모드가 필요해서 기본을 복사로
            stdout.println("Windows: 스킬을 링크 대신 복사로 설치합니다 (업데이트 때마다 다시 복사)");
        }
        stdout.println(dryRun ? "[미리보기] 설치" : "설치");
        Installer inst = new Installer(env, out(), dryRun);
        String ver = inst.prepareRepo(version);

        LockFile lock = LockFile.readOrNull(env.lockFile());
        if (lock == null) {
            lock = new LockFile();
        }
        for (ToolAdapter t : picked) {
            inst.installTool(t, lock, copy);
            lock.skipTools.remove(t.id());
        }
        if (tools != null && !tools.trim().isEmpty()) {
            for (ToolAdapter a : ToolAdapter.all()) {   // 감지됐는데 일부러 뺀 도구는 sync 가 자동으로 넣지 않게 기록
                if (a.detect(env) && !picked.contains(a) && !lock.tools.containsKey(a.id()) && !lock.skipTools.contains(a.id())) {
                    lock.skipTools.add(a.id());
                }
            }
        }
        if (dryRun) {
            stdout.println("미리보기 끝. 아무것도 바꾸지 않았습니다");
            return 0;
        }
        if (kit.core.WindowsShortcut.ensure(env)) {
            stdout.println("  시작 메뉴에 '팀 AI 키트' 바로가기를 만들었습니다 (누르면 화면이 열림)");
        } else if (kit.core.WindowsShortcut.lastError != null) {
            stdout.println("  [경고] 시작 메뉴 바로가기를 만들지 못했습니다: " + kit.core.WindowsShortcut.lastError);
        }
        lock.version = ver;
        lock.commit = Git.shortCommit(env.repoDir());
        lock.installedAt = now();
        lock.write(env.lockFile());
        stdout.println("완료: 키트 " + ver + " (" + lock.commit + "), 도구 " + picked.size() + "개"
                + (warnings > 0 ? ", 경고 " + warnings + "건" : ""));
        stdout.println("새 AI 세션부터 적용됩니다");
        return 0;
    }
}
