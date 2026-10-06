#!/bin/zsh
qj_creator_root="$(cd "$(dirname "$0")" && pwd)"
export PATH="$HOME/.homebrew/bin:/opt/homebrew/bin:/usr/local/bin:$PATH"
python3 "$qj_creator_root/scripts/creator-studio.py" "$@"
qj_creator_result=$?
if (( qj_creator_result != 0 )); then
  read 'qj_creator_close?按回车关闭此窗口…'
fi
exit "$qj_creator_result"
