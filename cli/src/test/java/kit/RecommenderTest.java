package kit;

import kit.core.KitEnv;
import kit.ui.Recommender;
import kit.ui.SessionLog;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

class RecommenderTest {

    @TempDir Path home;
    KitEnv env;

    @BeforeEach
    void setUp() throws IOException {
        env = new KitEnv(home, "unused");
        write(env.repoDir().resolve("kit/skills/incident-analysis/SKILL.md"), "---\nname: incident-analysis\ndescription: x\n---\n");
        write(env.repoDir().resolve("kit/context/AGENTS.md"), "- 핵심부터, 짧게 말한다\n");
        write(env.repoDir().resolve("kit/insights/patterns.json"), "{\"minPrompts\":3,\"minSessions\":2,\"patterns\":["
                + "{\"id\":\"query-analysis\",\"kind\":\"skill\",\"title\":\"SQL 쿼리 분석\",\"description\":\"쿼리를 분석한다\",\"keywords\":[\"쿼리\",\"explain\"],\"coveredBy\":[\"query-analysis\"]},"
                + "{\"id\":\"incident\",\"kind\":\"skill\",\"title\":\"장애 분석\",\"description\":\"장애\",\"keywords\":[\"느려\"],\"coveredBy\":[\"incident-analysis\"]},"
                + "{\"id\":\"rule-concise\",\"kind\":\"rule\",\"title\":\"짧게\",\"description\":\"짧게\",\"keywords\":[\"짧게\"],\"coveredBy\":[],\"coveredByContext\":\"핵심부터, 짧게\"}"
                + "]}");
    }

    List<SessionLog.Session> sessions() {
        List<SessionLog.Session> list = new ArrayList<SessionLog.Session>();
        list.add(session(10, "이 쿼리 왜 느려?", "EXPLAIN 결과 봐줘", "짧게 말해"));
        list.add(session(6, "쿼리 실행 계획 봐줘", "짧게 말해"));
        list.add(session(2, "느려졌어", "짧게"));
        return list;
    }

    static SessionLog.Session session(int toolUses, String... prompts) {
        SessionLog.Session s = new SessionLog.Session();
        s.promptTexts = new ArrayList<String>(Arrays.asList(prompts));
        s.prompts = prompts.length;
        s.toolUses = toolUses;
        s.start = "2026-11-27T00:00:00Z";
        return s;
    }

    @Test
    void finds_repeated_work_and_skips_what_is_already_covered() throws IOException {
        List<Map<String, Object>> recs = new Recommender(env).recommend(sessions(), Collections.<String>emptySet());

        assertEquals(1, recs.size(), "장애 분석은 스킬이 이미 있고, '짧게'는 팀 원칙에 이미 있어서 제외");
        Map<String, Object> r = recs.get(0);
        assertEquals("query-analysis", r.get("id"));
        assertEquals(3, r.get("prompts"));
        assertEquals(2, r.get("sessions"));
        assertTrue(((List<?>) r.get("examples")).contains("이 쿼리 왜 느려?"));
    }

    @Test
    void dismissed_candidates_are_hidden() throws IOException {
        List<Map<String, Object>> recs = new Recommender(env).recommend(sessions(), Collections.singleton("query-analysis"));
        assertTrue(recs.isEmpty());
    }

    @Test
    void below_threshold_is_not_recommended() throws IOException {
        List<SessionLog.Session> one = new ArrayList<SessionLog.Session>();
        one.add(session(5, "쿼리 봐줘", "쿼리 또 봐줘", "쿼리 하나 더"));
        assertTrue(new Recommender(env).recommend(one, Collections.<String>emptySet()).isEmpty(), "세션 1개뿐이면 추천 안 함");
    }

    @Test
    void draft_is_created_in_personal_skills_and_never_overwrites() throws IOException {
        Path skill = new Recommender(env).createDraft("query-analysis", sessions());

        assertEquals(home.resolve(".claude/skills/query-analysis/SKILL.md"), skill);
        String text = new String(Files.readAllBytes(skill), StandardCharsets.UTF_8);
        assertTrue(text.startsWith("---\nname: query-analysis\ndescription: "));
        assertTrue(text.contains("이 쿼리 왜 느려?"));
        assertThrows(IOException.class, () -> new Recommender(env).createDraft("query-analysis", sessions()), "있으면 덮어쓰지 않음");
    }

    private static void write(Path file, String text) throws IOException {
        Files.createDirectories(file.getParent());
        Files.write(file, text.getBytes(StandardCharsets.UTF_8));
    }
}
