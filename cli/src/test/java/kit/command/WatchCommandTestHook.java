package kit.command;

import java.util.concurrent.CountDownLatch;

/** 테스트에서 kit watch 를 띄웠다가 내리기 위한 연결점 */
public final class WatchCommandTestHook {
    private WatchCommandTestHook() {}

    public static void prepare() {
        WatchCommand.startedForTest = new CountDownLatch(1);
    }

    public static void stop() {
        if (WatchCommand.serverForTest != null) {
            WatchCommand.serverForTest.stop(0);
        }
        WatchCommand.startedForTest = null;
        WatchCommand.serverForTest = null;
    }
}
