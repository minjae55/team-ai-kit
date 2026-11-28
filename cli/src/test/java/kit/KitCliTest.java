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
        assertFalse(out.contains("kit 명령"), "cli를 안 바꿨으면 kit 명령 안내 없음");
    }

    @Test
    void sync_tells_when_kit_command_itself_changed() throws IOException {
        assertEquals(0, kit("install"));
        write(origin.resolve("install.sh"), "#!/usr/bin/env bash\necho new\n");
        commitAndTag("v0.1.1");

        assertEquals(0, kit("sync"));
        // install.sh로 설치하지 않은 환경(~/.kit/bin/kit.jar 없음)에서는 자동 교체 대신 안내만 한다
        assertTrue(output().contains("kit 명령 자체가 바뀌었습니다. 적용하려면: ~/.kit/repo/install.sh"));
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
    void usage_summary_counts_recent_uses_and_flags_unused_only_after_30_days() throws IOException {
        java.time.Instant now = java.time.Instant.now();
        String old = now.minus(java.time.Duration.ofDays(45)).toString();
        String recent = now.minus(java.time.Duration.ofDays(2)).toString();
        write(home.resolve(".kit/usage.jsonl"),
                "{\"time\":\"" + old + "\",\"skill\":\"perf-report\"}\n"
                + "{\"time\":\"" + recent + "\",\"skill\":\"incident-analysis\"}\n"
                + "{\"time\":\"" + recent + "\",\"skill\":\"incident-analysis\"}\n"
                + "{\"time\":\"" + recent + "\",\"skill\":\"query-analysis\"}\n"
                + "깨진 줄\n");
        kit.core.Usage.Summary sum = new kit.core.Usage(new kit.core.KitEnv(home, "x")).summary(30);
        assertEquals(3, sum.total, "30일 안의 사용만 셈");
        assertEquals(2, sum.bySkill.get("incident-analysis").count);
        assertEquals(0, sum.bySkill.get("perf-report").count, "45일 전 사용은 횟수에서 빠짐");
        assertEquals(old, sum.bySkill.get("perf-report").lastUsed.toString(), "마지막 사용일은 기간과 무관");
        assertTrue(sum.coversDays(30), "기록이 30일 넘음");

        assertEquals(0, kit("install"));
        assertEquals(0, kit("list"));
        String out = output();
        assertTrue(out.contains("스킬 사용 (최근 30일 3회)"), out);
        assertTrue(out.matches("(?s).*incident-analysis\\s+2회\\s+67%.*"), out);

        Path ev = home.resolve(".kit/repo/evals/incident-analysis");
        write(ev.resolve("scenarios/c1/case.json"), "{}");
        write(ev.resolve("scenarios/c2/case.json"), "{}");
        write(ev.resolve("verified.json"), "{\"cases\":{\"c1\":{\"passed\":true,\"date\":\"2026-11-28\"},\"c2\":{\"passed\":false,\"date\":\"2026-11-27\"},\"gone\":{\"passed\":true,\"date\":\"2026-11-29\"}}}");
        captured.reset();
        assertEquals(0, kit("list"));
        assertTrue(output().contains("검증 1/2 · 11-28"), "지워진 시나리오는 세지 않음: " + output());
    }

    @Test
    void export_builds_web_bundles_and_reports_changed_skills() throws Exception {
        assertEquals(0, kit("install"));
        Path out = tmp.resolve("export");
        assertEquals(0, kit("export", "--out", out.toString()));
        assertTrue(output().contains("새로 올리기"), output());
        try (java.util.zip.ZipFile z = new java.util.zip.ZipFile(out.resolve("claude-web/incident-analysis.zip").toFile())) {
            assertTrue(z.getEntry("incident-analysis/SKILL.md") != null, "claude.ai 업로드 형식: 스킬 폴더째 zip");
        }
        String ins = new String(Files.readAllBytes(out.resolve("chatgpt/incident-analysis/instructions.md")), StandardCharsets.UTF_8);
        assertTrue(ins.contains("웹에서 쓸 때의 규칙") && ins.contains("코드 실행"), ins);
        assertFalse(ins.startsWith("---"), "frontmatter는 뺌");
        assertTrue(Files.exists(out.resolve("gemini/incident-analysis/files/SKILL.md")));
        assertTrue(Files.exists(out.resolve("gemini/incident-analysis/올리는법.txt")));

        captured.reset();
        assertEquals(0, kit("export", "--out", out.toString()));
        assertTrue(output().contains("바뀐 스킬이 없습니다"), output());

        Path skillMd = home.resolve(".kit/repo/kit/skills/incident-analysis/SKILL.md");
        Files.write(skillMd, (new String(Files.readAllBytes(skillMd), StandardCharsets.UTF_8) + "\n추가 규칙\n").getBytes(StandardCharsets.UTF_8));
        captured.reset();
        assertEquals(0, kit("export", "--out", out.toString(), "--tools", "chatgpt"));
        assertTrue(output().contains("바뀜 → 다시 올리기"), output());
        assertFalse(output().contains("claude.ai"), "고른 도구만");
    }

    @Test
    void self_update_copies_prebuilt_dist_jar_without_building() throws IOException {
        kit.core.KitEnv env = new kit.core.KitEnv(home, "x");
        write(env.repoDir().resolve("dist/kit.jar"), "new-jar");
        write(env.repoDir().resolve("cli/kit"), "#!/bin/sh");
        write(env.kitDir().resolve("bin/kit.jar"), "old-jar");

        assertEquals(null, kit.core.SelfUpdate.now(env));
        assertEquals("new-jar", new String(Files.readAllBytes(env.kitDir().resolve("bin/kit.jar")), StandardCharsets.UTF_8));
        assertTrue(Files.isExecutable(env.kitDir().resolve("bin/kit")));
        assertFalse(Files.exists(env.kitDir().resolve("self-update.log")), "빌드하지 않음");
    }

    @Test
    void gemini_gets_skills_and_copied_context_in_its_own_folder() throws IOException {
        Files.createDirectories(home.resolve(".gemini"));
        assertEquals(0, kit("install", "--tools", "gemini"));
        assertTrue(Files.exists(home.resolve(".gemini/skills/incident-analysis/SKILL.md")));
        String ctx = new String(Files.readAllBytes(home.resolve(".gemini/GEMINI.md")), StandardCharsets.UTF_8);
        assertTrue(ctx.contains("team-ai-kit") || ctx.contains("팀"), ctx);
        assertFalse(Files.exists(home.resolve(".agents/skills")), "Codex 폴더는 건드리지 않음");
        assertEquals(0, kit("uninstall"));
        assertFalse(Files.exists(home.resolve(".gemini/skills/incident-analysis")));
    }

    @Test
    void only_selected_tool_is_installed() {
        assertEquals(0, kit("install", "--tools", "claude"));
        assertTrue(Files.exists(home.resolve(".claude/skills/incident-analysis")));
        assertFalse(Files.exists(home.resolve(".agents/skills")));
    }

    @Test
    void auto_on_adds_only_kit_hooks_and_off_removes_only_them() throws IOException {
        Path settings = home.resolve(".claude/settings.json");
        write(settings, "{\"theme\":\"dark\",\"hooks\":{\"PostToolUse\":[{\"matcher\":\"Write\",\"hooks\":[{\"type\":\"command\",\"command\":\"echo mine\"}]}]}}");

        assertEquals(0, kit("auto", "on"));
        String on = ManagedBlock.read(settings);
        assertTrue(on.contains("\"theme\" : \"dark\""), "다른 설정 유지");
        assertTrue(on.contains("echo mine"), "직접 만든 훅 유지");
        assertTrue(on.contains("\"SessionStart\"") && on.contains("\"matcher\" : \"startup\"") && on.contains("sync --quiet --if-older-than 6h"));
        assertTrue(on.contains("\"matcher\" : \"Skill\"") && on.contains("\"UserPromptExpansion\"") && on.contains("track --tool claude"));
        assertTrue(Files.exists(home.resolve(".claude/settings.json.kit-backup")));
        assertEquals(0, kit("auto", "on"));
        String twice = ManagedBlock.read(settings);
        assertEquals(twice.indexOf("sync --quiet"), twice.lastIndexOf("sync --quiet"), "두 번 켜도 훅은 하나");
        assertTrue(new kit.core.ClaudeHooks(new kit.core.KitEnv(home, "x")).enabled());

        assertEquals(0, kit("auto", "off"));
        String off = ManagedBlock.read(settings);
        assertFalse(off.contains(".kit/bin/kit"));
        assertTrue(off.contains("echo mine") && off.contains("\"theme\" : \"dark\""));
        assertFalse(off.contains("SessionStart"), "비게 된 이벤트는 정리");
    }

    @Test
    void track_records_skill_use_silently_and_ignores_non_team_commands() throws IOException {
        assertEquals(0, kit("install"));
        java.io.InputStream realIn = System.in;
        try {
            track("{\"session_id\":\"s1\",\"tool_name\":\"Skill\",\"tool_input\":{\"skill\":\"incident-analysis\"}}");
            track("{\"session_id\":\"s2\",\"command_name\":\"incident-analysis\"}");
            track("{\"session_id\":\"s3\",\"command_name\":\"help\"}");
            track("this is not json");
            track("{\"session_id\":\"s4\",\"cwd\":\"/tmp/kit-eval-abc\",\"tool_name\":\"Skill\",\"tool_input\":{\"skill\":\"incident-analysis\"}}");
        } finally {
            System.setIn(realIn);
        }
        java.util.List<String> lines = Files.readAllLines(home.resolve(".kit/usage.jsonl"), StandardCharsets.UTF_8);
        assertEquals(2, lines.size(), "팀 스킬 사용만 기록, /help·깨진 입력·eval 세션은 무시");
        assertTrue(lines.get(0).contains("\"via\":\"ai\"") && lines.get(1).contains("\"via\":\"slash\""));
        assertEquals(Integer.valueOf(2), new kit.core.Usage(new kit.core.KitEnv(home, "x")).counts(30).get("incident-analysis"));
    }

    private void track(String json) {
        System.setIn(new java.io.ByteArrayInputStream(json.getBytes(StandardCharsets.UTF_8)));
        assertEquals(0, kit("track", "--tool", "claude"));
    }

    @Test
    void rollback_pins_previous_version_and_unpin_returns_to_latest() throws IOException {
        assertEquals(0, kit("install"));
        write(origin.resolve("kit/skills/query-analysis/SKILL.md"), "---\nname: query-analysis\ndescription: test\n---\n");
        commitAndTag("v0.2.0");
        assertEquals(0, kit("sync"));
        LockFile lock = LockFile.readOrNull(home.resolve(".kit/kit.lock.json"));
        assertEquals("v0.2.0", lock.version);
        assertEquals("v0.1.0", lock.previous);

        assertEquals(0, kit("rollback"));
        lock = LockFile.readOrNull(home.resolve(".kit/kit.lock.json"));
        assertEquals("v0.1.0", lock.version);
        assertEquals("v0.1.0", lock.pinned);
        assertFalse(Files.exists(home.resolve(".claude/skills/query-analysis"), java.nio.file.LinkOption.NOFOLLOW_LINKS));

        assertEquals(0, kit("sync"));
        assertEquals("v0.1.0", LockFile.readOrNull(home.resolve(".kit/kit.lock.json")).version, "고정 중에는 sync해도 그대로");

        assertEquals(0, kit("unpin"));
        lock = LockFile.readOrNull(home.resolve(".kit/kit.lock.json"));
        assertEquals("v0.2.0", lock.version);
        assertEquals(null, lock.pinned);
        assertEquals(1, kit("pin", "v9.9.9"), "없는 버전은 고정 불가");
    }

    @Test
    void quiet_sync_prints_one_line_only_when_updated_and_respects_interval() throws IOException {
        assertEquals(0, kit("install"));
        captured.reset();
        assertEquals(0, kit("sync", "--quiet"));
        assertEquals("", output().trim(), "바뀐 게 없으면 아무것도 출력하지 않음 (AI 맥락 오염 방지)");

        write(origin.resolve("kit/skills/query-analysis/SKILL.md"), "---\nname: query-analysis\ndescription: test\n---\n");
        commitAndTag("v0.2.0");
        captured.reset();
        assertEquals(0, kit("sync", "--quiet", "--if-older-than", "1h"));
        assertEquals("", output().trim(), "방금 확인했으면 1시간 안에는 확인하지 않음");

        captured.reset();
        assertEquals(0, kit("sync", "--quiet"));
        String out = output().trim();
        assertTrue(out.startsWith("[팀 AI 키트] v0.1.0 → v0.2.0 업데이트됨") && out.contains("새 스킬: query-analysis"), out);
        assertEquals(1, out.split("\\R").length, "한 줄만");
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

            assertEquals(0, kit("skill", "enable", "incident-analysis"));
            Http preview = http("POST", base + "/api/uninstall", server.token(), "mode=preview");
            assertTrue(preview.body.contains("미리보기"), preview.body);
            assertTrue(Files.exists(home.resolve(".claude/skills/incident-analysis"), java.nio.file.LinkOption.NOFOLLOW_LINKS), "미리보기는 아무것도 안 지움");
            assertEquals(400, http("POST", base + "/api/uninstall", server.token(), "mode=bogus").code);
            Http removed = http("POST", base + "/api/uninstall", server.token(), "mode=remove");
            assertTrue(removed.body.contains("완료"), removed.body);
            assertFalse(Files.exists(home.resolve(".claude/skills/incident-analysis"), java.nio.file.LinkOption.NOFOLLOW_LINKS));
            assertTrue(Files.exists(home.resolve(".kit/repo")), "remove는 ~/.kit 을 남김");
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
