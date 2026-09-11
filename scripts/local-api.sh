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

qj_init_admin() {
  if [[ -z "$(qj_compose ps --status running --quiet api)" ]]; then
    echo "API 尚未运行，请先执行 ./scripts/local-api.sh up。" >&2
    return 1
  fi

  qj_admin_count="$(qj_compose exec -T mysql sh -c \
    'MYSQL_PWD="$MYSQL_ROOT_PASSWORD" mysql -N -B -uroot "$MYSQL_DATABASE" -e "SELECT COUNT(*) FROM admin_account"')"
  if [[ "${qj_admin_count}" != "0" ]]; then
    echo "单管理员账号已经存在；初始化命令不会重置现有密码。" >&2
    return 1
  fi

  qj_admin_username="${QJ_ADMIN_USERNAME:-}"
  qj_admin_password="${QJ_ADMIN_PASSWORD:-}"
  if [[ -z "${qj_admin_username}" ]]; then
    read -r -p "管理员用户名（4-64 位小写字母、数字、点、下划线或连字符）：" qj_admin_username
  fi
  if [[ -z "${qj_admin_password}" ]]; then
    read -r -s -p "管理员密码（12-128 位）：" qj_admin_password
    echo
    read -r -s -p "再次输入管理员密码：" qj_admin_password_confirm
    echo
    if [[ "${qj_admin_password}" != "${qj_admin_password_confirm}" ]]; then
      echo "两次输入的密码不一致。" >&2
      return 1
    fi
  fi
  if [[ ! "${qj_admin_username}" =~ ^[a-z0-9][a-z0-9._-]{3,63}$ ]]; then
    echo "管理员用户名格式不符合要求。" >&2
    return 1
  fi
  if (( ${#qj_admin_password} < 12 || ${#qj_admin_password} > 128 )); then
    echo "管理员密码长度必须为 12-128 位。" >&2
    return 1
  fi

  export QJ_ADMIN_USERNAME="${qj_admin_username}"
  export QJ_ADMIN_PASSWORD="${qj_admin_password}"
  qj_bootstrap_result=0
  if ! qj_compose up --detach --force-recreate api || ! qj_wait_for_api; then
    qj_bootstrap_result=1
  fi
  unset QJ_ADMIN_USERNAME QJ_ADMIN_PASSWORD qj_admin_password qj_admin_password_confirm

  # Recreate immediately without bootstrap plaintext so it does not remain in container metadata.
  qj_compose up --detach --force-recreate api
  qj_wait_for_api
  if (( qj_bootstrap_result != 0 )); then
    echo "管理员初始化失败，请查看 API 日志。" >&2
    return 1
  fi
  qj_admin_count="$(qj_compose exec -T mysql sh -c \
    'MYSQL_PWD="$MYSQL_ROOT_PASSWORD" mysql -N -B -uroot "$MYSQL_DATABASE" -e "SELECT COUNT(*) FROM admin_account"')"
  if [[ "${qj_admin_count}" != "1" ]]; then
    echo "管理员初始化后未找到单例账号，请查看 API 日志。" >&2
    return 1
  fi
  echo "单管理员账号已初始化，明文密码未写入仓库或运行时文件。"
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
  init-admin)
    qj_init_admin
    ;;
  *)
    echo "用法：./scripts/local-api.sh [up|down|status|logs [service]|init-admin]" >&2
    exit 2
    ;;
esac
