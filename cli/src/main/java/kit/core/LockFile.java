package kit.core;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.SerializationFeature;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/** 키트가 무엇을 설치했는지 기록한다. 키트는 여기 적힌 것만 고치거나 지운다. */
@JsonIgnoreProperties(ignoreUnknown = true)
public final class LockFile {

    private static final ObjectMapper JSON = new ObjectMapper().enable(SerializationFeature.INDENT_OUTPUT);

    public String version;
    public String commit;
    public String installedAt;
    public String mode = "link";
    /** 내 PC에서 꺼 둔 팀 스킬. 설치·sync 때 건너뛴다 (팀 레포와 다른 팀원에게는 영향 없음) */
    public List<String> disabled = new ArrayList<String>();
    public Map<String, ToolState> tools = new LinkedHashMap<String, ToolState>();

    @JsonIgnoreProperties(ignoreUnknown = true)
    public static final class ToolState {
        /** 키트가 만든 스킬 이름 (설치 위치는 도구의 스킬 폴더/이름) */
        public List<String> skills = new ArrayList<String>();
        /** 공통 지식을 연결한 지침 파일 */
        public String contextFile;
        /** 지침 파일을 키트가 새로 만들었으면 true (제거할 때 비어 있으면 파일도 지움) */
        public boolean contextFileCreated;
    }

    public static LockFile readOrNull(Path file) throws IOException {
        if (!Files.exists(file)) {
            return null;
        }
        return JSON.readValue(file.toFile(), LockFile.class);
    }

    public void write(Path file) throws IOException {
        Files.createDirectories(file.getParent());
        JSON.writeValue(file.toFile(), this);
    }

    public ToolState tool(String id) {
        ToolState s = tools.get(id);
        if (s == null) {
            s = new ToolState();
            tools.put(id, s);
        }
        return s;
    }
}
