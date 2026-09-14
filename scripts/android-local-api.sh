#!/usr/bin/env bash
# Run this worktree API against an explicitly selected existing LOCAL environment.
set -euo pipefail
qj_root="$(cd "$(dirname "${BASH_SOURCE[0]}")/.." && pwd)"
: "${QJ_LOCAL_ENV_FILE:?Set the existing local compose.env absolute path}"
: "${QJ_STORAGE_ROOT:?Set the same local environment storage absolute path}"
[[ -f "$QJ_LOCAL_ENV_FILE" && -d "$QJ_STORAGE_ROOT" ]] || { echo 'Local environment or storage does not exist' >&2; exit 1; }
export LC_ALL=en_US.UTF-8 LANG=en_US.UTF-8
set -a
source "$QJ_LOCAL_ENV_FILE"
set +a
export SPRING_PROFILES_ACTIVE=local
export SERVER_PORT="${QJ_ANDROID_API_PORT:-8081}"
exec java -Dfile.encoding=UTF-8 -jar "$qj_root/services/api-server/target/wallpaper-api-server-0.1.0-SNAPSHOT.jar"
