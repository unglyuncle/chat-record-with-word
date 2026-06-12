#!/usr/bin/env bash
# Downloads the prebuilt sherpa-onnx Android .aar into app/libs/.
# Usage:  bash scripts/fetch-sherpa-aar.sh
set -euo pipefail
VER=1.13.2
URL="https://github.com/k2-fsa/sherpa-onnx/releases/download/v${VER}/sherpa-onnx-${VER}.aar"
LIBS="$(cd "$(dirname "$0")/.." && pwd)/app/libs"
DEST="${LIBS}/sherpa-onnx-${VER}.aar"

mkdir -p "$LIBS"
echo "Downloading $URL"
if curl -fL -o "$DEST" "$URL"; then
  echo "Saved to $DEST"
else
  echo "下载失败。若该 release 未直接附带 .aar，请改用 README 方式 B" >&2
  exit 1
fi
