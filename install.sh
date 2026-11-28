#!/usr/bin/env bash
# team-ai-kit 처음 설치:
#   git clone https://github.com/minjae55/team-ai-kit.git ~/.kit/repo && ~/.kit/repo/install.sh
# 하는 일: kit 실행 파일 준비 → PATH 등록(물어봄) → kit install → 자동 업데이트·사용 기록 켜기(물어봄) → 점검 → kit ui 화면 열기
# KIT_NO_UI=1 이면 마지막에 화면을 열지 않는다. 그다음부터는 아무 폴더에서 kit ui / kit sync
set -euo pipefail

home="${KIT_HOME_DIR:-$HOME}"
# Windows(Git Bash)에서는 Java에 넘기는 홈 경로를 C:/Users/... 형식으로 바꾼다 (/c/Users/... 는 Java가 못 알아봄)
kit_home="$home"
case "$(uname -s 2>/dev/null)" in
  MINGW*|MSYS*|CYGWIN*) command -v cygpath >/dev/null 2>&1 && kit_home="$(cygpath -m "$home")" ;;
esac
repo="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)"
bin="$home/.kit/bin"

say() { printf '%s\n' "$*"; }
die() { printf '[오류] %s\n' "$*" >&2; exit 1; }

command -v git >/dev/null 2>&1 || die "git이 없습니다. git을 설치한 뒤 다시 실행하세요"
command -v java >/dev/null 2>&1 || die "Java가 없습니다. Java 8 이상을 설치한 뒤 다시 실행하세요"

say "1/5 kit 실행 파일 준비"
if [ -f "$repo/dist/kit.jar" ] && [ "${KIT_BUILD_FROM_SOURCE:-}" != "1" ]; then
  jar="$repo/dist/kit.jar"
  say "  빌드된 kit을 씁니다 (빌드 도구 불필요)"
else
  say "  소스에서 빌드합니다 (처음엔 1~2분, 이후엔 몇 초). JDK 17이 없으면 Gradle이 자동으로 받습니다"
  rm -f "$repo"/cli/build/libs/kit-*-all.jar
  (cd "$repo/cli" && sh gradlew --quiet fatJar) || die "빌드 실패. 위 메시지를 확인하세요"
  jar="$(ls "$repo"/cli/build/libs/kit-*-all.jar | sort -V | tail -1)"
fi
mkdir -p "$bin"
cp "$jar" "$bin/kit.jar"
cp "$repo/cli/kit" "$bin/kit"
chmod +x "$bin/kit"
case "$(uname -s 2>/dev/null)" in
  MINGW*|MSYS*|CYGWIN*)   # Windows: PowerShell·CMD 에서도 kit 을 쓰도록 (Java 만으로 실행, bash 불필요)
    printf '@echo off\r\njava -jar "%%~dp0kit.jar" %%*\r\n' > "$bin/kit.cmd" ;;
esac
say "  $bin/kit"

say "2/5 PATH 등록"
case ":$PATH:" in
  *":$bin:"*) say "  이미 등록되어 있습니다" ;;
  *)
    case "${SHELL:-}" in
      */zsh) rc="$home/.zshrc" ;;
      */bash) rc="$home/.bashrc" ;;
      *) rc="$home/.profile" ;;
    esac
    line='export PATH="$HOME/.kit/bin:$PATH"  # team-ai-kit'
    answer="n"
    if [ -r /dev/tty ] && [ -t 1 ]; then
      printf '  %s 에 다음 한 줄을 추가할까요? [y/N]\n    %s\n  > ' "$rc" "$line"
      read -r answer < /dev/tty || answer="n"
    fi
    if [ "$answer" = "y" ] || [ "$answer" = "Y" ]; then
      grep -qF '.kit/bin' "$rc" 2>/dev/null || printf '\n%s\n' "$line" >> "$rc"
      say "  추가했습니다. 새 터미널부터 kit 명령을 쓸 수 있습니다"
    else
      say "  건너뜀. 직접 추가하려면: echo '$line' >> $rc"
    fi
    ;;
esac
export PATH="$bin:$PATH"

say "3/5 kit install"
KIT_HOME_DIR="$kit_home" "$bin/kit" install "$@"

interactive=0
if [ -r /dev/tty ] && [ -t 1 ]; then interactive=1; fi

say "4/5 자동 업데이트·사용 기록"
if KIT_HOME_DIR="$kit_home" "$bin/kit" auto status 2>/dev/null | grep -q "켜짐"; then
  say "  이미 켜져 있습니다"
else
  answer="n"
  if [ "$interactive" = 1 ]; then
    printf '  Claude Code를 열 때 자동으로 최신 버전을 받고, 스킬 사용 횟수를 기록할까요? (~/.claude/settings.json 에 키트 훅 추가, 백업함) [Y/n]\n  > '
    read -r answer < /dev/tty || answer="n"
    [ -z "$answer" ] && answer="y"
  fi
  if [ "$answer" = "y" ] || [ "$answer" = "Y" ]; then
    KIT_HOME_DIR="$kit_home" "$bin/kit" auto on | sed 's/^/  /'
  else
    say "  건너뜀. 나중에 켜려면: kit auto on"
  fi
fi

say "5/5 점검"
KIT_HOME_DIR="$kit_home" "$bin/kit" doctor | sed 's/^/  /' || true

say ""
say "설치 끝. 새 Claude 세션부터 팀 스킬이 적용됩니다."
if [ "$interactive" = 1 ] && [ "${KIT_NO_UI:-}" != "1" ]; then
  say "kit 화면을 엽니다 (닫으려면 Ctrl+C, 다시 열려면 kit ui)"
  exec env KIT_HOME_DIR="$kit_home" "$bin/kit" ui
else
  say "화면 보기: kit ui"
fi
