package kit.adapter;

import kit.core.KitEnv;
import kit.core.ManagedBlock;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;

/**
 * Antigravity (구글 에이전트 IDE·앱): 공통 원칙은 ~/.gemini/GEMINI.md (Gemini CLI와 같은 파일, 같은 관리 구역이라 겹치지 않음),
 * 스킬은 ~/.gemini/config/skills/ (Antigravity 2.0 문서 기준. 예전 IDE는 ~/.gemini/antigravity/skills/).
 * 문서: https://antigravity.google/docs/skills , https://antigravity.google/docs/rules
 */
public final class AntigravityAdapter implements ToolAdapter {

    @Override public String id() { return "antigravity"; }
    @Override public String displayName() { return "Antigravity"; }

    @Override
    public boolean detect(KitEnv env) {
        Path g = env.home().resolve(".gemini");
        return Files.isDirectory(g.resolve("antigravity")) || Files.isDirectory(g.resolve("config"))
                || Files.isDirectory(env.home().resolve(".antigravity"))
                || (env.home().equals(Paths.get(System.getProperty("user.home")).toAbsolutePath().normalize())
                    && Files.isDirectory(Paths.get("/Applications/Antigravity.app")));
    }

    @Override
    public Path skillsDir(KitEnv env) {
        Path g = env.home().resolve(".gemini");
        if (Files.isDirectory(g.resolve("antigravity")) && !Files.isDirectory(g.resolve("config"))) {
            return g.resolve("antigravity").resolve("skills");   // 예전 IDE
        }
        return g.resolve("config").resolve("skills");
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
