#!/usr/bin/env bash
set -euo pipefail

REPO="$(cd "$(dirname "$0")/.." && pwd)"
VERSION_NAME="1.0.0"
VERSION_CODE="1"
OUTPUT=""
SKIP_TESTS=0
DRY_RUN=0
PUBLIC_ENV="${QJ_ANDROID_PUBLIC_ENV:-}"
SIGNING_ENV="${QJ_ANDROID_SIGNING_ENV:-}"

die() { printf 'ERROR: %s\n' "$*" >&2; exit 1; }
need() { command -v "$1" >/dev/null 2>&1 || die "缺少命令: $1"; }
usage() {
  cat <<'EOF'
Usage: package-offline-android.sh [options]
  --version-name VERSION  独立线下版本名称（默认 1.0.0）
  --version-code CODE     独立线下构建号（默认 1）
  --public-env FILE       正式资源公钥环境文件
  --signing-env FILE      现有正式 Android 签名环境文件
  --output DIRECTORY     APK 输出目录
  --skip-tests           当前代码已验证时跳过 analyze/test
  --dry-run              只核对配置和构建计划
本脚本仅构建吉意壁纸，不安装、不发布，也不修改其他端的版本。
EOF
}
while [ "$#" -gt 0 ]; do
  case "$1" in
    --version-name) shift; [ "$#" -gt 0 ] || die "缺少版本名称"; VERSION_NAME="$1" ;;
    --version-code) shift; [ "$#" -gt 0 ] || die "缺少构建号"; VERSION_CODE="$1" ;;
    --public-env) shift; [ "$#" -gt 0 ] || die "缺少公钥环境路径"; PUBLIC_ENV="$1" ;;
    --signing-env) shift; [ "$#" -gt 0 ] || die "缺少签名环境路径"; SIGNING_ENV="$1" ;;
    --output) shift; [ "$#" -gt 0 ] || die "缺少输出目录"; OUTPUT="$1" ;;
    --skip-tests) SKIP_TESTS=1 ;;
    --dry-run) DRY_RUN=1 ;;
    -h|--help) usage; exit 0 ;;
    *) die "未知参数: $1" ;;
  esac
  shift
done
[[ "$VERSION_NAME" =~ ^[0-9]+\.[0-9]+\.[0-9]+$ ]] || die "版本名称必须为三段数字"
[[ "$VERSION_CODE" =~ ^[1-9][0-9]*$ ]] || die "构建号必须为正整数"
need git; need flutter; need python3
COMMON_GIT="$(git -C "$REPO" rev-parse --path-format=absolute --git-common-dir)"
PRIMARY_REPO="$(dirname "$COMMON_GIT")"
PUBLIC_ENV="${PUBLIC_ENV:-$PRIMARY_REPO/.runtime/android-online/build-public.env}"
SIGNING_ENV="${SIGNING_ENV:-$PRIMARY_REPO/.runtime/android-online/signing.env}"
[ -f "$PUBLIC_ENV" ] || die "正式资源公钥配置不存在"
[ -f "$SIGNING_ENV" ] || die "正式签名配置不存在"
set -a
# Local, private configuration; never copy its contents into the repository.
# shellcheck disable=SC1090
source "$PUBLIC_ENV"
# shellcheck disable=SC1090
source "$SIGNING_ENV"
set +a
export QJ_PROD_PACKAGE_SIGNING_KEY_ID="${QJ_PROD_PACKAGE_SIGNING_KEY_ID:-${QJ_INTERNAL_PACKAGE_SIGNING_KEY_ID:-}}"
export QJ_PROD_PACKAGE_PUBLIC_KEY_DER="${QJ_PROD_PACKAGE_PUBLIC_KEY_DER:-${QJ_INTERNAL_PACKAGE_PUBLIC_KEY_DER:-}}"
[ -n "$QJ_PROD_PACKAGE_SIGNING_KEY_ID" ] && [ -n "$QJ_PROD_PACKAGE_PUBLIC_KEY_DER" ] || die "正式资源验签公钥未配置"
case "$QJ_PROD_PACKAGE_SIGNING_KEY_ID" in native-fixture*) die "禁止使用测试资源公钥" ;; esac
for name in QJ_PROD_KEYSTORE_FILE QJ_PROD_KEYSTORE_PASSWORD QJ_PROD_KEY_ALIAS QJ_PROD_KEY_PASSWORD; do
  [ -n "$(printenv "$name" 2>/dev/null || true)" ] || die "缺少正式签名配置: $name"
done
[ -f "$QJ_PROD_KEYSTORE_FILE" ] || die "正式 keystore 不存在"
OUTPUT="${OUTPUT:-$HOME/Downloads/吉意壁纸-Android-$(date +%Y%m%d-%H%M%S)}"
printf 'app=吉意壁纸\npackage=com.jiyi.wallpaper\nversion=%s+%s\noutput=%s\n' "$VERSION_NAME" "$VERSION_CODE" "$OUTPUT"
[ "$DRY_RUN" -eq 0 ] || exit 0

