# Linux-on-WASM spike

This spike tries to get a small Linux system into a Boomslang-shaped WASM artifact without starting with a Linux `ARCH=wasm` port.

## Best target

Use `joelseverin/linux-wasm` as the primary research target for a direct Linux/Wasm port, use `container2wasm` as the practical "small distro/container to Wasm" track, and keep `mini-rv32ima` compiled to `wasm32-wasip1` as the baseline/control path.

The broader research pass is in [research.md](research.md). Short version: there are two distinct good targets, depending on whether we optimize for "Linux kernel compiled to Wasm" or "usable Linux environment packaged as Wasm."

The direct path is:

```text
browser/runtime glue
  -> Linux kernel built for wasm32/wasm64 NOMMU
     -> musl + BusyBox built as Wasm userspace
        -> initramfs
```

That project is not emulating RISC-V or x86. It adds WebAssembly architecture support to Linux, patches LLVM/musl/BusyBox, and uses a runtime that starts Wasm instances/workers for execution.

The control path is:

```text
host runtime
  -> mini-rv32ima.wasm      # tiny RISC-V machine emulator
     -> Linux RV32 NOMMU    # normal kernel image
        -> BusyBox/initramfs
```

The direct path is more aligned with the original goal: compile a Linux kernel and tiny distribution to Wasm. The control path is still useful because it already gives us a small, reproducible Linux boot in a WASM module and can validate packaging/runtime assumptions independently.

The practical distro path is:

```text
WASI/browser runtime
  -> container2wasm output   # emulator + kernel/rootfs/container payload
     -> OCI image userspace  # BusyBox/Alpine/Debian/etc.
```

This is not a direct Linux/Wasm kernel port, but it is the strongest quick path to a distro-shaped `.wasm` artifact.

## Current control artifact

```text
host runtime
  -> mini-rv32ima.wasm      # tiny RISC-V machine emulator
     -> Linux RV32 NOMMU    # normal kernel image
        -> BusyBox/initramfs
```

This keeps one branch of the WASM work scoped to a small C emulator while the kernel and root filesystem remain standard RISC-V/Buildroot outputs.

## Current Chicory proof

The direct Joel path now has a minimal JVM/Chicory host:

```text
Chicory/JVM host
  -> Joel's deployed vmlinux.wasm
     -> shared imported memory
     -> cooperative Java runners for kernel tasks/CPUs
     -> BusyBox Wasm userland from initramfs
```

The host lives in [chicory/JoelLinuxChicoryProbe.java](chicory/JoelLinuxChicoryProbe.java). It imports Joel's shared `env.memory`, stubs the kernel's `sys_*` imports as `-ENOSYS` the way Joel's JS runtime does, implements the Linux/Wasm host callbacks, starts task runners as separate Chicory instances sharing the same memory, and instantiates BusyBox user executables with syscall trampolines back into the task's kernel instance.

The current proof boots Linux, unpacks the initramfs, runs `/init`, reaches the BusyBox shell prompt, feeds scripted console input, and gets guest output:

```text
Welcome to Linux running on Wasm!
~ # echo chicory-linux-ok
chicory-linux-ok
~ # uname -a
Linux (none) 6.4.16-00012-gf3e782cb608b #26 SMP Fri Oct 31 16:44:59 CET 2025 wasm GNU/Linux
```

This is still a spike host, not a finished product runtime. The most visible remaining gap is console/timer polish: after scripted input is consumed, the guest keeps polling `hvc_get` and the verification command exits via `timeout`.

## Why this target

