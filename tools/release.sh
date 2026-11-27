#!/usr/bin/env bash
# 배포 준비: kit을 빌드해 dist/kit.jar 에 넣는다. 팀원은 이 jar를 git으로 받아서 빌드 없이(Java만으로) 쓴다.
#   tools/release.sh 0.7.0
# 하는 일: CHANGELOG에 v0.7.0 절이 있는지 확인 → cli/build.gradle 버전 변경 → 테스트·빌드 → dist/kit.jar 교체 → 검사
# 커밋·태그·푸시는 하지 않는다. 마지막에 다음 명령을 보여 준다.
set -euo pipefail
cd "$(dirname "$0")/.."

ver="${1:-}"
ver="${ver#v}"
[[ "$ver" =~ ^[0-9]+\.[0-9]+\.[0-9]+$ ]] || { echo "사용: tools/release.sh 0.7.0" >&2; exit 1; }
grep -q "^## v$ver" CHANGELOG.md || { echo "CHANGELOG.md 맨 위에 '## v$ver' 절을 먼저 쓰세요" >&2; exit 1; }
if git rev-parse -q --verify "refs/tags/v$ver" >/dev/null; then echo "태그 v$ver 이 이미 있습니다" >&2; exit 1; fi

echo "1/4 버전 $ver 로 변경 (cli/build.gradle)"
sed -i.bak -E "s/^version = '.*'/version = '$ver'/" cli/build.gradle && rm -f cli/build.gradle.bak

echo "2/4 테스트·빌드"
rm -f cli/build/libs/kit-*-all.jar
(cd cli && sh gradlew --quiet test fatJar)

echo "3/4 dist/kit.jar 교체"
mkdir -p dist
cp "cli/build/libs/kit-$ver-all.jar" dist/kit.jar
python3 tools/check_dist.py

echo "4/4 스킬·eval 검사"
python3 tools/validate_skills.py | tail -1
python3 tools/eval/test_grader.py | tail -1

echo
echo "준비 끝. 확인 후:"
echo "  git add CHANGELOG.md cli/build.gradle dist/kit.jar <바꾼 파일들>"
echo "  git commit  (한글 Conventional Commits)"
echo "  git tag v$ver && git push origin main v$ver"
