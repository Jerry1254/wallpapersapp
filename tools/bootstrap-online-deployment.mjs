import { createHash } from 'node:crypto'
import {
  chmodSync,
  existsSync,
  mkdirSync,
  readFileSync,
  rmSync,
  statSync,
  writeFileSync
} from 'node:fs'
import os from 'node:os'
import path from 'node:path'
import process from 'node:process'
import { spawnSync } from 'node:child_process'
import { fileURLToPath } from 'node:url'

const root = fileURLToPath(new URL('..', import.meta.url))
const configPath = path.join(root, 'config/online-deployment.local.json')
const installerPath = path.join(root, 'infra/online/install-blue-green-remote.sh')

function fail(message) {
  throw new Error(message)
}

function assert(condition, message) {
  if (!condition) fail(message)
}

function expandHome(value) {
  return value === '~' ? os.homedir() : value.startsWith('~/')
    ? path.join(os.homedir(), value.slice(2))
    : value
}

function execute(command, args, options = {}) {
  const result = spawnSync(command, args, {
    cwd: options.cwd || root,
    encoding: 'utf8',
    input: options.input,
    env: options.env ? { ...process.env, ...options.env } : process.env,
    stdio: options.inherit ? 'inherit' : 'pipe',
    maxBuffer: 32 * 1024 * 1024
  })
  if (result.error || result.status !== 0) {
    fail(`${options.label || command}失败`)
  }
  return String(result.stdout || '').trim()
}

function sha256(file) {
  return createHash('sha256').update(readFileSync(file)).digest('hex')
}

function shellQuote(value) {
  return `'${String(value).replaceAll("'", `'"'"'`)}'`
}

function timestamp() {
  const now = new Date()
  return [
    now.getUTCFullYear(),
    String(now.getUTCMonth() + 1).padStart(2, '0'),
    String(now.getUTCDate()).padStart(2, '0'),
    '-',
    String(now.getUTCHours()).padStart(2, '0'),
    String(now.getUTCMinutes()).padStart(2, '0'),
    String(now.getUTCSeconds()).padStart(2, '0')
  ].join('')
}

