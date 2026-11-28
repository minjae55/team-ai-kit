package kit;

import kit.command.DoctorCommand;
import kit.command.InstallCommand;
import kit.command.ListCommand;
import kit.command.SkillCommand;
import kit.command.SyncCommand;
import kit.command.UiCommand;
import kit.command.UninstallCommand;
import picocli.CommandLine.Command;
import picocli.CommandLine.Option;

@Command(name = "kit",
        mixinStandardHelpOptions = true,
        version = "kit 0.5.0",
        description = "팀 AI 키트: 팀 스킬과 공통 지식을 AI 도구(Claude Code, Codex, Gemini CLI)에 설치하고 최신으로 유지한다. 웹 AI용 묶음도 만든다(kit export)",
        subcommands = {UiCommand.class, InstallCommand.class, SyncCommand.class, DoctorCommand.class, ListCommand.class,
                SkillCommand.class, kit.command.AutoCommand.class, kit.command.PinCommands.Pin.class,
                kit.command.PinCommands.Unpin.class, kit.command.PinCommands.Rollback.class,
                kit.command.TrackCommand.class, kit.command.ExportCommand.class, kit.command.InitCommand.class, UninstallCommand.class})
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
