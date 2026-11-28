package kit.adapter;

import kit.core.KitEnv;
import kit.core.ManagedBlock;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;

/**
 * Gemini CLI: 스킬은 ~/.gemini/skills/, 공통 지식은 ~/.gemini/GEMINI.md 에 내용 복사.
 * Gemini CLI는 ~/.agents/skills/ 도 읽지만 Codex와 같은 폴더를 함께 관리하지 않으려고 전용 폴더를 쓴다
 * (둘 다 설치하면 Gemini에 같은 스킬이 두 번 보일 수 있음). ~/.gemini 는 gemini를 한 번 실행하면 생긴다.
 */
public final class GeminiAdapter implements ToolAdapter {

    @Override public String id() { return "gemini"; }
    @Override public String displayName() { return "Gemini CLI"; }

    @Override
    public boolean detect(KitEnv env) {
        return Files.isDirectory(env.home().resolve(".gemini"));
    }

    @Override
    public Path skillsDir(KitEnv env) {
        return env.home().resolve(".gemini").resolve("skills");
    }

    @Override
    public Path contextFile(KitEnv env) {
        return env.home().resolve(".gemini").resolve("GEMINI.md");
    }

    @Override
    public String contextBody(KitEnv env) throws IOException {
        return ManagedBlock.read(env.contextSource()).trim();
    }
}
