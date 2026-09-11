#!/usr/bin/env bash
set -euo pipefail

qj_script_dir="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)"
qj_repo_root="$(cd "${qj_script_dir}/.." && pwd)"
qj_env_file="${qj_repo_root}/.runtime/local-api/compose.env"
qj_compose_file="${qj_repo_root}/infra/local/compose.yaml"
qj_marker_request_id="00000000-0000-4000-8000-000000000004"

"${qj_repo_root}/scripts/local-api.sh" up

set -a
# shellcheck disable=SC1090
source "${qj_env_file}"
set +a

qj_compose() {
  docker compose --env-file "${qj_env_file}" -f "${qj_compose_file}" "$@"
}

qj_mysql() {
  qj_compose exec -T -e MYSQL_PWD="${QJ_MYSQL_ROOT_PASSWORD}" mysql \
    mysql -uroot --batch --skip-column-names "${QJ_MYSQL_DATABASE}" -e "$1"
}

qj_migration_count="$(qj_mysql "SELECT COUNT(*) FROM flyway_schema_history WHERE success = 1;")"
if [[ "${qj_migration_count}" -lt 1 ]]; then
  echo "Flyway 没有成功迁移。" >&2
  exit 1
fi

qj_table_count="$(qj_mysql "SELECT COUNT(*) FROM information_schema.tables WHERE table_schema = '${QJ_MYSQL_DATABASE}' AND table_name <> 'flyway_schema_history';")"
if [[ "${qj_table_count}" -ne 16 ]]; then
  echo "预期 16 张业务表，实际 ${qj_table_count} 张。" >&2
  exit 1
fi

qj_mysql "INSERT INTO audit_event (actor_admin_id, request_id, action, aggregate_type, aggregate_id, result, change_summary, created_at) SELECT NULL, '${qj_marker_request_id}', 'LOCAL_RESTART_CHECK', 'SYSTEM', 'local-stack', 'SUCCEEDED', NULL, UTC_TIMESTAMP(6) WHERE NOT EXISTS (SELECT 1 FROM audit_event WHERE request_id = '${qj_marker_request_id}');"

qj_compose restart mysql redis api >/dev/null

for qj_attempt in $(seq 1 90); do
  if curl --fail --silent "http://127.0.0.1:${QJ_API_HOST_PORT}/actuator/health/readiness" >/dev/null; then
    break
  fi
  if [[ "${qj_attempt}" -eq 90 ]]; then
    echo "重启后 API 未在 180 秒内恢复。" >&2
    exit 1
  fi
  sleep 2
done

qj_marker_count="$(qj_mysql "SELECT COUNT(*) FROM audit_event WHERE request_id = '${qj_marker_request_id}';")"
if [[ "${qj_marker_count}" -ne 1 ]]; then
  echo "重启后 MySQL 验证记录未保留。" >&2
  exit 1
fi

qj_health="$(curl --fail --silent "http://127.0.0.1:${QJ_API_HOST_PORT}/actuator/health")"
if [[ "${qj_health}" != *'"status":"UP"'* ]]; then
  echo "重启后健康检查不是 UP：${qj_health}" >&2
  exit 1
fi

echo "本地栈验证通过：Flyway ${qj_migration_count} 个迁移、${qj_table_count} 张业务表，API/MySQL/Redis 重启后健康且 MySQL 数据保留。"
