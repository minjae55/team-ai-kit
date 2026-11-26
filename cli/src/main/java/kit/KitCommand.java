package kit;

import kit.command.DoctorCommand;
import kit.command.InstallCommand;
import kit.command.ListCommand;
import kit.command.SyncCommand;
import kit.command.UninstallCommand;
import picocli.CommandLine.Command;
import picocli.CommandLine.Option;

@Command(name = "kit",
        mixinStandardHelpOptions = true,
        version = "kit 0.1.0",
        description = "팀 AI 키트: 팀 스킬과 공통 지식을 AI 도구(Claude Code, Codex)에 설치하고 최신으로 유지한다",
        subcommands = {InstallCommand.class, SyncCommand.class, DoctorCommand.class, ListCommand.class, UninstallCommand.class})
public class KitCommand implements Runnable {

    @Option(names = "--home", hidden = true, description = "홈 폴더 대신 쓸 경로 (테스트용)")
    public String home;

    @Option(names = "--repo", description = "키트 레포 주소 (기본: 팀 레포, 환경 변수 KIT_REPO)")
    public String repo;

    @Override
    public void run() {
        new picocli.CommandLine(this).usage(System.out);
    }
}
