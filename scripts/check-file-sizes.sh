#!/usr/bin/env bash
set -euo pipefail
# 文件行数守卫：生产代码、测试与前端组件单文件上限 2000 行。
# 超限先抽取/拆分，避免单体文件继续膨胀。

cd "$(dirname "$0")/.."

files=()
while IFS= read -r -d '' file; do
  files+=("$file")
done < <(find \
  backend/src/main/java \
  backend/src/test/java \
  frontend/src \
  frontend/e2e \
  -type f \( -name '*.java' -o -name '*.vue' -o -name '*.ts' \) -print0)

if [ "${#files[@]}" -eq 0 ]; then
  echo "no source files found" >&2
  exit 1
fi

echo "checking ${#files[@]} source files for >2000 lines"
if wc -l "${files[@]}" | awk '$2 != "total" && $1 > 2000 { print; bad=1 } END { exit bad }'; then
  echo "file size guard OK (limit: 2000 lines)"
else
  echo "file size guard FAILED: split files above 2000 lines" >&2
  exit 1
fi
