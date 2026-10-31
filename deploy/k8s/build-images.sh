#!/usr/bin/env bash
set -euo pipefail

ROOT="$(cd "$(dirname "${BASH_SOURCE[0]}")/../.." && pwd)"
TAG="${TAG:-0.1.0}"
MAVEN_BIN="${MAVEN_BIN:-mvn}"
NPM_BIN="${NPM_BIN:-npm}"
if ! command -v "$MAVEN_BIN" >/dev/null 2>&1 && [ -x "$HOME/tools/apache-maven-3.9.16/bin/mvn" ]; then
  MAVEN_BIN="$HOME/tools/apache-maven-3.9.16/bin/mvn"
fi

if ! command -v docker >/dev/null 2>&1; then
  echo "docker is required" >&2
  exit 1
fi

echo "==> build backend jar"
(cd "$ROOT/backend" && "$MAVEN_BIN" -q -DskipTests -Dmaven.test.skip=true package)

if [ ! -f "$ROOT/frontend/dist/index.html" ]; then
  echo "==> build frontend dist"
  (cd "$ROOT/frontend" && "$NPM_BIN" run build)
fi

echo "==> build backend image"
docker build -t "aap/backend:${TAG}" "$ROOT/backend"

echo "==> build frontend image"
docker build -t "aap/frontend:${TAG}" "$ROOT/frontend"

echo
echo "built:"
echo "  aap/backend:${TAG}"
echo "  aap/frontend:${TAG}"
echo
echo "For a k3d cluster:"
echo "  k3d image import aap/backend:${TAG} aap/frontend:${TAG} -c <cluster>"
