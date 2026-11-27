#!/usr/bin/env python3
"""dist/kit.jar 가 지금 소스로 빌드한 jar와 같은지 확인한다 (배포 jar를 다시 빌드하지 않고 커밋하는 실수 방지).

사용: python3 tools/check_dist.py [--build] [--tag v0.7.0]
  --build  cli 에서 fatJar 를 먼저 빌드한다 (CI)
  --tag    jar 의 Implementation-Version 이 태그와 같은지도 본다
파일 시각은 무시하고 항목 이름과 CRC(내용 지문)만 비교한다.
"""
import argparse
import glob
import os
import re
import subprocess
import sys
import zipfile

ROOT = os.path.dirname(os.path.dirname(os.path.abspath(__file__)))


def entries(path):
    with zipfile.ZipFile(path) as z:
        return {i.filename: i.CRC for i in z.infolist() if not i.filename.endswith("/")}


def manifest_version(path):
    with zipfile.ZipFile(path) as z:
        m = re.search(r"^Implementation-Version:\s*(\S+)", z.read("META-INF/MANIFEST.MF").decode(), re.M)
        return m.group(1) if m else None


def main():
    p = argparse.ArgumentParser()
    p.add_argument("--build", action="store_true")
    p.add_argument("--tag")
    a = p.parse_args()
    dist = os.path.join(ROOT, "dist", "kit.jar")
    if not os.path.exists(dist):
        sys.exit("dist/kit.jar 가 없습니다. tools/release.sh 로 만드세요")
    cli = os.path.join(ROOT, "cli")
    if a.build:
        for f in glob.glob(os.path.join(cli, "build", "libs", "kit-*-all.jar")):
            os.remove(f)
        subprocess.run(["sh", "gradlew", "--quiet", "fatJar"], cwd=cli, check=True)
    built = sorted(glob.glob(os.path.join(cli, "build", "libs", "kit-*-all.jar")), key=os.path.getmtime)
    if not built:
        sys.exit("빌드한 jar가 없습니다 (--build 를 붙이세요)")
    want, got = entries(built[-1]), entries(dist)
    diff = sorted(k for k in set(want) | set(got) if want.get(k) != got.get(k))
    problems = []
    if diff:
        problems.append(f"dist/kit.jar 가 현재 소스와 다릅니다 ({len(diff)}개 항목, 예: {', '.join(diff[:3])}). tools/release.sh 로 다시 만드세요")
    ver = manifest_version(dist)
    if a.tag and ver != a.tag.lstrip("v"):
        problems.append(f"dist/kit.jar 버전 {ver} 이 태그 {a.tag} 와 다릅니다")
    for msg in problems:
        print("FAIL", msg)
    if problems:
        sys.exit(1)
    print(f"OK dist/kit.jar = 현재 소스 (버전 {ver}, 항목 {len(got)}개)")


if __name__ == "__main__":
    main()
