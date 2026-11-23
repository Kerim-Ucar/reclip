#!/usr/bin/env bash
set -euo pipefail

SCRIPT_DIR="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)"
REPO_ROOT="$(cd "$SCRIPT_DIR/../.." && pwd)"

if [ -z "${JAVA_HOME:-}" ] && command -v /usr/libexec/java_home >/dev/null 2>&1; then
  JAVA_HOME="$(/usr/libexec/java_home -v 25 2>/dev/null || /usr/libexec/java_home 2>/dev/null || true)"
fi

JAVA_BIN="${JAVA_HOME:+$JAVA_HOME/bin/}java"
if [ ! -x "$JAVA_BIN" ]; then
  JAVA_BIN="java"
fi

exec "$JAVA_BIN" -cp "$REPO_ROOT/build/classes/java/main" com.kerim.reclip.ipc.NativeMessagingHost
