#!/bin/zsh
set -euo pipefail

# Configure the isolated Debug entry point, then choose Runner-StoreKit in Xcode.
# No archive is produced and no phone is installed by this script.
task_preview_root="$(cd "$(dirname "$0")/.." && pwd)"
cd "$task_preview_root"
flutter build ios --debug --config-only --no-codesign --no-pub \
  --target tool/ios_purchase_preview.dart
print '在 Xcode 打开 ios/Runner.xcworkspace，选 Runner-StoreKit，运行本地测试。'
print '测试完成后，正常打包需明确使用 lib/main.dart。'
