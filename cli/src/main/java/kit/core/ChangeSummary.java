package kit.core;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Collection;
import java.util.List;
import java.util.Set;
import java.util.TreeSet;

/** 두 버전 사이에 무엇이 바뀌었는지 사람이 읽기 좋게 정리한다 (kit sync 출력용). */
public final class ChangeSummary {

    private static final int MAX_COMMITS = 15;

    private ChangeSummary() {}

    /**
     * @param repo       키트 레포
     * @param fromCommit 이전에 설치한 커밋 (lock 파일 기록)
     * @param toRef      새로 맞춘 버전 (태그 이름 또는 HEAD)
     * @param before     이전에 설치돼 있던 스킬
     * @param after      새 버전의 스킬
     */
    public static List<String> lines(Path repo, String fromCommit, String toRef,
                                     Collection<String> before, Collection<String> after) throws IOException {
        List<String> out = new ArrayList<String>();
        Set<String> added = new TreeSet<String>(after);
        added.removeAll(before);
        Set<String> removed = new TreeSet<String>(before);
        removed.removeAll(after);
        if (!added.isEmpty()) {
            out.add("새 스킬: " + String.join(", ", added));
        }
        if (!removed.isEmpty()) {
            out.add("빠진 스킬: " + String.join(", ", removed));
        }

        if (fromCommit == null || !Git.run(repo, "cat-file", "-e", fromCommit + "^{commit}").ok()) {
            return out;
        }
        String range = fromCommit + ".." + toRef;

        Set<String> changedSkills = new TreeSet<String>();
        boolean contextChanged = false;
        Git.Result files = Git.run(repo, "diff", "--name-only", range, "--", "kit");
        if (files.ok() && !files.out.isEmpty()) {
            for (String f : files.out.split("\\R")) {
                if (f.startsWith("kit/skills/")) {
                    String[] parts = f.split("/");
                    if (parts.length > 2 && !added.contains(parts[2]) && !removed.contains(parts[2])) {
                        changedSkills.add(parts[2]);
                    }
                } else if (f.startsWith("kit/context/")) {
                    contextChanged = true;
                }
            }
        }
        if (!changedSkills.isEmpty()) {
            out.add("바뀐 스킬: " + String.join(", ", changedSkills));
        }
        if (contextChanged) {
            out.add("팀 공통 지식(kit/context)이 바뀌었습니다");
        }
        Git.Result cli = Git.run(repo, "diff", "--name-only", range, "--", "cli/src", "cli/build.gradle", "cli/kit", "install.sh");
        if (cli.ok() && !cli.out.isEmpty()) {
            out.add("kit 명령 자체가 업데이트되었습니다. 적용하려면: ~/.kit/repo/install.sh");
        }

        Git.Result log = Git.run(repo, "log", "--no-merges", "--format=%s", "-n", String.valueOf(MAX_COMMITS + 1), range);
        if (log.ok() && !log.out.isEmpty()) {
            String[] subjects = log.out.split("\\R");
            out.add("변경 내역:");
            for (int i = 0; i < Math.min(subjects.length, MAX_COMMITS); i++) {
                out.add("  - " + subjects[i]);
            }
            if (subjects.length > MAX_COMMITS) {
                out.add("  - … (더 있음, CHANGELOG.md 참고)");
            }
        }
        return out;
    }

    /** 두 버전 사이에 kit 명령 자체(cli 코드, 실행 스크립트, install.sh)가 바뀌었는지 */
    public static boolean cliChanged(Path repo, String fromCommit, String toRef) throws IOException {
        if (fromCommit == null || !Git.run(repo, "cat-file", "-e", fromCommit + "^{commit}").ok()) {
            return false;
        }
        Git.Result r = Git.run(repo, "diff", "--name-only", fromCommit + ".." + toRef, "--",
                "cli/src", "cli/build.gradle", "cli/kit", "install.sh");
        return r.ok() && !r.out.isEmpty();
    }

    /** 특정 버전(ref)에 들어 있는 스킬 이름. 체크아웃하지 않고 git에서 바로 읽는다. */
    public static Set<String> skillsAt(Path repo, String ref) throws IOException {
        Set<String> names = new TreeSet<String>();
        Git.Result r = Git.run(repo, "ls-tree", "--name-only", "-r", ref, "--", "kit/skills");
        if (r.ok() && !r.out.isEmpty()) {
            for (String f : r.out.split("\\R")) {
                String[] parts = f.split("/");
                if (parts.length == 4 && "SKILL.md".equals(parts[3])) {
                    names.add(parts[2]);
                }
            }
        }
        return names;
    }

    /** CHANGELOG.md에서 해당 버전 절(## v0.1.1 …)의 본문. 없으면 빈 목록. */
    public static List<String> changelogSection(Path repo, String version) throws IOException {
        List<String> out = new ArrayList<String>();
        Path file = repo.resolve("CHANGELOG.md");
        if (version == null || !Files.exists(file)) {
            return out;
        }
        boolean in = false;
        for (String line : new String(Files.readAllBytes(file), StandardCharsets.UTF_8).split("\\R")) {
            if (line.startsWith("## ")) {
                if (in) {
                    break;
                }
                in = line.substring(3).trim().startsWith(version);
                continue;
            }
            if (in && !line.trim().isEmpty()) {
                out.add(line);
            }
        }
        return out;
    }
}
