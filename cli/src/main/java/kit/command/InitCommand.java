package kit.command;

import kit.core.ProjectScan;
import picocli.CommandLine.Command;
import picocli.CommandLine.Option;

import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.concurrent.Callable;

@Command(name = "init", description = "프로젝트 폴더를 훑어 AI 작업 지침(AGENTS.md) 초안을 만든다. 기존 AGENTS.md는 덮어쓰지 않는다")
public class InitCommand extends Common implements Callable<Integer> {

    @Option(names = "--dir", description = "프로젝트 폴더 (기본: 현재 폴더)")
    String dir = ".";

    @Option(names = "--print", description = "파일을 만들지 않고 초안만 보여 준다")
    boolean print;

    @Override
    public Integer call() throws Exception {
        Path root = Paths.get(dir).toAbsolutePath().normalize();
        ProjectScan s = ProjectScan.scan(root);
        String draft = s.draft();
        if (print) {
            stdout.print(draft);
            return 0;
        }
        Path agents = root.resolve("AGENTS.md");
        Path target = Files.exists(agents) ? root.resolve("AGENTS.kit-draft.md") : agents;
        Files.write(target, draft.getBytes(StandardCharsets.UTF_8));
        stdout.println((target.equals(agents) ? "만들었습니다: " : "AGENTS.md가 이미 있어서 따로 만들었습니다 (비교해서 필요한 부분만 옮기세요): ") + target);
        stdout.println("  찾은 것: " + (s.framework.isEmpty() ? "앱 종류 모름" : s.framework)
                + ", 서비스 " + s.services.size() + "개" + (s.composeFiles.isEmpty() ? "" : " (" + String.join(", ", s.composeFiles) + ")")
                + (s.hasK6 ? ", k6" : "") + (s.hasLogs ? ", logs" : ""));
        Path claude = root.resolve("CLAUDE.md");
        if (!Files.exists(claude)) {
            Files.write(claude, "@AGENTS.md\n".getBytes(StandardCharsets.UTF_8));
            stdout.println("만들었습니다: " + claude + " (@AGENTS.md: Claude Code가 AGENTS.md를 읽게 함)");
        } else if (!new String(Files.readAllBytes(claude), StandardCharsets.UTF_8).contains("@AGENTS.md")) {
            stdout.println("참고: CLAUDE.md에 @AGENTS.md 줄이 없습니다. Claude Code가 AGENTS.md를 읽게 하려면 맨 위에 추가하세요");
        }
        stdout.println("다음: 초안의 \"(확인 필요)\"를 채우세요. DB 접속 정보 파일 경로는 직접 적고, AI에게 그 파일을 열게 하지 마세요");
        return 0;
    }
}
