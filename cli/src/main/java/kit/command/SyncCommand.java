package kit.command;

import kit.core.SyncService;
import picocli.CommandLine.Command;
import picocli.CommandLine.Option;

import java.util.concurrent.Callable;

@Command(name = "sync", description = "설치된 도구를 키트 최신 버전(고정했으면 그 버전)으로 맞추고, 무엇이 바뀌었는지 보여 준다")
public class SyncCommand extends Common implements Callable<Integer> {

    @Option(names = "--dry-run", description = "바꿀 내용만 보여 주고 아무것도 바꾸지 않는다")
    boolean dryRun;

    @Option(names = "--quiet", description = "바뀐 게 있을 때만 한 줄 출력 (자동 업데이트 훅용)")
    boolean quiet;

    @Option(names = "--if-older-than", description = "마지막 확인 후 이 시간이 지났을 때만 확인 (예: 6h, 30m)")
    String ifOlderThan;

    @Override
    public Integer call() throws Exception {
        SyncService.Options o = new SyncService.Options();
        o.dryRun = dryRun;
        o.quiet = quiet;
        o.ifOlderThan = ifOlderThan;
        try {
            return new SyncService(env(), out(), stdout).run(o);
        } catch (Exception e) {
            if (quiet) {
                return 0; // 자동 업데이트는 실패해도 세션을 방해하지 않는다
            }
            throw e;
        }
    }
}