- `joelseverin/linux-wasm` proves a direct Linux/Wasm architecture path exists. It currently looks like the right ambitious target to study and possibly vendor/pin for a Boomslang-like runtime experiment.
- Tom B.'s independent Linux/Wasm port is required comparison material; Joel's repo explicitly calls it out and says later work combines ideas from both ports.
- The direct port is browser/runtime-glue oriented today: it uses Web Workers and host scheduling behavior to work around Wasm task suspension limits. That matters if we want a Chicory/JVM host later.
- The upstream kernel still has no comparable upstreamed `ARCH=wasm` target, so the direct path means carrying experimental patches for Linux, LLVM/lld, musl, BusyBox, and runtime glue.
- `container2wasm` gives us the fastest complete Linux environment artifact: standard OCI image in, Wasm image out, running under WASI runtimes or in the browser through emulation.
- `mini-rv32ima` is intentionally small, embeddable C, and already boots Linux with RV32 NOMMU Buildroot configs.
- RV32 NOMMU avoids a full PC platform and MMU/device model while still being a real Linux kernel path. It remains the fastest sanity check and a fallback if direct Linux/Wasm runtime assumptions do not fit Boomslang.

## Alternatives considered

- `joelseverin/linux-wasm`: now promoted from alternative to primary direct target. It compiles Linux, musl, and BusyBox toward Wasm with a patched toolchain and JS runtime.
- `tombl/linux`: independent direct Linux/Wasm port, now treated as required prior art rather than an optional alternative.
- `container2wasm`: now promoted to the practical distro/container track. It converts OCI images into Wasm using emulator backends and can run under WASI runtimes and in the browser.
- `ktock/qemu-wasm`: patched QEMU built for the browser with Emscripten. Strong compatibility fallback, larger than needed for first artifact.
- `v86`: proven x86 Linux in the browser, but it is a browser-oriented emulator with JS integration and runtime x86-to-WASM JIT behavior. It is less clean as a standalone WASI module for Chicory.
- TinyEMU/JSLinux: proven RISC-V and x86 Linux boot path with remote block devices. It is the fallback if `mini-rv32ima` hits hard device or filesystem limits, but it is larger than needed for a first boot proof.
- WebVM/CheerpX: excellent x86 Linux-in-browser UX, but CheerpX licensing/self-hosting constraints make it a reference, not a base dependency.
- LKL: Linux kernel as a library. Interesting for Linux services/syscalls in Wasm, but not a normal distro boot target.
- Blink/Firebox Blink: promising Linux ABI/ELF compatibility path, especially the `wasm32-wasi-threads` fork, but not a kernel or distro boot path.
- WASIX, WALI, Browsix/Browsix-Wasm: useful POSIX/Linux-interface references, not primary targets.
- BusyBox/toybox compiled directly to WASI: useful for a POSIX-like tool bundle, but it is not Linux.
- Linux `ARCH=wasm` from scratch: no longer the right starting point because `joelseverin/linux-wasm` and Tom B. have prior art to build on.

## What is implemented here

- A pinned fetch script for `cnlohr/mini-rv32ima`.
- A tiny WASI platform patch that bypasses `termios`/`ioctl` and keeps UART output working.
- A native boot smoke that fetches the upstream prebuilt RV32 NOMMU Linux image and checks for the Buildroot login prompt.
- A WASI build script that produces `build/out/mini-rv32ima.wasm`.
- A fetch script for Joel's deployed demo artifacts: `vmlinux.wasm`, `initramfs.cpio.gz`, `linux.js`, and `linux-worker.js`.
- A standalone Chicory host for Joel's deployed Linux/Wasm artifact.
- Minimal host support for Joel's task switching, secondary CPU startup, user executable loading, exec reloads, and scripted hvc console input.

Not yet implemented here:

- A pinned fetch/build wrapper for `container2wasm`.
- A local build of `joelseverin/linux-wasm`'s patched LLVM + Linux + musl + BusyBox stack.
- A polished interactive console or timer model for the Chicory host.

## Commands

From the repository root:

```bash
make -C spikes/linux-wasm fetch
make -C spikes/linux-wasm native-smoke
nix develop -c make -C spikes/linux-wasm wasm
nix develop -c make -C spikes/linux-wasm fetch-joel-demo
nix develop -c make -C spikes/linux-wasm joel-chicory-scripted
```

The WASI build writes:

```text
spikes/linux-wasm/build/out/mini-rv32ima.wasm
```

If a WASI runner is installed, the expected execution shape is:

```bash
wasmtime --dir "$(pwd)/spikes/linux-wasm/build/images::/images" \
  spikes/linux-wasm/build/out/mini-rv32ima.wasm -- \
  -f /images/Image -k "console=ttyS0"
```

