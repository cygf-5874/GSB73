#!/usr/bin/env bash
# 跑既有用例 src/test/java/prefixidx/PrefixIndexTest.java。
# 起点应 13/13 全红（实现是空壳，一律 UnsupportedOperationException）。
set -euo pipefail

cd "$(dirname "$0")/.."

bash scripts/build.sh >/dev/null

java -Dfile.encoding=UTF-8 -Dsun.stdout.encoding=UTF-8 -Dsun.stderr.encoding=UTF-8 \
    -cp out prefixidx.PrefixIndexTest
