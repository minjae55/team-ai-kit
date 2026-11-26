package kit.command;

import kit.KitCommand;
import kit.adapter.ToolAdapter;
import kit.core.Installer;
import kit.core.KitEnv;
import picocli.CommandLine.ParentCommand;

import java.io.PrintStream;
import java.text.SimpleDateFormat;
import java.util.ArrayList;
import java.util.Date;
import java.util.List;

/** 명령들이 같이 쓰는 것: 환경, 출력, 도구 선택 */
abstract class Common {

    @ParentCommand
    KitCommand parent;

    PrintStream stdout = System.out;
    int warnings;
    int errors;

    KitEnv env() {
        return KitEnv.resolve(parent.home, parent.repo);
    }

    Installer.Out out() {
        return new Installer.Out() {
            @Override public void info(String msg) { stdout.println("  " + msg); }
            @Override public void warn(String msg) { warnings++; stdout.println("  [경고] " + msg); }
        };
    }

    void ok(String msg) { stdout.println("  [OK] " + msg); }
    void warn(String msg) { warnings++; stdout.println("  [경고] " + msg); }
    void error(String msg) { errors++; stdout.println("  [오류] " + msg); }

    /** --tools 가 있으면 그 도구들(없는 폴더도 만듦), 없으면 이 PC에서 감지된 도구들 */
    List<ToolAdapter> selectTools(KitEnv env, String toolsOption) {
        List<ToolAdapter> picked = new ArrayList<ToolAdapter>();
        if (toolsOption != null && !toolsOption.trim().isEmpty()) {
            for (String id : toolsOption.split(",")) {
                ToolAdapter a = ToolAdapter.byId(id);
                if (a == null) {
                    error("알 수 없는 도구: " + id.trim() + " (지원: claude, codex)");
                } else {
                    picked.add(a);
                }
            }
            return picked;
        }
        for (ToolAdapter a : ToolAdapter.all()) {
            if (a.detect(env)) {
                picked.add(a);
            }
        }
        return picked;
    }

    static String now() {
        return new SimpleDateFormat("yyyy-MM-dd'T'HH:mm:ssXXX").format(new Date());
    }
}
