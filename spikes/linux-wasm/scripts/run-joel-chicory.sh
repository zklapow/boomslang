#!/usr/bin/env bash
set -euo pipefail

SCRIPT_DIR="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)"
REPO_ROOT="$(cd "$SCRIPT_DIR/../../.." && pwd)"
SPIKE_DIR="$REPO_ROOT/spikes/linux-wasm"
CLASS_DIR="$SPIKE_DIR/build/chicory/classes"
SOURCE="$SPIKE_DIR/chicory/JoelLinuxChicoryProbe.java"
STATIC_AOT_SOURCE="$SPIKE_DIR/chicory/JoelLinuxStaticAotModules.java"
DEFAULT_WASM="$SPIKE_DIR/build/joel-demo/vmlinux.stripped.wasm"
ORIGINAL_WASM="$SPIKE_DIR/build/joel-demo/vmlinux.wasm"
DEFAULT_INITRD="$SPIKE_DIR/build/joel-demo/initramfs.cpio.gz"
JOEL_USER_WASM_SHA256="3d0d16c37d4581390f58f29854419607b21aef216794ee5bea2278914544cf1d"

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

AOT_REQUESTED=false
previous_arg=""
for arg in "$@"; do
  if [[ "$previous_arg" == "--engine" && "$arg" == "aot" ]]; then
    AOT_REQUESTED=true
  fi
  previous_arg="$arg"
done

EXTRA_CP=""
if [[ "$AOT_REQUESTED" == "true" ]]; then
  AOT_CLASS_DIR="$REPO_ROOT/benchmarks/target/classes"
  if [[ ! -f "$AOT_CLASS_DIR/com/hubspot/boomslang/benchmarks/compiled/JoelLinuxWasmMachine.class" ||
    ! -f "$AOT_CLASS_DIR/com/hubspot/boomslang/benchmarks/compiled/JoelUserWasm.class" ]]; then
    echo "missing statically linked Linux/Wasm AOT benchmark classes" >&2
    echo "run: mvn -pl benchmarks -am -DskipTests -Dlinux.wasm.aot=true package" >&2
    exit 1
  fi

  EXTRA_CP=":$AOT_CLASS_DIR"
  STATIC_AOT_SOURCE="$CLASS_DIR/JoelLinuxStaticAotModules.java"
  cat > "$STATIC_AOT_SOURCE" <<EOF
import com.dylibso.chicory.runtime.Instance;
import com.dylibso.chicory.runtime.Machine;
import com.hubspot.boomslang.benchmarks.compiled.JoelLinuxWasmMachine;
import com.hubspot.boomslang.benchmarks.compiled.JoelUserWasm;
import java.util.Map;
import java.util.function.Function;

final class JoelLinuxStaticAotModules {

  private JoelLinuxStaticAotModules() {}

  static Function<Instance, Machine> vmlinuxMachineFactory() {
    return JoelLinuxWasmMachine::new;
  }

  static Map<String, JoelLinuxLinkedUserModule> userModulesBySha256() {
    JoelUserWasm userModule = new JoelUserWasm();
    return Map.of(
      "$JOEL_USER_WASM_SHA256",
      new JoelLinuxLinkedUserModule(
        userModule.wasmModule(),
        userModule.machineFactory()
      )
    );
  }
}
EOF
fi

javac -proc:none -cp "$CHICORY_CP$EXTRA_CP" -d "$CLASS_DIR" "$SOURCE" "$STATIC_AOT_SOURCE"

exec java -Xmx2g -cp "$CLASS_DIR:$CHICORY_CP$EXTRA_CP" JoelLinuxChicoryProbe \
  --wasm "$DEFAULT_WASM" \
  --initrd "$DEFAULT_INITRD" \
  "$@"
