package kit.adapter;

import kit.core.KitEnv;
import kit.core.ManagedBlock;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;

/**
 * Codex: 스킬은 ~/.agents/skills/, 공통 지식은 ~/.codex/AGENTS.md 에 내용 복사.
 * Codex 공식 문서에 AGENTS.md import 기능이 없어서 복사한다. 그래서 sync 때 다시 복사해야 최신이 된다.
 */
public final class CodexAdapter implements ToolAdapter {

    @Override public String id() { return "codex"; }
    @Override public String displayName() { return "Codex"; }

    @Override
    public boolean detect(KitEnv env) {
        return Files.isDirectory(env.home().resolve(".codex"));
    }

    @Override
    public Path skillsDir(KitEnv env) {
        return env.home().resolve(".agents").resolve("skills");
    }

    @Override
    public Path contextFile(KitEnv env) {
        return env.home().resolve(".codex").resolve("AGENTS.md");
    }

    @Override
    public String contextBody(KitEnv env) throws IOException {
        return ManagedBlock.read(env.contextSource()).trim();
    }
}