MOBILE="$REPO/apps/mobile"
if [ "$SKIP_TESTS" -eq 0 ]; then
  (cd "$MOBILE" && flutter analyze && flutter test)
fi
BUILD_CONFIG="$(mktemp "${TMPDIR:-/tmp}/jiyi-android-build.XXXXXX")"
trap 'rm -f "$BUILD_CONFIG"' EXIT
chmod 600 "$BUILD_CONFIG"
python3 - "$BUILD_CONFIG" <<'PY'
import json,sys
from pathlib import Path
Path(sys.argv[1]).write_text(json.dumps({'API_BASE_URL':'https://wallpaper.biguo66.top/api/v1'}))
PY
export ORG_GRADLE_PROJECT_offlineVersionName="$VERSION_NAME"
export ORG_GRADLE_PROJECT_offlineVersionCode="$VERSION_CODE"
(cd "$MOBILE" && flutter build apk --release --flavor offline --target-platform android-arm64 --dart-define-from-file="$BUILD_CONFIG")

SDK_ROOT="${ANDROID_HOME:-${ANDROID_SDK_ROOT:-$HOME/Library/Android/sdk}}"
TOOLS="$(find "$SDK_ROOT/build-tools" -mindepth 1 -maxdepth 1 -type d | sort | tail -1)"
AAPT="$TOOLS/aapt"; AAPT2="$TOOLS/aapt2"; APKSIGNER="$TOOLS/apksigner"
for tool in "$AAPT" "$AAPT2" "$APKSIGNER"; do [ -x "$tool" ] || die "Android build-tools 不完整"; done
APK="$MOBILE/build/app/outputs/flutter-apk/app-offline-release.apk"
[ -f "$APK" ] || die "吉意 APK 未生成"
"$APKSIGNER" verify "$APK" >/dev/null || die "APK 正式签名无效"
BADGING="$("$AAPT" dump badging "$APK")"
printf '%s\n' "$BADGING" | sed -n '1p' | grep -F "package: name='com.jiyi.wallpaper' versionCode='$VERSION_CODE' versionName='$VERSION_NAME'" >/dev/null || die "包名或独立版本错误"
printf '%s\n' "$BADGING" | grep -F "application-label:'吉意壁纸'" >/dev/null || die "应用名称错误"
"$AAPT2" dump resources "$APK" | grep -F "\"$QJ_PROD_PACKAGE_SIGNING_KEY_ID\"" >/dev/null || die "未内置正式资源验签配置"
"$AAPT2" dump resources "$APK" | grep -F "\"$QJ_PROD_PACKAGE_PUBLIC_KEY_DER\"" >/dev/null || die "正式资源验签公钥不一致"
python3 - "$APK" "$APKSIGNER" <<'PY'
import os,re,subprocess,sys,zipfile
from pathlib import Path
apk,signer=sys.argv[1:]
keytool=Path(os.environ.get('JAVA_HOME',''))/'bin/keytool'
if not keytool.is_file(): keytool=Path('keytool')
expected=subprocess.run([str(keytool),'-J-Duser.language=en','-list','-v','-keystore',os.environ['QJ_PROD_KEYSTORE_FILE'],'-alias',os.environ['QJ_PROD_KEY_ALIAS'],'-storepass:env','QJ_PROD_KEYSTORE_PASSWORD'],capture_output=True,text=True)
if expected.returncode: raise SystemExit('Unable to inspect production signer')
if 'CN=Android Debug' in expected.stdout: raise SystemExit('Debug signer is prohibited')
actual=subprocess.run([signer,'verify','--print-certs',apk],capture_output=True,text=True,check=True).stdout
left=re.search(r'SHA256:\s*([0-9A-F:]+)',expected.stdout)
right=re.search(r'certificate SHA-256 digest:\s*([0-9a-f]+)',actual)
if not left or not right or left[1].replace(':','').lower()!=right[1]: raise SystemExit('APK signer differs from production signer')
with zipfile.ZipFile(apk) as archive:
 if not any(name.startswith('lib/arm64-v8a/') for name in archive.namelist()): raise SystemExit('Missing ARM64 native libraries')
print('Production signer and ARM64 resources verified.')
PY
mkdir -p "$OUTPUT"
TARGET="$OUTPUT/吉意壁纸-${VERSION_NAME}-${VERSION_CODE}-arm64.apk"
cp "$APK" "$TARGET"
shasum -a 256 "$TARGET" | tee "$OUTPUT/SHA256SUMS"
printf 'DONE: %s\n' "$TARGET"
