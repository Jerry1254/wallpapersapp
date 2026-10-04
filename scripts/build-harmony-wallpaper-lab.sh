#!/usr/bin/env bash
set -euo pipefail

lab_root="$(cd "$(dirname "${BASH_SOURCE[0]}")/.." && pwd)"
lab_source="$lab_root/apps/harmony-wallpaper-lab"
# Hvigor 不支持含中文的工程路径；在独立英文缓存目录构建。
lab_cache="${QJ_HARMONY_LAB_CACHE:-$HOME/.cache/qingjing-harmony-wallpaper-lab}"
lab_node="${QJ_DEVECO_NODE:-/Applications/DevEco-Studio.app/Contents/tools/node/bin/node}"
lab_cli="${QJ_DEVECO_CLI_JS:-}"
lab_mode="release"
lab_sign="false"
lab_device=""

while [[ $# -gt 0 ]]; do
  case "$1" in
    --sign) lab_sign="true"; shift ;;
    --install) lab_device="${2:?请提供设备地址或序列号}"; shift 2 ;;
    --debug) lab_mode="debug"; shift ;;
    *) echo "用法：$0 [--sign] [--install 设备] [--debug]" >&2; exit 2 ;;
  esac
done

if [[ -z "$lab_cli" ]]; then
  for lab_candidate in "$HOME"/.npm/_npx/*/node_modules/@deveco/deveco-cli/dist/cli.js; do
    if [[ -f "$lab_candidate" ]]; then lab_cli="$lab_candidate"; break; fi
  done
fi
[[ -x "$lab_node" && -f "$lab_cli" ]] || { echo "请先安装 DevEco Studio 和 DevEco CLI。" >&2; exit 1; }

mkdir -p "$lab_cache"
rsync -a --exclude .hvigor --exclude oh_modules --exclude build --exclude build-profile.local.json5 \
  "$lab_source/" "$lab_cache/"
if [[ -f "$lab_source/build-profile.local.json5" ]]; then
  cp "$lab_source/build-profile.local.json5" "$lab_cache/build-profile.json5"
fi

if [[ "$lab_sign" == "true" ]]; then
  (cd "$lab_cache" && "$lab_node" "$lab_cli" signature generate)
  cp "$lab_cache/build-profile.json5" "$lab_source/build-profile.local.json5"
  chmod 600 "$lab_source/build-profile.local.json5" "$lab_cache/build-profile.json5"
fi

# 只移除最终包，防止本次未签名时误选上一次的已签名 HAP；保留编译缓存。
rm -f "$lab_cache/entry/build/default/outputs/default/entry-default-signed.hap" \
  "$lab_cache/entry/build/default/outputs/default/entry-default-unsigned.hap"
(cd "$lab_cache" && "$lab_node" "$lab_cli" build --build-mode "$lab_mode")
lab_output="$lab_root/build/harmony-wallpaper-lab/$lab_mode"
mkdir -p "$lab_output"
lab_hap="$lab_cache/entry/build/default/outputs/default/entry-default-signed.hap"
if [[ ! -f "$lab_hap" ]]; then
  lab_hap="$lab_cache/entry/build/default/outputs/default/entry-default-unsigned.hap"
  [[ -z "$lab_device" ]] || { echo "测试包尚未签名；请登录后使用 --sign 生成独立包签名。" >&2; exit 1; }
fi
[[ -f "$lab_hap" ]] || { echo "没有找到 HAP 构建产物。" >&2; exit 1; }
lab_target="$lab_output/$(basename "$lab_hap")"
cp "$lab_hap" "$lab_target"
shasum -a 256 "$lab_target"

if [[ -n "$lab_device" ]]; then
  lab_hdc="${QJ_HDC:-/Applications/DevEco-Studio.app/Contents/sdk/default/openharmony/toolchains/hdc}"
  if [[ "$lab_device" == *:* ]]; then "$lab_hdc" tconn "$lab_device"; fi
  lab_install_result="$("$lab_hdc" -t "$lab_device" install -r "$lab_target" 2>&1)"
  printf '%s\n' "$lab_install_result"
  if ! printf '%s\n' "$lab_install_result" | rg -qi 'install.*success'; then
    echo "未确认独立测试包安装成功，停止启动。" >&2
    exit 1
  fi
  "$lab_hdc" -t "$lab_device" shell aa start -a EntryAbility -b com.qingjing.wallpaper.lab
fi
