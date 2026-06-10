# Linux/Wasm research pass

Research date: 2026-06-10.

Goal: identify the best starting target for a Boomslang-style experiment that either compiles Linux itself to Wasm or produces a small Linux distribution/environment as a Wasm artifact.

## Recommendation

Run the spike as three tracks:

1. Direct Linux/Wasm port: use `joelseverin/linux-wasm` as the primary target, and read Tom B.'s `tombl/linux` port side by side. This is the closest match to "compile the Linux kernel to Wasm."
2. Whole Linux environment in Wasm: use `container2wasm` as the practical distro/container target. This is the fastest path to "a small Linux distribution as a Wasm artifact" that can run under WASI runtimes and in the browser.
3. Tiny control artifact: keep the current `mini-rv32ima` path as the small, understandable baseline. It proves our build/runtime plumbing without depending on the direct Linux/Wasm patch stack.

The important distinction: track 1 compiles Linux and its userspace toward Wasm. Tracks 2 and 3 compile an emulator/VM to Wasm and boot a normal Linux guest inside it.

## Project matrix

| Project | Lane | What it gives us | Fit | Notes |
| --- | --- | --- | --- | --- |
| `joelseverin/linux-wasm` | Direct Linux kernel port | Linux `wasm32_nommu` / `wasm64_nommu` style port plus LLVM/lld, musl, BusyBox, initramfs, and JS runtime glue | Primary | Best match for "Linux kernel to Wasm"; carries patches across Linux/toolchain/libc/userspace/runtime |
| `tombl/linux` | Direct Linux kernel port | Independent Linux WebAssembly port with kernel threads mapped to workers, virtio plans/devices, `binfmt_wasm`, musl/BusyBox work | Required comparison | Joel's README says both ports converged and his repo rebases/merges ideas from Tom's |
| `container2wasm` | Container/distro to Wasm via emulation | Converts OCI images like Ubuntu/Debian/Python/Node/Vim to a Wasm image; runs on WASI runtimes and browser | Practical parallel target | Best way to get a usable distro-shaped Wasm artifact quickly |
| `ktock/qemu-wasm` | Full system VM in browser | Patched QEMU built with Emscripten; supports x86_64, AArch64, RISC-V examples | Reference or fallback | Big, but compatibility ceiling is high |
| `copy/v86` | x86 PC emulator and x86-to-Wasm JIT | Mature browser x86 emulator with many Linux demos | Reference | Browser-oriented, not a clean WASI/JVM artifact |
| JSLinux / TinyEMU | Small emulator booting Linux | RISC-V and x86 Linux in the browser; TinyEMU is small and MIT-licensed | Reference/fallback | Good source for disk, console, and small-system design |
| WebVM / CheerpX | x86 virtualization in browser | Strong end-user Linux VM experience, networking, WebVM repo | Reference only | CheerpX licensing/self-hosting limits make it a poor base dependency for this repo |
| `mini-rv32ima` | Tiny emulator control path | Tiny C RISC-V emulator that can boot RV32 NOMMU Linux and compile to `wasm32-wasip1` | Current control | Smallest single-artifact sanity path, but it is Linux-on-emulator, not Linux-as-Wasm |
| LKL | Linux kernel as a library | Links Linux kernel code into applications; syscall-shaped API and host operations | Alternate research path | Could be interesting for a "Linux services in Wasm" library, but not a distro |
| Blink / Firebox Blink | Linux ABI / ELF interpreter | Runs x86_64 Linux ELF programs; Firebox fork targets `wasm32-wasi-threads` | Adjacent target | Useful if we care more about Linux binary compatibility than booting a kernel |
| WASIX | Extended WASI/POSIX ABI | Threads, sockets, subprocesses, fork/vfork-like APIs, TTY, filesystem extensions | Adjacent runtime path | Useful apps now, but it is not Linux |
| WALI / thin kernel interfaces | Research ABI | Direct Linux syscall interface for Wasm modules | Host-interface research | Good conceptual material for ABI shape, not an artifact target |
| Browsix / Browsix-Wasm | Browser Unix abstractions | Processes, pipes, signals, sockets, shared filesystem in browser workers | Historical reference | Older, but explains the browser OS-abstraction problem well |

## Direct Linux/Wasm track

`joelseverin/linux-wasm` is the best direct target. Its demo says it boots the Linux kernel in the browser, with BusyBox programs backed by musl, and explicitly distinguishes this from x86/RISC-V emulation. The source repo contains build scripts and patches for LLVM/lld, Linux, musl, BusyBox, kernel headers, initramfs, and a runtime.

Key constraints to account for:

