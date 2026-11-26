package kit;

import kit.core.Git;
import kit.core.LockFile;
import kit.core.ManagedBlock;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.PrintStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Arrays;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/** 가짜 키트 레포와 가짜 홈 폴더로 실제 명령을 실행해 본다. 실제 홈 폴더는 건드리지 않는다. */
class KitCliTest {

    @TempDir Path tmp;
    Path home;
    Path origin;
    ByteArrayOutputStream captured;
    PrintStream realOut;

    @BeforeEach
    void setUp() throws IOException {
        home = Files.createDirectories(tmp.resolve("home"));
        Files.createDirectories(home.resolve(".claude"));
        Files.createDirectories(home.resolve(".codex"));
        origin = Files.createDirectories(tmp.resolve("origin"));
        git(origin, "init", "--quiet", "-b", "main");
        git(origin, "config", "user.email", "test@example.com");
        git(origin, "config", "user.name", "test");
        write(origin.resolve("kit/skills/incident-analysis/SKILL.md"), "---\nname: incident-analysis\ndescription: test\n---\n");
        write(origin.resolve("kit/context/AGENTS.md"), "# 팀 공통 원칙\n- 운영에는 읽기만 한다\n");
        commitAndTag("v0.1.0");

        realOut = System.out;
        captured = new ByteArrayOutputStream();
        System.setOut(new PrintStream(captured, true, "UTF-8"));
    }

    @AfterEach
    void tearDown() {
        System.setOut(realOut);
    }

    int kit(String... args) {
        String[] all = new String[args.length + 4];
        all[0] = "--home";
        all[1] = home.toString();
        all[2] = "--repo";
        all[3] = origin.toString();
        System.arraycopy(args, 0, all, 4, args.length);
        return Main.run(all);
    }

    String output() {
        return new String(captured.toByteArray(), StandardCharsets.UTF_8);
    }

    @Test
    void install_links_skills_and_connects_context_for_both_tools() throws IOException {
        assertEquals(0, kit("install"));

        Path claudeSkill = home.resolve(".claude/skills/incident-analysis");
        Path codexSkill = home.resolve(".agents/skills/incident-analysis");
        assertTrue(Files.isSymbolicLink(claudeSkill));
        assertTrue(Files.isSymbolicLink(codexSkill));
        assertTrue(Files.exists(claudeSkill.resolve("SKILL.md")));

        String claudeMd = ManagedBlock.read(home.resolve(".claude/CLAUDE.md"));
        assertTrue(claudeMd.contains(ManagedBlock.START));
        assertTrue(claudeMd.contains("@" + home.resolve(".kit/repo/kit/context/AGENTS.md")));

        String codexMd = ManagedBlock.read(home.resolve(".codex/AGENTS.md"));
        assertTrue(codexMd.contains("운영에는 읽기만 한다"), "Codex에는 내용을 복사");

        LockFile lock = LockFile.readOrNull(home.resolve(".kit/kit.lock.json"));
        assertEquals("v0.1.0", lock.version);
        assertEquals(Arrays.asList("incident-analysis"), lock.tool("claude").skills);
        assertTrue(lock.tool("claude").contextFileCreated);
    }

    @Test
    void personal_skill_with_same_name_is_never_touched() throws IOException {
        Path personal = home.resolve(".claude/skills/incident-analysis/SKILL.md");
        write(personal, "내 개인 스킬");

        assertEquals(0, kit("install"));

        assertFalse(Files.isSymbolicLink(personal.getParent()));
        assertEquals("내 개인 스킬", ManagedBlock.read(personal));
        assertTrue(output().contains("개인 스킬 보호"));
        LockFile lock = LockFile.readOrNull(home.resolve(".kit/kit.lock.json"));
        assertTrue(lock.tool("claude").skills.isEmpty(), "키트가 만들지 않은 것은 기록하지 않음");

        assertEquals(0, kit("uninstall"));
        assertEquals("내 개인 스킬", ManagedBlock.read(personal), "제거할 때도 그대로");
    }

    @Test
    void user_written_instructions_are_kept_and_backed_up() throws IOException {
        Path claudeMd = home.resolve(".claude/CLAUDE.md");
        write(claudeMd, "# 내가 쓴 지침\n- 답은 짧게\n");

        assertEquals(0, kit("install"));
        assertEquals(0, kit("install"));

        String text = ManagedBlock.read(claudeMd);
        assertTrue(text.startsWith("# 내가 쓴 지침\n- 답은 짧게\n"));
        assertEquals(text.indexOf(ManagedBlock.START_PREFIX), text.lastIndexOf(ManagedBlock.START_PREFIX), "두 번 설치해도 구역은 하나");
        assertEquals("# 내가 쓴 지침\n- 답은 짧게\n", ManagedBlock.read(claudeMd.resolveSibling("CLAUDE.md.kit-backup")));

        assertEquals(0, kit("uninstall"));
        assertEquals("# 내가 쓴 지침\n- 답은 짧게\n", ManagedBlock.read(claudeMd).replaceAll("\\n+$", "\n"));
        assertFalse(Files.exists(home.resolve(".codex/AGENTS.md")), "키트가 새로 만든 파일은 비면 지움");
        assertFalse(Files.exists(home.resolve(".claude/skills/incident-analysis")));
    }

    @Test
    void sync_picks_up_new_tag_and_new_skill() throws IOException {
        assertEquals(0, kit("install"));

        write(origin.resolve("kit/skills/query-analysis/SKILL.md"), "---\nname: query-analysis\ndescription: test\n---\n");
        write(origin.resolve("kit/context/AGENTS.md"), "# 팀 공통 원칙\n- 근거를 붙인다\n");
        commitAndTag("v0.2.0");

        assertEquals(0, kit("sync"));

        assertTrue(Files.isSymbolicLink(home.resolve(".claude/skills/query-analysis")));
        assertTrue(ManagedBlock.read(home.resolve(".codex/AGENTS.md")).contains("근거를 붙인다"), "Codex 복사본도 갱신");
        assertEquals("v0.2.0", LockFile.readOrNull(home.resolve(".kit/kit.lock.json")).version);
        assertTrue(output().contains("v0.1.0 → v0.2.0"));
    }

    @Test
    void dry_run_changes_nothing() {
        assertEquals(0, kit("install", "--dry-run"));
        assertFalse(Files.exists(home.resolve(".kit")));
        assertFalse(Files.exists(home.resolve(".claude/skills")));
        assertFalse(Files.exists(home.resolve(".claude/CLAUDE.md")));
    }

    @Test
    void doctor_reports_broken_link() throws IOException {
        assertEquals(0, kit("install"));
        assertEquals(0, kit("doctor"));

        Files.delete(home.resolve(".claude/skills/incident-analysis"));
        assertEquals(1, kit("doctor"));
        assertTrue(output().contains("스킬 incident-analysis 이(가) 없습니다"));
    }

    @Test
    void only_selected_tool_is_installed() {
        assertEquals(0, kit("install", "--tools", "claude"));
        assertTrue(Files.exists(home.resolve(".claude/skills/incident-analysis")));
        assertFalse(Files.exists(home.resolve(".agents/skills")));
    }

    private void commitAndTag(String tag) throws IOException {
        git(origin, "add", "-A");
        git(origin, "commit", "--quiet", "-m", tag);
        git(origin, "tag", tag);
    }

    private static void git(Path dir, String... args) throws IOException {
        Git.runOrThrow(dir, args);
    }

    private static void write(Path file, String text) throws IOException {
        Files.createDirectories(file.getParent());
        Files.write(file, text.getBytes(StandardCharsets.UTF_8));
    }
}
