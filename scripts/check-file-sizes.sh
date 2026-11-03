#!/usr/bin/env bash
set -euo pipefail
# 文件行数守卫：新文件/继续增长的生产代码、测试与前端组件单文件上限 1600 行。
# 存量超限文件冻结在 scripts/file-size-baseline.txt，只允许缩小且不得超过 1600；拆分后删除基线行。

cd "$(dirname "$0")/.."

LIMIT="${FILE_SIZE_LIMIT:-1600}"
BASELINE="${FILE_SIZE_BASELINE:-scripts/file-size-baseline.txt}"
baseline_count() {
  [[ -f "$BASELINE" ]] || return 0
  awk -v file="$1" '$2 == file { print $1; exit }' "$BASELINE"
}

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

echo "checking ${#files[@]} source files; limit=${LIMIT}; baseline=${BASELINE}"
bad=0
for file in "${files[@]}"; do
  lines=$(wc -l < "$file" | tr -d ' ')
  if [ "$lines" -le "$LIMIT" ]; then
    continue
  fi
  allowed="$(baseline_count "$file")"
  if [ -n "$allowed" ] && [ "$allowed" -le "$LIMIT" ] && [ "$lines" -le "$allowed" ]; then
    echo "GRANDFATHERED | $file | $lines <= baseline $allowed"
  else
    echo "FAIL | $file | $lines > ${allowed:-$LIMIT} (new/grown file)" >&2
    bad=1
  fi
done
if [ "$bad" -ne 0 ]; then
  echo "file size guard FAILED: split above-limit files or lower them; do not grow baseline files" >&2
  exit 1
fi
echo "file size guard OK (hard limit ${LIMIT}; baseline only shrinks)"
