#!/usr/bin/env bash

set -euo pipefail

DEPLOY_USER="qingjing-deploy"
DEPLOY_HOME="/var/lib/qingjing-deploy"
CONTROLLER="/usr/local/libexec/qingjing-deploy"
SSH_GATE="/usr/local/libexec/qingjing-deploy-ssh"
SUDOERS_FILE="/etc/sudoers.d/qingjing-deploy"

fail() {
  printf '%s\n' "$1" >&2
  exit 1
}

verify_upload() {
  local file="$1"
  local expected_sha="$2"
  local label="$3"

  [[ -s "${file}" ]] || fail "${label}不存在或为空"
  [[ "${expected_sha}" =~ ^[a-f0-9]{64}$ ]] || fail "${label}校验值不合法"
  [[ "$(sha256sum "${file}" | awk '{print $1}')" == "${expected_sha}" ]] \
    || fail "${label}完整性校验失败"
}

prepare_bootstrap_state() {
  local root="$1"
  local active_slot_file="${root}/runtime/active-slot"
  local preserved_state=""

  [[ -e "${active_slot_file}" ]] || return 0

  if systemctl is-active --quiet qingjing-api.service \
    && ! systemctl is-active --quiet qingjing-api@blue.service \
    && ! systemctl is-active --quiet qingjing-api@green.service \
    && [[ -s "${root}/current/wallpaper-api.jar" ]] \
    && [[ -s "${root}/current/admin-web/index.html" ]] \
    && grep -Fq '127.0.0.1:8080' "${root}/runtime/api-upstream.conf"; then
    preserved_state="${active_slot_file}.stale-$(date -u +%Y%m%dT%H%M%SZ)"
    mv "${active_slot_file}" "${preserved_state}"
    printf '识别到失败回退后保留的蓝绿状态，已保全旧状态并继续初始化。\n'
    return 0
  fi

  fail "ONLINE_MAIN 已经完成蓝绿初始化，或当前运行状态不允许自动恢复"
}