The Joel/Chicory proof command expands to:

```bash
nix develop -c timeout 60s ./spikes/linux-wasm/scripts/run-joel-chicory.sh \
  --cmdline 'nosmp nr_cpus=1 maxcpus=1 root=/dev/ram0 rootfstype=ramfs init=/init console=hvc console=ttyS0' \
  --stdin-text "$(printf 'echo chicory-linux-ok\nuname -a\n')"
```

The JMH benchmarks for Linux/Wasm scripted boot live in
`benchmarks/src/main/java/com/hubspot/boomslang/benchmarks/LinuxWasmScriptBenchmark.java`.
After packaging the benchmark jar, the default artifact paths work if
`fetch-joel-demo` has populated `spikes/linux-wasm/build/joel-demo`:

```bash
mvn -pl benchmarks -am -DskipTests package
java -jar benchmarks/target/benchmarks.jar LinuxWasmScriptBenchmark
```

To benchmark a Maven-plugin-style AOT kernel machine, build the benchmark module
with the Linux/Wasm AOT profile and run the JMH parameter with `engine=aot`:

```bash
mvn -pl benchmarks -am -DskipTests -Dlinux.wasm.aot=true package
java -jar benchmarks/target/benchmarks.jar LinuxWasmScriptBenchmark.bootAndEcho -p engine=aot
```

This currently AOT-compiles `vmlinux` only. BusyBox/user executables are loaded
from the initramfs at guest runtime, so they remain interpreted unless separately
dumped, pinned, and compiled.

For out-of-tree or source-built artifacts, prefer environment variables because
JMH forks benchmark JVMs:

```bash
LINUX_WASM_BENCH_WASM=/path/to/vmlinux.stripped.wasm \
LINUX_WASM_BENCH_INITRD=/path/to/initramfs.cpio.gz \
java -jar benchmarks/target/benchmarks.jar LinuxWasmScriptBenchmark.bootAndEcho
```

## Verification so far

Done locally:

- Fetched pinned `mini-rv32ima` source at `84858f58cb41899705e2ff2d6ee3b2d5c0795bfe`.
- Built the native emulator on macOS.
- Booted the upstream `linux-6.1.14-rv32nommu-cnl-1` image to `Welcome to Buildroot` and `buildroot login:`.
- Compiled the patched emulator to `wasm32-wasip1`; the stripped artifact is 38 KB and imports `wasi_snapshot_preview1`.
- Downloaded Joel's deployed `vmlinux.wasm` and `initramfs.cpio.gz` artifacts.
- Stripped Joel's `vmlinux.wasm` for faster local parsing.
- Ran Joel's Linux/Wasm kernel in Chicory through initramfs unpack, `/init`, BusyBox shell startup, scripted `echo`, and scripted `uname -a`.

Not done locally:

- Running the WASI artifact, because `wasmtime`, `wasmer`, `wasm3`, and `iwasm` were not installed on PATH.
- Rebuilding the Linux image from Buildroot in this repo. The upstream configs are present in the pinned source; wiring that into a cached container task is the next step after the WASI module boots.
- Rebuilding `joelseverin/linux-wasm` locally from patched source/toolchain.
- Building or running `container2wasm` locally.

## Sources

- linux-wasm demo: https://joelseverin.github.io/linux-wasm/
- linux-wasm source: https://github.com/joelseverin/linux-wasm
- Tom B.'s Linux/Wasm demo: https://linux.tombl.dev/
- Tom B.'s independent Linux/Wasm port: https://github.com/tombl/linux
- container2wasm: https://github.com/container2wasm/container2wasm
- qemu-wasm: https://github.com/ktock/qemu-wasm
- mini-rv32ima: https://github.com/cnlohr/mini-rv32ima
- mini-rv32ima image archive: https://github.com/cnlohr/mini-rv32ima-images
- Linux kernel LLVM build docs: https://docs.kernel.org/kbuild/llvm.html
- Buildroot docs: https://buildroot.org/docs.html
- TinyEMU: https://bellard.org/tinyemu/
- v86: https://github.com/copy/v86
