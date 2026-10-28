#!/usr/bin/env python3
"""Elasticsearch snapshot CronJob helper (no external Python packages)."""
import base64
import datetime
import json
import os
import random
import sys
import urllib.error
import urllib.request

BASE = os.getenv("ELASTICSEARCH_URL", "http://elasticsearch:9200").rstrip("/")
REPO = os.getenv("ES_SNAPSHOT_REPO", "aap_local")
LOCATION = os.getenv("ES_SNAPSHOT_LOCATION", "/usr/share/elasticsearch/snapshots")
KEEP = max(1, int(os.getenv("ES_SNAPSHOT_KEEP", "14")))
PREFIX = os.getenv("ES_SNAPSHOT_PREFIX", "aap-knowledge-")


def headers():
    out = {"Content-Type": "application/json"}
    api_key = os.getenv("ELASTICSEARCH_API_KEY", "").strip()
    if api_key:
        out["Authorization"] = "ApiKey " + api_key
        return out
    username = os.getenv("ELASTICSEARCH_USERNAME", "").strip()
    password = os.getenv("ELASTICSEARCH_PASSWORD", "")
    if username:
        token = base64.b64encode(f"{username}:{password}".encode()).decode()
        out["Authorization"] = "Basic " + token
    return out


def request(method, path, body=None):
    data = None if body is None else json.dumps(body).encode()
    req = urllib.request.Request(BASE + path, data=data, method=method, headers=headers())
    try:
        with urllib.request.urlopen(req, timeout=60) as response:
            return json.loads(response.read().decode() or "{}")
    except urllib.error.HTTPError as e:
        message = e.read().decode(errors="replace")[:500]
        raise RuntimeError(f"ES {method} {path} -> HTTP {e.code}: {message}") from e


def ensure_repo():
    try:
        request("GET", f"/_snapshot/{REPO}")
        return
    except RuntimeError as e:
        if "HTTP 404" not in str(e):
            raise
    request("PUT", f"/_snapshot/{REPO}", {
        "type": "fs",
        "settings": {"location": LOCATION, "compress": True},
    })
    print(f"registered snapshot repository {REPO} -> {LOCATION}")


def main():
    ensure_repo()
    stamp = datetime.datetime.now(datetime.timezone.utc).strftime("%Y%m%d-%H%M%S")
    name = f"{PREFIX}{stamp}-{random.randint(1000, 9999)}"
    result = request("PUT", f"/_snapshot/{REPO}/{name}?wait_for_completion=true", {
        "indices": "aap_knowledge_chunks_*",
        "ignore_unavailable": True,
        "include_global_state": False,
    })
    state = result.get("snapshot", {}).get("state")
    print(f"snapshot={name} state={state} indices={len(result.get('snapshot', {}).get('indices', []))}")
    if state != "SUCCESS":
        raise SystemExit(1)

    all_snaps = request("GET", f"/_snapshot/{REPO}/_all").get("snapshots", [])
    rows = [
        s for s in all_snaps
        if s.get("snapshot", "").startswith(PREFIX) and s.get("state") == "SUCCESS"
    ]
    rows.sort(key=lambda s: s.get("start_time_in_millis", 0), reverse=True)
    for old in rows[KEEP:]:
        request("DELETE", f"/_snapshot/{REPO}/{old['snapshot']}")
        print(f"pruned snapshot={old['snapshot']}")


if __name__ == "__main__":
    try:
        main()
    except Exception as exc:
        print(f"snapshot job failed: {exc}", file=sys.stderr)
        raise
