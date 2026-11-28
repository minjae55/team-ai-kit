package kit.adapter;

import kit.core.KitEnv;

import java.io.IOException;
import java.nio.file.Path;
import java.util.Arrays;
import java.util.List;

/** AI 도구별 차이(스킬 폴더, 지침 파일, 연결 방식)를 감춘다. 새 도구 지원 = 구현 하나 추가. */
public interface ToolAdapter {

    /** lock 파일과 --tools 옵션에 쓰는 이름 (claude, codex) */
    String id();

    String displayName();

    /** 이 PC에 도구가 설치되어 있는지 */
    boolean detect(KitEnv env);

    /** 스킬을 넣는 폴더 */
    Path skillsDir(KitEnv env);

    /** 팀 공통 지식을 연결하는 사용자 전역 지침 파일 */
    Path contextFile(KitEnv env);

    /** 지침 파일의 관리 구역에 넣을 본문 */
    String contextBody(KitEnv env) throws IOException;

    static List<ToolAdapter> all() {
        return Arrays.<ToolAdapter>asList(new ClaudeAdapter(), new CodexAdapter(), new GeminiAdapter());
    }

    static ToolAdapter byId(String id) {
        for (ToolAdapter a : all()) {
            if (a.id().equalsIgnoreCase(id.trim())) {
                return a;
            }
        }
        return null;
    }
}