- Wasm MVP has no normal MMU story, so the kernel/userland path is NOMMU today.
- The browser runtime maps tasks/processes to Web Workers because Wasm execution cannot be preempted like a native CPU thread.
- The current runtime is JS/browser-shaped, not a WASI component or JVM/Chicory host yet.
- Carrying this path means pinning patched Linux, patched LLVM/lld, patched musl, patched BusyBox, and runtime glue.

Tom B.'s port is important prior art rather than a side note. Its demo says Linux 6.1 runs natively in the browser as a WebAssembly kernel port, with virtio devices, memory isolation, multicore support, BusyBox, and musl. Joel's repo says his later work rebases/combines ideas from both independent ports. For our spike, Tom's tree is the best comparison point when we need to understand which design choices are fundamental and which are local implementation details.

Best first step for this track:

1. Pin `joelseverin/linux-wasm` and record exact commit/source versions.
2. Run the repo's Docker build path once as an external baseline.
3. Extract the kernel/toolchain/userspace patch stack into our notes.
4. Try a minimal `wasm32_nommu` kernel-only build before attempting the full musl/BusyBox/initramfs flow.
5. Decide whether the first host target is browser-only, WASI, or a Chicory-specific host.

## Container/distro track

`container2wasm` is the best practical distro target. It converts a container image into a Wasm image, runs under WASI runtimes such as Wasmtime/WAMR/Wasmer/WasmEdge/Wazero, and has browser demos. It uses emulation internally: Bochs for x86_64 containers, TinyEMU for riscv64 containers, and QEMU where needed.

This should not replace the direct Linux/Wasm kernel work, but it is a better parallel track than inventing a new distro packaging path. It gives us quick answers for:

- Can we produce a distro-shaped `.wasm` artifact from a standard OCI image?
- What is the artifact size and boot latency for BusyBox/Alpine/Debian-scale images?
- Which WASI imports are required?
- Can Chicory run any of these images, or do they require WASI/thread/socket features outside Chicory's comfort zone?

Best first step for this track:

1. Pin `container2wasm`.
2. Convert a tiny image first, probably `busybox` or an Alpine-minirootfs-based image.
3. Run it with `wasmtime` as the baseline.
4. Compare the import surface and artifact size against the `mini-rv32ima.wasm` control.
5. Only then try a browser demo or a larger Debian/Ubuntu image.

## Control track

The `mini-rv32ima` work remains useful because the whole C emulator is small and understandable. The control artifact built in this spike is only 38 KB after stripping, imports `wasi_snapshot_preview1`, and can boot the upstream RV32 NOMMU Buildroot image natively. We still need a local WASI runner to verify it end-to-end as Wasm.

This track should stay intentionally narrow:

- Keep it pinned and reproducible.
- Use it to test Wasm host behavior and Java/Chicory feasibility.
- Avoid turning it into a full VM project unless the direct and container tracks fail to produce a usable host path.

## Non-targets

- Building `ARCH=wasm` from scratch: not the right first move now that two direct ports exist.
- Pure BusyBox/toybox on WASI: useful shell tools, but not Linux.
- WebContainers/Node-in-browser environments: useful developer UX, not Linux kernel or distro work.
- Wasm-in-Linux-kernel projects: interesting inverse direction, but not relevant to Linux-on-Wasm.

## Sources

- `joelseverin/linux-wasm` demo: https://joelseverin.github.io/linux-wasm/
- `joelseverin/linux-wasm` source: https://github.com/joelseverin/linux-wasm
- Tom B. Linux/Wasm demo: https://linux.tombl.dev/
- Tom B. Linux/Wasm source: https://github.com/tombl/linux
- `container2wasm`: https://github.com/container2wasm/container2wasm
- `ktock/qemu-wasm`: https://github.com/ktock/qemu-wasm
- `copy/v86`: https://github.com/copy/v86
- JSLinux: https://bellard.org/jslinux/
- TinyEMU: https://bellard.org/tinyemu/
- WebVM: https://webvm.io/
- WebVM source: https://github.com/leaningtech/webvm
- CheerpX: https://cheerpx.io/
- CheerpX licensing: https://cheerpx.io/licensing
- LKL: https://github.com/lkl/linux
- Blink: https://github.com/jart/blink
- Firebox Blink: https://github.com/jmfirth/firebox-blink
- WASIX: https://wasix.org/
- WASIX libc: https://github.com/wasix-org/wasix-libc
- WALI / thin kernel interfaces paper: https://arxiv.org/abs/2312.03858
- Browsix: https://browsix.org/
- Browsix source: https://github.com/plasma-umass/browsix
- WebAssembly memory-control proposal: https://github.com/WebAssembly/memory-control
- WebAssembly stack-switching proposal: https://github.com/WebAssembly/stack-switching
