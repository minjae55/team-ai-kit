package kit.command;

import kit.core.KitEnv;
import kit.core.LockFile;
import kit.core.WebExport;
import picocli.CommandLine.Command;
import picocli.CommandLine.Option;

import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.Arrays;
import java.util.Collections;
import java.util.List;
import java.util.concurrent.Callable;

@Command(name = "export", description = "웹 AI(claude.ai, ChatGPT, Gemini)에 올릴 스킬 묶음을 만든다. 바뀐 스킬을 알려 준다")
public class ExportCommand extends Common implements Callable<Integer> {

    @Option(names = "--tools", split = ",", description = "claude-web, chatgpt, gemini (생략하면 셋 다)")
    List<String> tools;

    @Option(names = "--out", description = "만들 위치 (기본: ~/.kit/export)")
    String outDir;

    @Override
    public Integer call() throws Exception {
        KitEnv env = env();
        LockFile lock = LockFile.readOrNull(env.lockFile());
        if (lock == null) {
            stdout.println("설치된 키트가 없습니다. kit install 로 먼저 설치하세요");
            return 1;
        }
        WebExport ex = new WebExport(env);
        Path out = outDir != null ? Paths.get(outDir) : ex.defaultOut();
        List<String> want = tools == null || tools.isEmpty() ? WebExport.TOOLS : tools;
        List<WebExport.Entry> entries = ex.run(out, want, lock.disabled == null ? Collections.<String>emptyList() : lock.disabled);
        stdout.println("웹 AI용 스킬 묶음: " + out);
        for (String tool : want) {
            stdout.println();
            stdout.println(label(tool) + "  (" + hint(tool) + ")");
            for (WebExport.Entry e : entries) {
                if (e.tool.equals(tool)) {
                    String mark = "new".equals(e.status) ? "새로 올리기" : "changed".equals(e.status) ? "바뀜 → 다시 올리기" : "그대로";
                    stdout.println(String.format("  %-24s %s", e.skill, mark));
                }
            }
        }
        long todo = entries.stream().filter(e -> !"same".equals(e.status)).count();
        stdout.println();
        stdout.println(todo == 0 ? "지난번 이후 바뀐 스킬이 없습니다" : "올려야 할 것 " + todo + "개. 각 폴더의 올리는법.txt 를 따르세요");
        return 0;
    }

    static String label(String tool) {
        return "claude-web".equals(tool) ? "claude.ai" : "chatgpt".equals(tool) ? "ChatGPT" : "Gemini";
    }

    static String hint(String tool) {
        if ("claude-web".equals(tool)) {
            return "설정 → 기능(Capabilities) → 스킬에서 zip 업로드. 조직 관리자는 전원에게 배포 가능";
        }
        if ("chatgpt".equals(tool)) {
            return "GPT·프로젝트 지침에 instructions.md, 지식에 files/";
        }
        return "Gem 안내에 instructions.md, 지식에 files/";
    }

    static List<String> all() {
        return Arrays.asList("claude-web", "chatgpt", "gemini");
    }
}
