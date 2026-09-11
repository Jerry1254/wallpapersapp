#!/usr/bin/env bash
set -euo pipefail

qj_script_dir="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)"
qj_repo_root="$(cd "${qj_script_dir}/.." && pwd)"
qj_runtime_dir="${qj_repo_root}/.runtime/local-api"
qj_env_file="${qj_runtime_dir}/compose.env"
qj_compose_file="${qj_repo_root}/infra/local/compose.yaml"

qj_generate_env() {
  mkdir -p "${qj_runtime_dir}" "${qj_repo_root}/.runtime/storage"
  if [[ -f "${qj_env_file}" ]]; then
    return
  fi

  umask 077
  qj_mysql_password="$(openssl rand -hex 24)"
  qj_mysql_root_password="$(openssl rand -hex 24)"
  qj_redis_password="$(openssl rand -hex 24)"
  printf '%s\n' \
    'QJ_MYSQL_DATABASE=wallpaper_app' \
    'QJ_MYSQL_USER=wallpaper_app' \
    "QJ_MYSQL_PASSWORD=${qj_mysql_password}" \
    "QJ_MYSQL_ROOT_PASSWORD=${qj_mysql_root_password}" \
    "QJ_REDIS_PASSWORD=${qj_redis_password}" \
    'QJ_API_HOST_PORT=8080' \
    'QJ_MYSQL_HOST_PORT=3307' \
    'QJ_REDIS_HOST_PORT=6380' > "${qj_env_file}"
  echo "已在 .runtime/local-api/compose.env 生成仅供本机使用的随机凭据。"
}

qj_compose() {
  docker compose --env-file "${qj_env_file}" -f "${qj_compose_file}" "$@"
}

qj_wait_for_api() {
  for qj_attempt in $(seq 1 90); do
    if curl --fail --silent "http://127.0.0.1:${QJ_API_HOST_PORT}/actuator/health/readiness" >/dev/null; then
      echo "API 已就绪：http://127.0.0.1:${QJ_API_HOST_PORT}/actuator/health"
      return 0
    fi
    sleep 2
  done
  echo "API 未在 180 秒内就绪，请执行 ./scripts/local-api.sh logs 查看日志。" >&2
  return 1
}

qj_generate_env
set -a
# shellcheck disable=SC1090
source "${qj_env_file}"
set +a

case "${1:-up}" in
  up)
    (
      cd "${qj_repo_root}/services/api-server"
      ./mvnw --batch-mode --no-transfer-progress -DskipTests package
    )
    qj_compose up --build --detach
    qj_wait_for_api
    ;;
  down)
    qj_compose down
    ;;
  status)
    qj_compose ps
    ;;
  logs)
    qj_compose logs --follow --tail=200 "${2:-api}"
    ;;
  *)
    echo "用法：./scripts/local-api.sh [up|down|status|logs [service]]" >&2
    exit 2
    ;;
esac
