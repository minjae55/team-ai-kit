package kit.ui;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;

import java.io.BufferedReader;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.DirectoryStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.regex.Pattern;

/**
 * Claude Code 세션 기록(~/.claude/projects/*\/*.jsonl)을 읽어 화면용으로 요약한다.
 * 내 PC에서만 읽고, 명령은 원문 대신 사람이 읽는 설명만 쓴다.
 */
public final class SessionLog {

    private static final ObjectMapper JSON = new ObjectMapper();
    private static final Pattern TAGS = Pattern.compile(
            "<(pasted_content|system-reminder|command-[a-z-]+|local-command-[a-z-]+)[^>]*>.*?</\\1[^>]*>", Pattern.DOTALL);

    public static final class Session {
        public String id;
        public String project = "";
        public String title = "";
        public String firstPrompt = "";
        public String start;
        public String end;
        public int prompts;
        public Set<String> skills = new LinkedHashSet<String>();
        /** 실패한 명령 설명 → 실패 횟수 */
        public Map<String, Integer> failedCommands = new HashMap<String, Integer>();
    }

    private SessionLog() {}

    public static List<Session> recent(Path projectsDir, int days) throws IOException {
        List<Session> out = new ArrayList<Session>();
        if (!Files.isDirectory(projectsDir)) {
            return out;
        }
        long since = System.currentTimeMillis() - days * 86400000L;
        try (DirectoryStream<Path> projects = Files.newDirectoryStream(projectsDir)) {
            for (Path project : projects) {
                if (!Files.isDirectory(project)) {
                    continue;
                }
                try (DirectoryStream<Path> files = Files.newDirectoryStream(project, "*.jsonl")) {
                    for (Path f : files) {
                        if (Files.getLastModifiedTime(f).toMillis() < since) {
                            continue;
                        }
                        Session s = parse(f);
                        if (s.start != null && s.prompts > 0) {
                            out.add(s);
                        }
                    }
                }
            }
        }
        out.sort((a, b) -> b.start.compareTo(a.start));
        return out;
    }

    static Session parse(Path file) throws IOException {
        Session s = new Session();
        String name = file.getFileName().toString();
        s.id = name.length() > 8 ? name.substring(0, 8) : name;
        Map<String, String> bashById = new HashMap<String, String>();
        Set<String> failedIds = new LinkedHashSet<String>();
        try (BufferedReader r = Files.newBufferedReader(file, StandardCharsets.UTF_8)) {
            String line;
            while ((line = r.readLine()) != null) {
                JsonNode e;
                try {
                    e = JSON.readTree(line);
                } catch (IOException ex) {
                    continue;
                }
                if (e == null || !e.isObject()) {
                    continue;
                }
                String ts = e.path("timestamp").asText(null);
                if (ts != null) {
                    if (s.start == null || ts.compareTo(s.start) < 0) s.start = ts;
                    if (s.end == null || ts.compareTo(s.end) > 0) s.end = ts;
                }
                if (s.project.isEmpty() && e.hasNonNull("cwd")) {
                    Path cwd = file.getFileSystem().getPath(e.get("cwd").asText());
                    s.project = cwd.getFileName() == null ? "" : cwd.getFileName().toString();
                }
                String type = e.path("type").asText("");
                if ("custom-title".equals(type) && e.hasNonNull("customTitle")) {
                    s.title = e.get("customTitle").asText();
                }
                JsonNode content = e.path("message").path("content");
                if ("user".equals(type)) {
                    StringBuilder text = new StringBuilder();
                    if (content.isTextual()) {
                        text.append(content.asText());
                    } else if (content.isArray()) {
                        for (JsonNode b : content) {
                            if ("text".equals(b.path("type").asText())) {
                                text.append(b.path("text").asText()).append('\n');
                            } else if ("tool_result".equals(b.path("type").asText()) && b.path("is_error").asBoolean(false)) {
                                failedIds.add(b.path("tool_use_id").asText());
                            }
                        }
                    }
                    if (isRealPrompt(e, text.toString())) {
                        s.prompts++;
                        if (s.firstPrompt.isEmpty()) {
                            s.firstPrompt = clean(text.toString());
                        }
                    }
                } else if ("assistant".equals(type) && content.isArray()) {
                    for (JsonNode b : content) {
                        if (!"tool_use".equals(b.path("type").asText())) {
                            continue;
                        }
                        String tool = b.path("name").asText();
                        if ("Skill".equals(tool)) {
                            String skill = b.path("input").path("skill").asText("");
                            int colon = skill.lastIndexOf(':');
                            s.skills.add(colon >= 0 ? skill.substring(colon + 1) : skill);
                        } else if ("Bash".equals(tool)) {
                            bashById.put(b.path("id").asText(), b.path("input").path("description").asText("(설명 없음)"));
                        }
                    }
                }
            }
        }
        for (String id : failedIds) {
            String desc = bashById.get(id);
            if (desc != null) {
                Integer n = s.failedCommands.get(desc);
                s.failedCommands.put(desc, n == null ? 1 : n + 1);
            }
        }
        return s;
    }

    private static boolean isRealPrompt(JsonNode e, String text) {
        if (e.path("isMeta").asBoolean(false) || e.path("isSidechain").asBoolean(false)) {
            return false;
        }
        String t = text.trim();
        return !t.isEmpty() && !t.startsWith("<task-notification") && !t.startsWith("[Request interrupted")
                && !t.startsWith("<system-reminder") && !t.startsWith("Base directory for this skill");
    }

    static String clean(String text) {
        boolean pasted = text.contains("<pasted_content");
        String t = TAGS.matcher(text).replaceAll("").replaceAll("\\s+", " ").trim();
        if (t.isEmpty() && pasted) {
            return "[붙여넣은 내용]";
        }
        if (t.length() > 80) {
            t = t.substring(0, 80) + "…";
        }
        return t;
    }
}
