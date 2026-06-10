#!/usr/bin/env bash
set -euo pipefail

SCRIPT_DIR="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)"
SPIKE_DIR="$(cd "${SCRIPT_DIR}/.." && pwd)"

BUILD_DIR="${1:-${SPIKE_DIR}/build}"
SRC_DIR="${BUILD_DIR}/src/mini-rv32ima"
OUT_DIR="${BUILD_DIR}/out"
OUT="${OUT_DIR}/mini-rv32ima.wasm"

if [ ! -d "${SRC_DIR}" ]; then
  "${SPIKE_DIR}/scripts/fetch-mini-rv32ima.sh" "${BUILD_DIR}" >/dev/null
fi

if [ -z "${WASI_SDK_PATH:-}" ]; then
  echo "WASI_SDK_PATH is not set. Run through the repo dev shell:" >&2
  echo "  nix develop -c make -C spikes/linux-wasm wasm" >&2
  exit 1
fi

CC="${WASI_SDK_PATH}/bin/clang"
if [ ! -x "${CC}" ]; then
  echo "WASI clang not found at ${CC}" >&2
  exit 1
fi

mkdir -p "${OUT_DIR}"

"${CC}" \
  --target=wasm32-wasip1 \
  -Os \
  -D_WASI_EMULATED_PROCESS_CLOCKS \
  -Wno-unused-function \
  -Wl,--stack-first \
  -Wl,-z,stack-size=1048576 \
  -Wl,--initial-memory=134217728 \
  -o "${OUT}" \
  "${SRC_DIR}/mini-rv32ima/mini-rv32ima.c" \
  -lwasi-emulated-process-clocks

if command -v wasm-strip >/dev/null 2>&1; then
  wasm-strip "${OUT}"
fi

ls -lh "${OUT}"
