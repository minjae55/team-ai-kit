#!/usr/bin/env bash
# team-ai-kit 처음 설치:
#   git clone git@github.com:minjae55/team-ai-kit.git ~/.kit/repo && ~/.kit/repo/install.sh
# 하는 일: kit 실행 파일을 ~/.kit/bin 에 넣고 → PATH 등록(물어봄) → kit install 실행
# 그다음부터는 아무 폴더에서 kit sync / kit doctor
set -euo pipefail

home="${KIT_HOME_DIR:-$HOME}"
repo="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)"
bin="$home/.kit/bin"

say() { printf '%s\n' "$*"; }
die() { printf '[오류] %s\n' "$*" >&2; exit 1; }

command -v git >/dev/null 2>&1 || die "git이 없습니다. git을 설치한 뒤 다시 실행하세요"
command -v java >/dev/null 2>&1 || die "Java가 없습니다. Java 8 이상을 설치한 뒤 다시 실행하세요"

say "1/3 kit 실행 파일 준비"
say "  현재 버전으로 빌드합니다 (처음엔 1~2분, 이후엔 몇 초). JDK 17이 없으면 Gradle이 자동으로 받습니다"
rm -f "$repo"/cli/build/libs/kit-*-all.jar
(cd "$repo/cli" && ./gradlew --quiet fatJar) || die "빌드 실패. 위 메시지를 확인하세요"
jar="$(ls "$repo"/cli/build/libs/kit-*-all.jar | sort -V | tail -1)"
mkdir -p "$bin"
cp "$jar" "$bin/kit.jar"
cp "$repo/cli/kit" "$bin/kit"
chmod +x "$bin/kit"
say "  $bin/kit"

say "2/3 PATH 등록"
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

say "3/3 kit install"
KIT_HOME_DIR="$home" "$bin/kit" install "$@"
