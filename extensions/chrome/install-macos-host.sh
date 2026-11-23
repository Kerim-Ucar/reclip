#!/usr/bin/env bash
set -euo pipefail

if [ $# -ne 1 ]; then
  echo "Usage: $0 <chrome-extension-id>"
  exit 1
fi

EXT_ID="$1"
SCRIPT_DIR="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)"
HOST_SCRIPT="$SCRIPT_DIR/reclip-native-host.sh"
chmod +x "$HOST_SCRIPT"

TARGET_DIR="$HOME/Library/Application Support/Google/Chrome/NativeMessagingHosts"
mkdir -p "$TARGET_DIR"

cat > "$TARGET_DIR/com.kerim.reclip.json" <<EOF
{
  "name": "com.kerim.reclip",
  "description": "Reclip Native Messaging Host",
  "path": "$HOST_SCRIPT",
  "type": "stdio",
  "allowed_origins": [
    "chrome-extension://$EXT_ID/"
  ]
}
EOF

echo "Installed Native Messaging host manifest to $TARGET_DIR/com.kerim.reclip.json"
