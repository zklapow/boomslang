#!/usr/bin/env bash
set -euo pipefail

SCRIPT_DIR="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)"
REPO_ROOT="$(cd "$SCRIPT_DIR/../../.." && pwd)"
SPIKE_DIR="$REPO_ROOT/spikes/linux-wasm"
CLASS_DIR="$SPIKE_DIR/build/chicory/classes"
SOURCE="$SPIKE_DIR/chicory/JoelLinuxChicoryProbe.java"
DEFAULT_WASM="$SPIKE_DIR/build/joel-demo/vmlinux.stripped.wasm"
ORIGINAL_WASM="$SPIKE_DIR/build/joel-demo/vmlinux.wasm"
DEFAULT_INITRD="$SPIKE_DIR/build/joel-demo/initramfs.cpio.gz"

if [[ ! -f "$DEFAULT_WASM" && -f "$ORIGINAL_WASM" ]]; then
  if command -v wasm-strip >/dev/null 2>&1; then
    echo "[joel-chicory] stripping $ORIGINAL_WASM -> $DEFAULT_WASM" >&2
    wasm-strip "$ORIGINAL_WASM" -o "$DEFAULT_WASM"
  else
    DEFAULT_WASM="$ORIGINAL_WASM"
  fi
fi

if [[ ! -f "$DEFAULT_WASM" ]]; then
  echo "missing $DEFAULT_WASM" >&2
  echo "fetch the deployed Joel demo artifacts first" >&2
  exit 1
fi

if [[ ! -f "$DEFAULT_INITRD" ]]; then
  echo "missing $DEFAULT_INITRD" >&2
  echo "fetch the deployed Joel demo artifacts first" >&2
  exit 1
fi

CHICORY_CP="$(find "$HOME/.m2/repository/com/dylibso/chicory" -type f -name '*.jar' | sort | tr '\n' ':')"
if [[ -z "$CHICORY_CP" ]]; then
  echo "missing Chicory jars in ~/.m2; run mvn -q -DskipTests compile from the repo root" >&2
  exit 1
fi

mkdir -p "$CLASS_DIR"
javac -proc:none -cp "$CHICORY_CP" -d "$CLASS_DIR" "$SOURCE"

exec java -Xmx2g -cp "$CLASS_DIR:$CHICORY_CP" JoelLinuxChicoryProbe \
  --wasm "$DEFAULT_WASM" \
  --initrd "$DEFAULT_INITRD" \
  "$@"
