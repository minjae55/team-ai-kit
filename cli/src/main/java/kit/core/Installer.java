package kit.core;

import kit.adapter.ToolAdapter;

import java.io.IOException;
import java.nio.file.DirectoryStream;
import java.nio.file.Files;
import java.nio.file.LinkOption;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.util.ArrayList;
import java.util.Collections;
import java.util.Comparator;
import java.util.HashSet;
import java.util.List;
import java.util.Set;
import java.util.stream.Stream;

/**
 * 설치·제거 로직. 원칙: lock 파일에 적힌 것(키트가 만든 것)만 만들고 고치고 지운다.
 * 같은 이름의 개인 스킬이 이미 있으면 덮어쓰지 않고 경고만 한다.
 */
public final class Installer {

    public interface Out {
        void info(String msg);
        void warn(String msg);
    }

    private final KitEnv env;
    private final Out out;
    private final boolean dryRun;

    public Installer(KitEnv env, Out out, boolean dryRun) {
        this.env = env;
        this.out = out;
        this.dryRun = dryRun;
    }

    /** 키트 레포를 받거나 갱신하고, 지정 버전(없으면 최신 v* 태그)으로 맞춘다. 맞춘 버전 이름을 돌려준다. */
    public String prepareRepo(String version) throws IOException {
        Path repo = env.repoDir();
        if (!Git.isRepo(repo)) {
            out.info("키트 받기: " + env.repoUrl() + " → " + repo);
            if (dryRun) {
                return version != null ? version : "(받은 뒤 최신 태그)";
            }
            Files.createDirectories(env.kitDir());
            Git.runOrThrow(null, "clone", "--quiet", env.repoUrl(), repo.toString());
        } else {
            Git.Result f = Git.run(repo, "fetch", "--tags", "--quiet", "origin");
            if (!f.ok()) {
                out.warn("키트 갱신 실패, 받아 둔 버전으로 진행합니다: " + f.out);
            }
        }
        String target = version != null ? version : Git.latestTag(repo);
        if (dryRun) {
            return target != null ? target : "untagged";
        }
        if (target == null) {
            out.warn("버전 태그가 없어 기본 브랜치의 최신 커밋을 씁니다");
            Git.runOrThrow(repo, "checkout", "--quiet", "--detach", "origin/HEAD");
            return "untagged";
        }
        Git.runOrThrow(repo, "checkout", "--quiet", target);
        return target;
    }

    /** 키트 레포에 있는 스킬 이름 (SKILL.md가 있는 폴더) */
    public List<String> repoSkills() throws IOException {
        List<String> names = new ArrayList<String>();
        Path src = env.skillsSource();
        if (!Files.isDirectory(src)) {
            return names;
        }
        try (DirectoryStream<Path> ds = Files.newDirectoryStream(src)) {
            for (Path p : ds) {
                if (Files.isRegularFile(p.resolve("SKILL.md"))) {
                    names.add(p.getFileName().toString());
                }
            }
        }
        Collections.sort(names);
        return names;
    }

