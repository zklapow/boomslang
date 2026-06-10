#!/usr/bin/env bash
set -euo pipefail

SCRIPT_DIR="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)"
SPIKE_DIR="$(cd "${SCRIPT_DIR}/.." && pwd)"

BUILD_DIR="${1:-${SPIKE_DIR}/build}"
SRC_DIR="${BUILD_DIR}/src/mini-rv32ima"
IMAGE_ZIP="${BUILD_DIR}/downloads/linux-6.1.14-rv32nommu-cnl-1.zip"
IMAGE_SHA256="add651195348b538c309becb39c5f8ef4f9d15ec275a2954b02016fc38091393"
IMAGE_URL="https://github.com/cnlohr/mini-rv32ima-images/raw/master/images/linux-6.1.14-rv32nommu-cnl-1.zip"
IMAGE_DIR="${BUILD_DIR}/images"
LOG="${BUILD_DIR}/native-smoke.log"

if [ ! -d "${SRC_DIR}" ]; then
  "${SPIKE_DIR}/scripts/fetch-mini-rv32ima.sh" "${BUILD_DIR}" >/dev/null
fi

mkdir -p "${BUILD_DIR}/downloads" "${IMAGE_DIR}"

make -C "${SRC_DIR}/mini-rv32ima" mini-rv32ima

if [ ! -f "${IMAGE_ZIP}" ]; then
  curl -fL -o "${IMAGE_ZIP}" "${IMAGE_URL}"
fi

actual_sha="$(shasum -a 256 "${IMAGE_ZIP}" | awk '{print $1}')"
if [ "${actual_sha}" != "${IMAGE_SHA256}" ]; then
  echo "Linux image checksum mismatch" >&2
  echo "expected ${IMAGE_SHA256}" >&2
  echo "actual   ${actual_sha}" >&2
  exit 1
fi

unzip -o "${IMAGE_ZIP}" -d "${IMAGE_DIR}" >/dev/null

"${SRC_DIR}/mini-rv32ima/mini-rv32ima" -f "${IMAGE_DIR}/Image" >"${LOG}" 2>&1 &
pid="$!"
sleep "${BOOT_SECONDS:-8}"
kill -INT "${pid}" >/dev/null 2>&1 || true
wait "${pid}" >/dev/null 2>&1 || true

if grep -q "Welcome to Buildroot" "${LOG}" && grep -q "buildroot login:" "${LOG}"; then
  echo "native smoke passed: boot reached Buildroot login"
  echo "${LOG}"
else
  echo "native smoke failed: did not find Buildroot login in ${LOG}" >&2
  tail -n 80 "${LOG}" >&2
  exit 1
fi
