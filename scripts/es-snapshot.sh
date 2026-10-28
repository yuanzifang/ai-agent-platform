#!/usr/bin/env bash
# Elasticsearch 本地快照/恢复演练。默认使用 aap-es-local 的 aap-es-snapshots 卷。
set -euo pipefail

BASE="${ELASTICSEARCH_URL:-http://127.0.0.1:9200}"
REPO="${ES_SNAPSHOT_REPO:-aap_local}"
SOURCE_INDEX="${ES_SNAPSHOT_SOURCE:-aap_knowledge_chunks_v1}"
RESTORE_INDEX="${ES_SNAPSHOT_RESTORE_INDEX:-aap_knowledge_chunks_restore_test}"

json() { curl -fsS -H 'Content-Type: application/json' "$@"; }

ensure_repo() {
  local code
  code=$(curl -s -o /tmp/aap-es-repo.json -w '%{http_code}' "$BASE/_snapshot/$REPO")
  if [ "$code" = "200" ]; then
    return 0
  fi
  json -X PUT "$BASE/_snapshot/$REPO" \
    -d '{"type":"fs","settings":{"location":"/usr/share/elasticsearch/snapshots","compress":true}}' >/dev/null
  echo "已注册快照仓库 $REPO -> /usr/share/elasticsearch/snapshots"
}

list_snapshots() {
  ensure_repo
  json "$BASE/_snapshot/$REPO/_all" | python3 -c '
import json,sys
d=json.load(sys.stdin)
for s in d.get("snapshots", []):
    print(s.get("snapshot"), s.get("state"), s.get("start_time_in_millis", 0),
          len(s.get("indices", [])), "indices", sep="\t")
' 
}

create_snapshot() {
  ensure_repo
  local name="${1:-aap-knowledge-$(date +%Y%m%d-%H%M%S)}"
  json -X PUT "$BASE/_snapshot/$REPO/$name?wait_for_completion=true" \
    -d '{"indices":"aap_knowledge_chunks_*","ignore_unavailable":true,"include_global_state":false}' \
    >/tmp/aap-es-snapshot-create.json
  python3 - "$name" <<'PY'
import json,sys
name=sys.argv[1]
d=json.load(open('/tmp/aap-es-snapshot-create.json'))
s=d.get('snapshot',{})
print(f"snapshot={name} state={s.get('state')} indices={len(s.get('indices',[]))}")
if s.get('state') != 'SUCCESS':
    raise SystemExit(1)
PY
}

prune_snapshots() {
  local keep="${1:-14}"
  ensure_repo
  local names
  names=$(json "$BASE/_snapshot/$REPO/_all" | python3 -c '
import json,sys
keep=int(sys.argv[1])
d=json.load(sys.stdin)
rows=[s for s in d.get("snapshots", [])
      if s.get("snapshot","").startswith("aap-knowledge-") and s.get("state")=="SUCCESS"]
rows.sort(key=lambda s: s.get("start_time_in_millis",0), reverse=True)
for s in rows[keep:]:
    print(s["snapshot"])
' "$keep")
  if [ -z "$names" ]; then
    echo "没有需要清理的快照（保留最近 ${keep} 个）"
    return 0
  fi
  while IFS= read -r name; do
    [ -z "$name" ] && continue
    json -X DELETE "$BASE/_snapshot/$REPO/$name" >/dev/null
    echo "已删除快照 $name"
  done <<< "$names"
}

restore_drill() {
  local name="${1:?用法: $0 restore-drill <snapshot>}"
  ensure_repo
  echo "恢复 ${SOURCE_INDEX} -> ${RESTORE_INDEX}（仅演练，不触碰线上索引）"
  curl -s -X DELETE "$BASE/$RESTORE_INDEX" >/dev/null 2>&1 || true
  json -X POST "$BASE/_snapshot/$REPO/$name/_restore?wait_for_completion=true" \
    -d "{\"indices\":\"$SOURCE_INDEX\",\"ignore_unavailable\":true,\"include_global_state\":false,\"rename_pattern\":\"$SOURCE_INDEX\",\"rename_replacement\":\"$RESTORE_INDEX\"}" \
    >/tmp/aap-es-restore.json
  local count
  count=$(json "$BASE/$RESTORE_INDEX/_count" | python3 -c 'import json,sys; print(json.load(sys.stdin).get("count",0))')
  echo "restore_index=$RESTORE_INDEX count=$count"
  curl -s -X DELETE "$BASE/$RESTORE_INDEX" >/dev/null 2>&1 || true
  echo "恢复演练完成，临时索引已删除"
}

case "${1:-}" in
  setup) ensure_repo ;;
  create) create_snapshot "${2:-}" ;;
  list) list_snapshots ;;
  prune) prune_snapshots "${2:-14}" ;;
  restore-drill) restore_drill "${2:-}" ;;
  *)
    echo "用法: $0 setup|create [snapshot]|list|prune [keep]|restore-drill <snapshot>" >&2
    exit 2
    ;;
esac
