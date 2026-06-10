#!/usr/bin/env bash
set -euo pipefail

SCRIPT_DIR="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)"
SPIKE_DIR="$(cd "${SCRIPT_DIR}/.." && pwd)"

BUILD_DIR="${1:-${SPIKE_DIR}/build}"
PIN="84858f58cb41899705e2ff2d6ee3b2d5c0795bfe"
TARBALL_SHA256="ef4a5968b94e309e849a15e4b70ba8e86462bf76b2edfe561f8d922bccd1756e"
URL="https://github.com/cnlohr/mini-rv32ima/archive/${PIN}.tar.gz"

TARBALL="${BUILD_DIR}/downloads/mini-rv32ima-${PIN}.tar.gz"
SRC_DIR="${BUILD_DIR}/src/mini-rv32ima"

mkdir -p "${BUILD_DIR}/downloads" "${BUILD_DIR}/src"

if [ ! -f "${TARBALL}" ]; then
  curl -fL -o "${TARBALL}" "${URL}"
fi

actual_sha="$(shasum -a 256 "${TARBALL}" | awk '{print $1}')"
if [ "${actual_sha}" != "${TARBALL_SHA256}" ]; then
  echo "mini-rv32ima tarball checksum mismatch" >&2
  echo "expected ${TARBALL_SHA256}" >&2
  echo "actual   ${actual_sha}" >&2
  exit 1
fi

rm -rf "${SRC_DIR}"
mkdir -p "${SRC_DIR}"
tar -xzf "${TARBALL}" -C "${SRC_DIR}" --strip-components=1

patch -d "${SRC_DIR}" -p1 < "${SPIKE_DIR}/patches/mini-rv32ima-wasi.patch"

echo "${SRC_DIR}"
