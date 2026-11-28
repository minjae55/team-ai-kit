#!/usr/bin/env bash
# team-ai-kit 한 줄 설치:
#   curl -fsSL https://raw.githubusercontent.com/minjae55/team-ai-kit/main/get.sh | bash
# 키트 레포를 ~/.kit/repo 에 받고(이미 있으면 최신으로) install.sh 를 실행한다.
set -euo pipefail
repo_url="${KIT_REPO:-https://github.com/minjae55/team-ai-kit.git}"
dir="${KIT_HOME_DIR:-$HOME}/.kit/repo"

command -v git >/dev/null 2>&1 || { echo "[오류] git이 필요합니다. macOS는 터미널에서 'xcode-select --install', 그 외는 git을 설치한 뒤 다시 실행하세요" >&2; exit 1; }
if ! command -v java >/dev/null 2>&1 || ! java -version >/dev/null 2>&1; then
  echo "[오류] Java 8 이상이 필요합니다. https://adoptium.net 에서 설치한 뒤 다시 실행하세요" >&2; exit 1
fi

if [ -d "$dir/.git" ]; then
  echo "키트 레포가 이미 있습니다. 최신으로 받습니다: $dir"
  git -C "$dir" fetch --quiet --tags origin
else
  echo "키트 레포 받기: $repo_url → $dir"
  mkdir -p "$(dirname "$dir")"
  git clone --quiet "$repo_url" "$dir"
fi
exec bash "$dir/install.sh" "$@"