try {
  assert(existsSync(configPath), '缺少 config/online-deployment.local.json')
  const config = JSON.parse(readFileSync(configPath, 'utf8'))
  const identity = expandHome(config.sshIdentityFile || '')
  assert(config.environment === 'ONLINE_MAIN', '只允许初始化 ONLINE_MAIN')
  assert(config.sshUser === 'ubuntu', '首次初始化必须使用 ubuntu 受控入口')
  assert(config.deploymentRoot === '/opt/qingjing', '部署根目录不符合基线')
  assert(
    config.serviceEnvironmentFile === '/opt/qingjing/shared/env/api.env',
    'API 环境文件不符合基线'
  )
  assert(/^[A-Za-z0-9.-]+$/u.test(config.serverHost || ''), '服务器地址不合法')
  assert(/^[A-Za-z0-9.-]+$/u.test(config.publicHost || ''), '公开域名不合法')
  assert(existsSync(identity) && statSync(identity).isFile(), 'SSH 私钥不存在')

  const branch = execute('git', ['branch', '--show-current'], { label: 'Git 分支检查' })
  assert(branch === 'main', '初始化只允许使用 main 分支')
  assert(
    !execute('git', ['status', '--porcelain', '--untracked-files=no'], { label: '工作区检查' }),
    '跟踪文件存在未提交改动'
  )
  execute('git', ['fetch', '--quiet', 'origin', 'main'], { label: '远端 main 检查' })
  const commit = execute('git', ['rev-parse', 'HEAD'], { label: '本地提交检查' })
  assert(commit === execute('git', ['rev-parse', 'origin/main'], { label: '远端提交检查' }), '本地 main 尚未推送')

  execute('npm', ['ci'], { cwd: path.join(root, 'apps/admin-web'), label: '管理后台依赖安装', inherit: true })
  execute('npm', ['run', 'build'], { cwd: path.join(root, 'apps/admin-web'), label: '管理后台构建', inherit: true })
  execute('./mvnw', ['--batch-mode', '--no-transfer-progress', 'verify'], {
    cwd: path.join(root, 'services/api-server'),
    label: 'API 验证与打包',
    inherit: true
  })

  const releaseId = `${timestamp()}-${commit.slice(0, 12)}`
  const archive = execute(process.execPath, ['tools/package-online-release.mjs', releaseId], {
    label: '完整发布包生成',
    env: { GIT_COMMIT: commit, RELEASE_NOTE: 'blue-green-bootstrap' }
  })
  assert(existsSync(archive) && statSync(archive).size > 0, '完整发布包不存在')

  const secretDirectory = path.join(os.homedir(), '.qingjing-secrets', 'woodpecker')
  const deployKey = path.join(secretDirectory, 'online-deploy')
  mkdirSync(secretDirectory, { recursive: true, mode: 0o700 })
  chmodSync(secretDirectory, 0o700)
  if (!existsSync(deployKey)) {
    execute('ssh-keygen', ['-q', '-t', 'ed25519', '-N', '', '-C', 'qingjing-woodpecker-online', '-f', deployKey], {
      label: '受限发布密钥生成'
    })
  }
  chmodSync(deployKey, 0o600)

  const nginxTemplate = readFileSync(path.join(root, 'infra/online/nginx/qingjing.conf.template'), 'utf8')
  const renderedNginx = nginxTemplate.replaceAll('{{PUBLIC_HOST}}', config.publicHost)
  assert(!renderedNginx.includes('{{'), 'Nginx 模板渲染不完整')
  const renderedNginxPath = path.join(root, '.runtime', 'online-deployment', 'qingjing.conf')
  mkdirSync(path.dirname(renderedNginxPath), { recursive: true })
  writeFileSync(renderedNginxPath, renderedNginx, { mode: 0o600 })

  const knownHosts = execute('ssh-keygen', ['-F', config.serverHost, '-f', path.join(os.homedir(), '.ssh/known_hosts')], {
    label: 'SSH 主机指纹读取'
  }).split(/\r?\n/u).filter((line) => line && !line.startsWith('#')).join('\n')
  assert(knownHosts, '本机 known_hosts 中缺少服务器指纹')

  const ssh = ['-i', identity, '-o', 'BatchMode=yes', '-o', 'IdentitiesOnly=yes', '-o', 'StrictHostKeyChecking=yes']
  const target = `${config.sshUser}@${config.serverHost}`
  const remoteBase = `/tmp/qingjing-blue-green-${releaseId}`
  execute('ssh', [...ssh, target, `install -d -m 700 ${shellQuote(remoteBase)}`], { label: '服务器临时目录创建' })
  const uploads = {
    archive,
    controller: path.join(root, 'infra/online/qingjing-deploy'),
    unit: path.join(root, 'infra/online/systemd/qingjing-api@.service'),
    nginx: renderedNginxPath,
    publicKey: `${deployKey}.pub`
  }
  const remote = Object.fromEntries(Object.keys(uploads).map((name) => [name, `${remoteBase}/${name}`]))
  try {
    for (const [name, localPath] of Object.entries(uploads)) {
      execute('scp', [...ssh, localPath, `${target}:${remote[name]}`], { label: `初始化文件 ${name} 上传` })
    }
    const args = [
      config.deploymentRoot,
      releaseId,
      remote.archive,
      sha256(uploads.archive),
      config.serviceEnvironmentFile,
      config.publicHost,
      config.publicHost,
      remote.controller,
      sha256(uploads.controller),
      remote.unit,
      sha256(uploads.unit),
      remote.nginx,
      sha256(uploads.nginx),
      remote.publicKey,
      sha256(uploads.publicKey)
    ]
    const command = ['sudo', '-n', 'bash', '-s', '--', ...args].map(shellQuote).join(' ')
    execute('ssh', [...ssh, target, command], {
      label: '蓝绿部署初始化',
      input: readFileSync(installerPath, 'utf8')
    })
  } finally {
    execute('ssh', [...ssh, target, `rm -rf ${shellQuote(remoteBase)}`], { label: '服务器临时文件清理' })
    rmSync(renderedNginxPath, { force: true })
  }

  const secretFile = path.join(secretDirectory, 'woodpecker-online-secrets.txt')
  writeFileSync(secretFile, [
    `ONLINE_DEPLOYMENT_HOST=${config.serverHost}`,
    'ONLINE_DEPLOYMENT_USER=qingjing-deploy',
    `ONLINE_DEPLOYMENT_ROOT=${config.deploymentRoot}`,
    `ONLINE_SERVICE_ENVIRONMENT_FILE=${config.serviceEnvironmentFile}`,
    `ONLINE_PUBLIC_HOST=${config.publicHost}`,
    `ONLINE_DEPLOYMENT_SSH_PRIVATE_KEY_FILE=${deployKey}`,
    'ONLINE_DEPLOYMENT_SSH_KNOWN_HOSTS_START',
    knownHosts,
    'ONLINE_DEPLOYMENT_SSH_KNOWN_HOSTS_END',
    ''
  ].join('\n'), { mode: 0o600 })
  process.stdout.write(`蓝绿运行时初始化完成：${releaseId}\nWoodpecker 秘密配置已保存到本机受限目录。\n`)
} catch (error) {
  process.stderr.write(`${error instanceof Error ? error.message : '蓝绿部署初始化失败'}\n`)
  process.exit(1)
}
