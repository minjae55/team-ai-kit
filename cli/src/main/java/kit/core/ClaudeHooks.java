package kit.core;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.ObjectNode;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.util.Iterator;

/**
 * ~/.claude/settings.json 에 키트 훅을 넣고 뺀다. 키트 훅은 명령에 ".kit/bin/kit" 이 들어 있는 것으로 구별한다.
 * - SessionStart(startup): kit sync --quiet --if-older-than 6h  → 세션을 열 때 자동 업데이트 (바뀌면 한 줄이 AI 맥락에 들어감)
 * - PostToolUse(Skill): kit track  → AI가 스킬을 쓰면 사용 기록
 * - UserPromptExpansion: kit track  → 사람이 /스킬이름 으로 직접 부르면 사용 기록 (Skill 도구를 거치지 않아서 따로 받음)
 * 다른 설정과 사람이 만든 훅은 건드리지 않고, 처음 바꾸기 전에 settings.json.kit-backup 으로 백업한다.
 */
public final class ClaudeHooks {

    private static final ObjectMapper JSON = new ObjectMapper();
    private static final String MARK = ".kit/bin/kit";

    private final KitEnv env;

    public ClaudeHooks(KitEnv env) {
        this.env = env;
    }

    Path settings() {
        return env.home().resolve(".claude").resolve("settings.json");
    }

    private String kit() {
        return env.kitDir().resolve("bin").resolve("kit").toString();
    }

    public boolean enabled() throws IOException {
        JsonNode hooks = read().path("hooks");
        return containsKit(hooks.path("SessionStart")) && containsKit(hooks.path("PostToolUse"));
    }

    public void enable() throws IOException {
        ObjectNode root = read();
        removeKit(root);
        ObjectNode hooks = root.has("hooks") && root.get("hooks").isObject() ? (ObjectNode) root.get("hooks") : root.putObject("hooks");
        // 키트가 없거나 실패해도 세션을 방해하지 않도록: 오류는 버리고 항상 0으로 끝낸다
        add(hooks, "SessionStart", "startup", "'" + kit() + "' sync --quiet --if-older-than 6h 2>/dev/null || true", 60);
        add(hooks, "PostToolUse", "Skill", "'" + kit() + "' track --tool claude >/dev/null 2>&1 || true", 10);
        add(hooks, "UserPromptExpansion", null, "'" + kit() + "' track --tool claude >/dev/null 2>&1 || true", 10);
        write(root);
    }

    public void disable() throws IOException {
        if (!Files.exists(settings())) {
            return;
        }
        ObjectNode root = read();
        if (removeKit(root)) {
            write(root);
        }
    }

    private static void add(ObjectNode hooks, String event, String matcher, String command, int timeout) {
        ArrayNode groups = hooks.has(event) && hooks.get(event).isArray() ? (ArrayNode) hooks.get(event) : hooks.putArray(event);
        ObjectNode group = groups.addObject();
        if (matcher != null) {
            group.put("matcher", matcher);
        }
        ObjectNode h = group.putArray("hooks").addObject();
        h.put("type", "command");
        h.put("command", command);
        h.put("timeout", timeout);
    }

    private static boolean containsKit(JsonNode groups) {
        for (JsonNode g : groups) {
            for (JsonNode h : g.path("hooks")) {
                if (h.path("command").asText("").contains(MARK)) {
                    return true;
                }
            }
        }
        return false;
    }

    /** 키트 훅만 지우고, 비게 된 묶음·이벤트는 정리한다. 지운 게 있으면 true */
    private static boolean removeKit(ObjectNode root) {
        JsonNode hooksNode = root.get("hooks");
        if (hooksNode == null || !hooksNode.isObject()) {
            return false;
        }
        ObjectNode hooks = (ObjectNode) hooksNode;
        boolean changed = false;
        Iterator<String> events = hooks.fieldNames();
        java.util.List<String> emptyEvents = new java.util.ArrayList<String>();
        while (events.hasNext()) {
            String ev = events.next();
            JsonNode groups = hooks.get(ev);
            if (!groups.isArray()) {
                continue;
            }
            for (int i = groups.size() - 1; i >= 0; i--) {
                JsonNode g = groups.get(i);
                JsonNode hs = g.path("hooks");
                if (!hs.isArray()) {
                    continue;
                }
                for (int j = hs.size() - 1; j >= 0; j--) {
                    if (hs.get(j).path("command").asText("").contains(MARK)) {
                        ((ArrayNode) hs).remove(j);
                        changed = true;
                    }
                }
                if (hs.size() == 0) {
                    ((ArrayNode) groups).remove(i);
                }
            }
            if (groups.size() == 0) {
                emptyEvents.add(ev);
            }
        }
        for (String ev : emptyEvents) {
            hooks.remove(ev);
        }
        if (hooks.size() == 0) {
            root.remove("hooks");
        }
        return changed;
    }

    private ObjectNode read() throws IOException {
        Path f = settings();
        if (!Files.exists(f)) {
            return JSON.createObjectNode();
        }
        JsonNode n = JSON.readTree(f.toFile());
        if (n == null || !n.isObject()) {
            throw new IOException(f + " 이(가) JSON 객체가 아니라서 건드리지 않습니다");
        }
        return (ObjectNode) n;
    }

    private void write(ObjectNode root) throws IOException {
        Path f = settings();
        Files.createDirectories(f.getParent());
        Path backup = f.resolveSibling("settings.json.kit-backup");
        if (Files.exists(f) && !Files.exists(backup)) {
            Files.copy(f, backup, StandardCopyOption.COPY_ATTRIBUTES);
        }
        JSON.writerWithDefaultPrettyPrinter().writeValue(f.toFile(), root);
    }
}
