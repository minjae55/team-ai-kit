package kit.command;

import kit.adapter.ToolAdapter;
import kit.core.Git;
import kit.core.Installer;
import kit.core.KitEnv;
import kit.core.LockFile;
import kit.core.ManagedBlock;
import picocli.CommandLine.Command;

import java.nio.file.Files;
import java.nio.file.LinkOption;
import java.nio.file.Path;
import java.util.List;
import java.util.Map;
import java.util.concurrent.Callable;

@Command(name = "doctor", description = "설치 상태를 점검한다 (아무것도 바꾸지 않음)")
public class DoctorCommand extends Common implements Callable<Integer> {

    @Override
    public Integer call() throws Exception {
        KitEnv env = env();
        stdout.println("점검: " + env.home());

        ok("Java " + System.getProperty("java.version"));
        if (Git.available()) {
            ok("git 사용 가능");
        } else {
            error("git이 없습니다");
        }

        boolean repo = Git.isRepo(env.repoDir());
        if (repo) {
            ok("키트 레포 " + env.repoDir());
        } else {
            warn("키트 레포가 없습니다 → kit install");
        }

        LockFile lock = LockFile.readOrNull(env.lockFile());
        if (lock == null) {
            warn("설치 기록이 없습니다 → kit install");
        } else {
            ok("설치 기록: 키트 " + lock.version + " (" + lock.commit + "), 방식 " + lock.mode);
            if (repo) {
                String latest = Git.latestTag(env.repoDir());
                if (latest != null && !latest.equals(lock.version)) {
                    warn("받아 둔 최신 태그는 " + latest + " 입니다 → kit sync");
                }
                List<String> repoSkills = new Installer(env, out(), true).repoSkills();
                checkTools(env, lock, repoSkills);
            }
        }

        for (ToolAdapter a : ToolAdapter.all()) {
            if (a.detect(env) && (lock == null || !lock.tools.containsKey(a.id()))) {
                warn(a.displayName() + " 이(가) 있지만 키트가 설치되지 않았습니다 → kit install --tools " + a.id());
            }
        }

        stdout.println("결과: 오류 " + errors + "건, 경고 " + warnings + "건");
        return errors > 0 ? 1 : 0;
    }

    private void checkTools(KitEnv env, LockFile lock, List<String> repoSkills) throws Exception {
        for (Map.Entry<String, LockFile.ToolState> e : lock.tools.entrySet()) {
            ToolAdapter a = ToolAdapter.byId(e.getKey());
            if (a == null) {
                warn("알 수 없는 도구 기록: " + e.getKey());
                continue;
            }
            LockFile.ToolState st = e.getValue();
            for (String skill : st.skills) {
                Path target = a.skillsDir(env).resolve(skill);
                if (!Files.exists(target, LinkOption.NOFOLLOW_LINKS)) {
                    error(a.displayName() + ": 스킬 " + skill + " 이(가) 없습니다 → kit sync");
                } else if (Files.isSymbolicLink(target) && !Files.exists(target)) {
                    error(a.displayName() + ": 스킬 " + skill + " 링크가 깨졌습니다 → kit sync");
                } else {
                    ok(a.displayName() + ": 스킬 " + skill);
                }
            }
            for (String skill : repoSkills) {
                if (!st.skills.contains(skill)) {
                    warn(a.displayName() + ": 키트에 있지만 설치되지 않은 스킬 " + skill + " (개인 스킬과 이름이 겹치면 그대로 둡니다)");
                }
            }
            Path cf = env.home().getFileSystem().getPath(st.contextFile);
            if (ManagedBlock.has(cf)) {
                ok(a.displayName() + ": 공통 지식 연결 " + cf);
            } else {
                error(a.displayName() + ": " + cf + " 에 관리 구역이 없습니다 → kit sync");
            }
        }
    }
}
