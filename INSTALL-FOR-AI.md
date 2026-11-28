# AI가 대신 설치할 때 따를 안내

사용자가 "팀 AI 키트 설치해줘"처럼 부탁하면 이 순서대로 한다. 사용자의 PC 설정을 바꾸는 단계는 실행 전에 무엇이 바뀌는지 한 줄로 알리고 동의를 받는다.

1. 준비물 확인: `git --version`, `java -version` (Java 8 이상). 없으면 설치 방법을 안내하고 멈춘다 (사용자 대신 설치 프로그램을 내려받지 않는다).
2. 설치: `curl -fsSL https://raw.githubusercontent.com/minjae55/team-ai-kit/main/get.sh | KIT_NO_UI=1 bash`
   - 사람 없이 실행되므로 PATH 등록과 자동 업데이트 질문은 건너뛰어진다. 끝나면 결과의 점검 줄(오류·경고 수)을 사용자에게 알린다.
3. PATH 등록 (동의 후): 사용자의 셸 설정 파일(zsh는 `~/.zshrc`)에 `export PATH="$HOME/.kit/bin:$PATH"  # team-ai-kit` 한 줄 추가.
4. 자동 업데이트·사용 기록 (동의 후): `~/.kit/bin/kit auto on`. `~/.claude/settings.json`에 키트 훅을 추가한다(백업함).
5. 마무리: `~/.kit/bin/kit list`로 설치된 스킬을 보여 주고, 화면은 `kit ui`로 열 수 있다고 알린다. 새 AI 세션부터 스킬이 적용된다고 안내한다.