[[ "$(id -u)" == "0" ]] || fail "部署平台初始化必须由现有受控 root 入口执行"
[[ $# -eq 15 ]] || fail "部署平台初始化参数不完整"
command -v curl >/dev/null 2>&1 || fail "服务器缺少 curl"
command -v python3 >/dev/null 2>&1 || fail "服务器缺少 python3"
command -v mysql >/dev/null 2>&1 || fail "服务器缺少 MySQL 客户端"
command -v nginx >/dev/null 2>&1 || fail "服务器缺少 Nginx"
command -v java >/dev/null 2>&1 || fail "服务器缺少 Java"
command -v ss >/dev/null 2>&1 || fail "服务器缺少 ss"

root="$1"
release_id="$2"
archive_upload="$3"
archive_sha="$4"
service_environment_file="$5"
admin_host="$6"
api_host="$7"
controller_upload="$8"
controller_sha="$9"
unit_upload="${10}"
unit_sha="${11}"
nginx_upload="${12}"
nginx_sha="${13}"
public_key_upload="${14}"
public_key_sha="${15}"

[[ "${root}" == "/opt/qingjing" ]] || fail "部署根目录与受控基线不一致"
[[ "${release_id}" =~ ^[0-9]{8}-[0-9]{6}-[a-f0-9]{7,40}$ ]] || fail "发布版本号不合法"
[[ "${service_environment_file}" == "/opt/qingjing/shared/env/api.env" ]] \
  || fail "API 环境文件路径与受控基线不一致"
prepare_bootstrap_state "${root}"
verify_upload "${archive_upload}" "${archive_sha}" "完整发布包"
verify_upload "${controller_upload}" "${controller_sha}" "发布控制器"
verify_upload "${unit_upload}" "${unit_sha}" "systemd 模板"
verify_upload "${nginx_upload}" "${nginx_sha}" "Nginx 配置"
verify_upload "${public_key_upload}" "${public_key_sha}" "部署公钥"
grep -Eq '^ssh-ed25519 [A-Za-z0-9+/=]+( .*)?$' "${public_key_upload}" \
  || fail "部署公钥格式不合法"

if ! id "${DEPLOY_USER}" >/dev/null 2>&1; then
  useradd --system --create-home --home-dir "${DEPLOY_HOME}" --shell /bin/bash "${DEPLOY_USER}"
fi
install -d -m 0750 -o "${DEPLOY_USER}" -g "${DEPLOY_USER}" "${DEPLOY_HOME}/uploads"
install -d -m 0755 /usr/local/libexec
install -m 0755 -o root -g root "${controller_upload}" "${CONTROLLER}"
ssh_gate_candidate="$(mktemp)"
cat > "${ssh_gate_candidate}" <<'SSH_GATE'
#!/usr/bin/env bash

set -euo pipefail

CONTROLLER="/usr/local/libexec/qingjing-deploy"

fail() {
  printf '%s\n' '该部署密钥只允许调用受限发布控制器' >&2
  exit 1
}

[[ -n "${SSH_ORIGINAL_COMMAND:-}" ]] || fail
read -r -a command_parts <<< "${SSH_ORIGINAL_COMMAND}"

if [[ ${#command_parts[@]} -ge 4 \
  && "${command_parts[0]}" == "sudo" \
  && "${command_parts[1]}" == "-n" \
  && "${command_parts[2]}" == "${CONTROLLER}" ]]; then
  exec "${command_parts[@]}"
fi

fail
SSH_GATE
install -m 0755 -o root -g root "${ssh_gate_candidate}" "${SSH_GATE}"
rm -f "${ssh_gate_candidate}"

sudoers_candidate="$(mktemp)"
printf '%s ALL=(root) NOPASSWD: %s\n' "${DEPLOY_USER}" "${CONTROLLER}" > "${sudoers_candidate}"
chmod 0440 "${sudoers_candidate}"
visudo -cf "${sudoers_candidate}" >/dev/null || fail "受限部署 sudoers 校验失败"
install -m 0440 -o root -g root "${sudoers_candidate}" "${SUDOERS_FILE}"
rm -f "${sudoers_candidate}"

controlled_archive="${DEPLOY_HOME}/uploads/${release_id}.tar.gz"
install -m 0640 -o "${DEPLOY_USER}" -g "${DEPLOY_USER}" \
  "${archive_upload}" "${controlled_archive}"

"${CONTROLLER}" bootstrap \
  "${root}" "${release_id}" "${controlled_archive}" \
  "${service_environment_file}" "${admin_host}" "${api_host}" \
  "${archive_sha}" "${unit_upload}" "${unit_sha}" "${nginx_upload}" "${nginx_sha}"

install -d -m 0700 -o "${DEPLOY_USER}" -g "${DEPLOY_USER}" "${DEPLOY_HOME}/.ssh"
authorized_keys_candidate="$(mktemp)"
if [[ -f "${DEPLOY_HOME}/.ssh/authorized_keys" ]]; then
  grep -E '^command="/usr/local/libexec/qingjing-deploy-ssh",restrict ssh-ed25519 [A-Za-z0-9+/=]+( .*)?$' \
    "${DEPLOY_HOME}/.ssh/authorized_keys" > "${authorized_keys_candidate}" || true
fi
public_key_blob="$(awk '{print $2}' "${public_key_upload}")"
if ! grep -Fq "${public_key_blob}" "${authorized_keys_candidate}"; then
  printf 'command="%s",restrict ' "${SSH_GATE}"
  cat "${public_key_upload}"
fi >> "${authorized_keys_candidate}"
install -m 0600 -o "${DEPLOY_USER}" -g "${DEPLOY_USER}" \
  "${authorized_keys_candidate}" "${DEPLOY_HOME}/.ssh/authorized_keys"
rm -f "${authorized_keys_candidate}"

rm -f \
  "${archive_upload}" "${controller_upload}" "${unit_upload}" \
  "${nginx_upload}" "${public_key_upload}"
printf '受限部署账号、蓝绿运行时和服务器发布控制器初始化完成。\n'
