package kit.command;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import kit.core.Installer;
import kit.core.KitEnv;
import kit.core.Usage;
import picocli.CommandLine.Command;
import picocli.CommandLine.Option;

import java.util.List;
import java.util.concurrent.Callable;

/**
 * 훅이 부르는 숨은 명령: 표준 입력으로 받은 훅 JSON에서 스킬 이름을 꺼내 사용 기록을 남긴다.
 * 아무것도 출력하지 않고, 무슨 일이 있어도 0으로 끝난다 (AI 세션을 방해하지 않기 위해).
 */
@Command(name = "track", hidden = true, description = "스킬 사용 기록 (훅 전용)")
public class TrackCommand extends Common implements Callable<Integer> {

    private static final ObjectMapper JSON = new ObjectMapper();

    @Option(names = "--tool", defaultValue = "claude")
    String tool;

    @Override
    public Integer call() {
        try {
            JsonNode in = JSON.readTree(System.in);
            if (in == null) {
                return 0;
            }
            KitEnv env = env();
            String skill = null;
            String via = null;
            if ("Skill".equals(in.path("tool_name").asText())) {
                skill = in.path("tool_input").path("skill").asText(null);
                via = "ai";
            } else if (in.hasNonNull("command_name")) {
                skill = in.get("command_name").asText();
                via = "slash";
            } else if (in.path("prompt").asText("").startsWith("/")) {
                skill = in.get("prompt").asText().substring(1).split("\\s+")[0];
                via = "slash";
            }
            if (skill == null || skill.isEmpty()) {
                return 0;
            }
            skill = skill.replaceFirst("^/", "");
            int colon = skill.lastIndexOf(':');
            if (colon >= 0) {
                skill = skill.substring(colon + 1);
            }
            // 직접 호출(/이름)은 팀 스킬일 때만 기록 (/help 같은 내장 명령 제외)
            List<String> team = new Installer(env, null, true).repoSkills();
            if ("slash".equals(via) && !team.contains(skill)) {
                return 0;
            }
            new Usage(env).record(tool, skill, in.path("session_id").asText(""), via);
        } catch (Exception ignored) {
            // 기록 실패는 조용히 넘긴다
        }
        return 0;
    }
}
