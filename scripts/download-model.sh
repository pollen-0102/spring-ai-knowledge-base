#!/usr/bin/env bash
# Download the local ONNX embedding model (bge-small-zh-v1.5, 512 dims).
# The model is not committed to git; run this once after cloning.
set -euo pipefail

ROOT="$(cd "$(dirname "$0")/.." && pwd)"
DIR="$ROOT/models/bge-small-zh"
BASE="https://hf-mirror.com/Xenova/bge-small-zh-v1.5/resolve/main"

mkdir -p "$DIR"

echo "Downloading model.onnx (~91MB)..."
curl -L --fail --retry 3 -o "$DIR/model.onnx" "$BASE/onnx/model.onnx"

echo "Downloading tokenizer.json..."
curl -L --fail --retry 3 -o "$DIR/tokenizer.json" "$BASE/tokenizer.json"

echo "Downloading config.json..."
curl -L --fail --retry 3 -o "$DIR/config.json" "$BASE/config.json"

echo
echo "Done:"
ls -lh "$DIR"