    public void installTool(ToolAdapter tool, LockFile lock, boolean copy) throws IOException {
        LockFile.ToolState st = lock.tool(tool.id());
        Set<String> managed = new HashSet<String>(st.skills);
        List<String> want = repoSkills();
        want.removeAll(lock.disabled);
        List<String> nowManaged = new ArrayList<String>();
        Path dir = tool.skillsDir(env);
        String mode = copy ? "copy" : "link";

        for (String name : want) {
            Path target = dir.resolve(name);
            Path src = env.skillsSource().resolve(name);
            boolean exists = Files.exists(target, LinkOption.NOFOLLOW_LINKS);
            if (exists && !managed.contains(name)) {
                out.warn(tool.displayName() + ": " + target + " 이(가) 이미 있어 건드리지 않습니다 (개인 스킬 보호)");
                continue;
            }
            if (exists) {
                if (!copy && isLinkTo(target, src)) {
                    nowManaged.add(name);
                    continue;
                }
                if (!deleteManaged(target, lock.mode)) {
                    continue;
                }
            }
            out.info(tool.displayName() + ": 스킬 " + (copy ? "복사" : "링크") + " " + name + " → " + target);
            if (!dryRun) {
                Files.createDirectories(dir);
                if (copy) {
                    copyTree(src, target);
                } else {
                    Files.createSymbolicLink(target, src);
                }
            }
            nowManaged.add(name);
        }

        for (String name : managed) {
            if (!want.contains(name)) {
                Path target = dir.resolve(name);
                if (Files.exists(target, LinkOption.NOFOLLOW_LINKS)) {
                    out.info(tool.displayName() + ": " + (lock.disabled.contains(name) ? "꺼 둔 스킬 제거 " : "키트에서 빠진 스킬 제거 ") + name);
                    deleteManaged(target, lock.mode);
                }
            }
        }
        st.skills = nowManaged;

        Path cf = tool.contextFile(env);
        boolean existed = Files.exists(cf);
        if (dryRun) {
            out.info(tool.displayName() + ": 공통 지식 연결 예정 → " + cf + " (관리 구역)");
        } else {
            boolean changed = ManagedBlock.upsert(cf, tool.contextBody(env));
            if (changed) {
                out.info(tool.displayName() + ": 공통 지식 연결 → " + cf + " (관리 구역)");
            }
            if (!existed) {
                st.contextFileCreated = true;
            }
        }
        st.contextFile = cf.toString();
        lock.mode = mode;
    }

    public void uninstallTool(String toolId, ToolAdapter tool, LockFile lock) throws IOException {
        LockFile.ToolState st = lock.tools.get(toolId);
        if (st == null) {
            return;
        }
        String name = tool != null ? tool.displayName() : toolId;
        if (tool != null) {
            for (String skill : st.skills) {
                Path target = tool.skillsDir(env).resolve(skill);
                if (Files.exists(target, LinkOption.NOFOLLOW_LINKS)) {
                    out.info(name + ": 스킬 제거 " + target);
                    deleteManaged(target, lock.mode);
                }
            }
        }
        if (st.contextFile != null) {
            Path cf = env.home().getFileSystem().getPath(st.contextFile);
            if (ManagedBlock.has(cf)) {
                out.info(name + ": 공통 지식 연결 해제 " + cf);
                if (!dryRun) {
                    ManagedBlock.remove(cf, st.contextFileCreated);
                }
            }
        }
    }

    /** 키트가 만든 것만 지운다. 링크 모드인데 링크가 아니면(누군가 바꿈) 지우지 않는다. */
    private boolean deleteManaged(Path target, String mode) throws IOException {
        if (Files.isSymbolicLink(target)) {
            if (!dryRun) {
                Files.delete(target);
            }
            return true;
        }
        if ("copy".equals(mode) && Files.isDirectory(target)) {
            if (!dryRun) {
                deleteTree(target);
            }
            return true;
        }
        out.warn(target + " 은(는) 키트가 만든 링크가 아니라서 지우지 않습니다");
        return false;
    }

    private static boolean isLinkTo(Path link, Path src) throws IOException {
        if (!Files.isSymbolicLink(link)) {
            return false;
        }
        Path dest = Files.readSymbolicLink(link);
        return dest.toAbsolutePath().normalize().equals(src.toAbsolutePath().normalize());
    }

    public static void deleteTree(Path root) throws IOException {
        if (!Files.exists(root, LinkOption.NOFOLLOW_LINKS)) {
            return;
        }
        List<Path> paths;
        try (Stream<Path> s = Files.walk(root)) {
            paths = new ArrayList<Path>();
            s.sorted(Comparator.reverseOrder()).forEach(paths::add);
        }
        for (Path p : paths) {
            Files.delete(p);
        }
    }

    private static void copyTree(Path src, Path dst) throws IOException {
        List<Path> paths = new ArrayList<Path>();
        try (Stream<Path> s = Files.walk(src)) {
            s.forEach(paths::add);
        }
        for (Path p : paths) {
            Path to = dst.resolve(src.relativize(p).toString());
            if (Files.isDirectory(p)) {
                Files.createDirectories(to);
            } else {
                Files.copy(p, to, StandardCopyOption.REPLACE_EXISTING, StandardCopyOption.COPY_ATTRIBUTES);
            }
        }
    }
}
