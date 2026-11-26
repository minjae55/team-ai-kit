package kit.adapter;

import kit.core.KitEnv;

import java.nio.file.Files;
import java.nio.file.Path;

/**
 * Claude Code: 스킬은 ~/.claude/skills/, 공통 지식은 ~/.claude/CLAUDE.md 에 import 한 줄.
 * (2026-11-26 POC: 사용자 폴더 심볼릭 링크 스킬 인식, @~/ import 동작 확인)
 */
public final class ClaudeAdapter implements ToolAdapter {

    @Override public String id() { return "claude"; }
    @Override public String displayName() { return "Claude Code"; }

    @Override
    public boolean detect(KitEnv env) {
        return Files.isDirectory(env.home().resolve(".claude"));
    }

    @Override
    public Path skillsDir(KitEnv env) {
        return env.home().resolve(".claude").resolve("skills");
    }

    @Override
    public Path contextFile(KitEnv env) {
        return env.home().resolve(".claude").resolve("CLAUDE.md");
    }

    @Override
    public String contextBody(KitEnv env) {
        return "@" + env.display(env.contextSource());
    }
}
