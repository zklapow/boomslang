#!/usr/bin/env bash
set -euo pipefail

OUT_DIR="${1:-build/joel-demo}"
BASE_URL="https://joelseverin.github.io/linux-wasm"

mkdir -p "$OUT_DIR"

for name in vmlinux.wasm initramfs.cpio.gz linux.js linux-worker.js; do
  url="$BASE_URL/$name"
  out="$OUT_DIR/$name"
  if [[ -f "$out" ]]; then
    echo "[fetch-joel-demo] exists $out" >&2
  else
    echo "[fetch-joel-demo] downloading $url" >&2
    curl -L --fail --show-error --output "$out" "$url"
  fi
done

shasum -a 256 \
  "$OUT_DIR/vmlinux.wasm" \
  "$OUT_DIR/initramfs.cpio.gz" \
  "$OUT_DIR/linux.js" \
  "$OUT_DIR/linux-worker.js" \
  > "$OUT_DIR/SHA256SUMS"
