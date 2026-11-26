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
        write(origin.resolve("kit/skills/incident-analysis/SKILL.md"), "---\nname: incident-analysis\ndescription: test v2\n---\n");
        write(origin.resolve("kit/context/AGENTS.md"), "# 팀 공통 원칙\n- 근거를 붙인다\n");
        write(origin.resolve("CHANGELOG.md"), "# 변경 기록\n\n## v0.2.0 (2026-11-27)\n- 새 스킬: query-analysis\n\n## v0.1.0\n- 첫 버전\n");
        commitAndTag("v0.2.0");

        assertEquals(0, kit("sync"));

        assertTrue(Files.isSymbolicLink(home.resolve(".claude/skills/query-analysis")));
        assertTrue(ManagedBlock.read(home.resolve(".codex/AGENTS.md")).contains("근거를 붙인다"), "Codex 복사본도 갱신");
        assertEquals("v0.2.0", LockFile.readOrNull(home.resolve(".kit/kit.lock.json")).version);
        String out = output();
        assertTrue(out.contains("바뀐 내용 (v0.1.0 → v0.2.0)"));
        assertTrue(out.contains("새 스킬: query-analysis"));
        assertTrue(out.contains("바뀐 스킬: incident-analysis"));
        assertTrue(out.contains("팀 공통 지식(kit/context)이 바뀌었습니다"));
        assertTrue(out.contains("- v0.2.0"), "변경 내역에 커밋 제목");
        assertTrue(out.contains("- 새 스킬: query-analysis"), "CHANGELOG 절");
        assertFalse(out.contains("첫 버전"), "다른 버전의 CHANGELOG는 안 보여 줌");
        assertFalse(out.contains("kit 명령 자체가 업데이트"), "cli를 안 바꿨으면 안내하지 않음");
    }

    @Test
    void sync_tells_when_kit_command_itself_changed() throws IOException {
        assertEquals(0, kit("install"));
        write(origin.resolve("install.sh"), "#!/usr/bin/env bash\necho new\n");
        commitAndTag("v0.1.1");

        assertEquals(0, kit("sync"));
        assertTrue(output().contains("kit 명령 자체가 업데이트되었습니다"));
    }

    @Test
    void sync_says_up_to_date_when_nothing_changed() {
        assertEquals(0, kit("install"));
        assertEquals(0, kit("sync"));
        assertTrue(output().contains("이미 최신입니다: v0.1.0"));
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

    @Test
    void disabled_skill_is_removed_and_stays_off_after_sync() throws IOException {
        assertEquals(0, kit("install"));
        Path link = home.resolve(".claude/skills/incident-analysis");

        assertEquals(0, kit("skill", "disable", "incident-analysis"));
        assertFalse(Files.exists(link, java.nio.file.LinkOption.NOFOLLOW_LINKS));
        assertEquals(0, kit("sync"));
        assertFalse(Files.exists(link, java.nio.file.LinkOption.NOFOLLOW_LINKS), "sync 해도 꺼 둔 스킬은 다시 생기지 않음");

        assertEquals(0, kit("skill", "enable", "incident-analysis"));
        assertTrue(Files.isSymbolicLink(link));
        assertEquals(1, kit("skill", "disable", "no-such-skill"));
    }

    @Test
    void ui_server_requires_token_and_serves_state_and_toggles() throws Exception {
        assertEquals(0, kit("install"));
        kit.ui.UiServer server = new kit.ui.UiServer(new kit.core.KitEnv(home, origin.toString()));
        int port = server.start(0);
        try {
            String base = "http://127.0.0.1:" + port;
            assertEquals(403, http("GET", base + "/?t=wrong", null, null).code);
            assertEquals(403, http("GET", base + "/api/state", null, null).code);

            Http page = http("GET", base + "/?t=" + server.token(), null, null);
            assertEquals(200, page.code);
            assertTrue(page.body.contains(server.token()) && page.body.contains("팀 AI 키트"));

            Http state = http("GET", base + "/api/state", server.token(), null);
            assertEquals(200, state.code);
            assertTrue(state.body.contains("\"version\":\"v0.1.0\""));
            assertTrue(state.body.contains("\"name\":\"incident-analysis\""));

            Http rate = http("POST", base + "/api/rate", server.token(), "session=abc12345&skill=incident-analysis&rating=wrong&note=%EC%9D%B8%EB%8D%B1%EC%8A%A4");
            assertEquals(200, rate.code);
            String ratings = ManagedBlock.read(home.resolve(".kit/ratings.json"));
            assertTrue(ratings.contains("\"rating\" : \"wrong\"") && ratings.contains("인덱스"));
            assertEquals(400, http("POST", base + "/api/rate", server.token(), "session=abc&rating=bogus").code);
            assertTrue(http("GET", base + "/api/state", server.token(), null).body.contains("결과가 틀림"), "AI 실수 노트에 나타남");

            Http off = http("POST", base + "/api/skill", server.token(), "name=incident-analysis&on=false");
            assertEquals(200, off.code);
            assertTrue(off.body.contains("껐습니다"));
            assertFalse(Files.exists(home.resolve(".claude/skills/incident-analysis"), java.nio.file.LinkOption.NOFOLLOW_LINKS));
        } finally {
            server.stop();
        }
    }

    static final class Http {
        int code;
        String body;
    }

    private static Http http(String method, String url, String token, String form) throws IOException {
        java.net.HttpURLConnection c = (java.net.HttpURLConnection) new java.net.URL(url).openConnection();
        c.setRequestMethod(method);
        if (token != null) {
            c.setRequestProperty("X-Kit-Token", token);
        }
        if (form != null) {
            c.setDoOutput(true);
            c.setRequestProperty("Content-Type", "application/x-www-form-urlencoded");
            c.getOutputStream().write(form.getBytes(StandardCharsets.UTF_8));
        }
        Http h = new Http();
        h.code = c.getResponseCode();
        java.io.InputStream in = h.code < 400 ? c.getInputStream() : c.getErrorStream();
        java.io.ByteArrayOutputStream buf = new java.io.ByteArrayOutputStream();
        if (in != null) {
            byte[] b = new byte[8192];
            int n;
            while ((n = in.read(b)) != -1) {
                buf.write(b, 0, n);
            }
        }
        h.body = new String(buf.toByteArray(), StandardCharsets.UTF_8);
        return h;
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
