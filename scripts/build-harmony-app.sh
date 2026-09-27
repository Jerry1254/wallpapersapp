#!/usr/bin/env bash
set -euo pipefail

qingjing_root="$(cd "$(dirname "${BASH_SOURCE[0]}")/.." && pwd)"
qingjing_source="${qingjing_root}/apps/harmony"
qingjing_temp="$(mktemp -d /tmp/qingjing-harmony-build.XXXXXX)"
qingjing_mode="${1:-debug}"

cleanup() {
  find "${qingjing_temp}" -depth -delete 2>/dev/null || true
}
trap cleanup EXIT

if [[ "${qingjing_mode}" != "debug" && "${qingjing_mode}" != "release" ]]; then
  echo "用法：$0 [debug|release]" >&2
  exit 2
fi

rsync -a \
  --exclude .hvigor \
  --exclude oh_modules \
  --exclude build \
  --exclude entry/build \
  --exclude build-profile.local.json5 \
  "${qingjing_source}/" "${qingjing_temp}/"

if [[ -f "${qingjing_source}/build-profile.local.json5" ]]; then
  cp "${qingjing_source}/build-profile.local.json5" "${qingjing_temp}/build-profile.json5"
fi

(
  cd "${qingjing_temp}"
  npx @deveco/deveco-cli@stable build --build-mode "${qingjing_mode}"
)

qingjing_output="${qingjing_root}/build/harmony/${qingjing_mode}"
mkdir -p "${qingjing_output}"
find "${qingjing_temp}/entry/build" -type f -name '*.hap' -exec cp {} "${qingjing_output}/" \;

echo "鸿蒙构建完成：${qingjing_output}"
