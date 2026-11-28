# AI가 대신 설치할 때 따를 안내

사용자가 "팀 AI 키트 설치해줘"처럼 부탁하면 **설명만 하지 말고 이 순서대로 직접 실행한다.** 명령마다 사용자 승인이 필요하면 받는다.
사용자 PC 설정을 바꾸는 단계(PATH, 자동 업데이트)는 실행 전에 무엇이 바뀌는지 한 줄로 알리고 동의를 받는다.
설치 프로그램(git, Java)을 사용자 대신 내려받지 않는다. 없으면 설치 방법을 안내하고 멈춘다.

## 먼저: 운영체제 확인
- Windows면 아래 "Windows" 순서, macOS·Linux면 "macOS·Linux" 순서를 따른다.

## macOS·Linux
1. 준비물 확인: `git --version`, `java -version` (Java 8 이상)
2. 설치: `curl -fsSL https://raw.githubusercontent.com/minjae55/team-ai-kit/main/get.sh | KIT_NO_UI=1 bash`
   - 사람 없이 실행되므로 PATH와 자동 업데이트 질문은 건너뛴다. 끝나면 결과의 점검 줄(오류·경고 수)을 알린다
3. PATH 등록 (동의 후): 셸 설정 파일(zsh는 `~/.zshrc`, bash는 `~/.bashrc`)에 `export PATH="$HOME/.kit/bin:$PATH"  # team-ai-kit` 한 줄 추가
4. 자동 업데이트·사용 기록 (동의 후): `~/.kit/bin/kit auto on` (`~/.claude/settings.json`에 키트 훅 추가, 백업함)
5. 마무리: `~/.kit/bin/kit list`로 설치된 스킬을 보여 주고, 화면은 `kit ui`로 연다고 알린다

## Windows
설치 스크립트가 bash 용이라 PowerShell에서 Git Bash를 찾아 대신 실행하는 `get.ps1`을 쓴다.
1. 준비물 확인 (PowerShell): `git --version`, `java -version`. git이 없으면 Git for Windows(https://git-scm.com/download/win), Java가 없으면 https://adoptium.net 안내 후 멈춘다
2. 설치 (PowerShell): `$env:KIT_NO_UI = "1"; irm https://raw.githubusercontent.com/minjae55/team-ai-kit/main/get.ps1 | iex`
   - 결과의 점검 줄(오류·경고 수)을 알린다. Windows는 스킬을 링크 대신 복사로 설치한다(정상)
3. PATH 등록 (동의 후, PowerShell): `[Environment]::SetEnvironmentVariable("Path", [Environment]::GetEnvironmentVariable("Path","User") + ";$env:USERPROFILE\.kit\bin", "User")`
   - 새 터미널부터 PowerShell·CMD에서 `kit` 명령을 쓸 수 있다 (`kit.cmd`)
4. 자동 업데이트·사용 기록 (동의 후): `& "$env:USERPROFILE\.kit\bin\kit.cmd" auto on`
5. 마무리: `& "$env:USERPROFILE\.kit\bin\kit.cmd" list`로 설치된 스킬을 보여 주고, 화면은 `kit ui`로 연다고 알린다

## 공통
- 새 AI 세션부터 스킬이 적용된다고 안내한다
- 설치 중 오류가 나면 오류 줄을 그대로 보여 주고, 추측으로 다른 저장소나 다른 도구를 설치하지 않는다
