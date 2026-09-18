#!/usr/bin/env bash
# Package Magisk flashable zip for VolumeManager root module.
set -euo pipefail

ROOT_DIR="$(cd "$(dirname "$0")/.." && pwd)"
SRC="$ROOT_DIR/magisk"
OUT_DIR="$ROOT_DIR/out"
ZIP_NAME="volumemanager_root-v1.0.0.zip"
ZIP_PATH="$OUT_DIR/$ZIP_NAME"

mkdir -p "$OUT_DIR"
rm -f "$ZIP_PATH"

# Magisk expects unix newlines and executable scripts in the zip.
chmod +x "$SRC/service.sh" "$SRC/META-INF/com/google/android/update-binary"

(
  cd "$SRC"
  zip -r9 "$ZIP_PATH" \
    module.prop \
    service.sh \
    META-INF
)

echo "Magisk zip: $ZIP_PATH"
ls -la "$ZIP_PATH"
