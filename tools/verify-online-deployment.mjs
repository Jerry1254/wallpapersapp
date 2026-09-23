import { readFileSync } from 'node:fs'
import path from 'node:path'
import process from 'node:process'
import { spawnSync } from 'node:child_process'
import { fileURLToPath } from 'node:url'

const root = fileURLToPath(new URL('..', import.meta.url))

function read(relative) {
  return readFileSync(path.join(root, relative), 'utf8')
}

function assert(condition, message) {
  if (!condition) throw new Error(message)
}

function verify(command, args, label) {
  const result = spawnSync(command, args, { cwd: root, encoding: 'utf8' })
  if (result.error || result.status !== 0) throw new Error(`${label}失败`)
}

try {
  const workflow = read('.woodpecker/online-release.yaml')
  const sync = read('.woodpecker/commit-sync.yaml')
  const controller = read('infra/online/qingjing-deploy')
  const installer = read('infra/online/install-blue-green-remote.sh')
  const unit = read('infra/online/systemd/qingjing-api@.service')
  const nginx = read('infra/online/nginx/qingjing.conf.template')
  const packager = read('tools/package-online-release.mjs')
  const application = read('services/api-server/src/main/resources/application.yml')
  const scheduler = read('services/api-server/src/main/java/com/qingjing/wallpaper/shared/config/SchedulingConfiguration.java')

  assert(workflow.includes('Jerry1254/wallpapersapp.git'), '流水线必须检出倾境壁纸仓库')
  assert(workflow.includes('cache_repo="$cache_root/wallpapersapp.git"'), '流水线必须使用独立 Git 对象缓存')
  assert(workflow.includes('npm ci --cache /cache/npm --prefer-offline'), '管理后台必须复用 npm 缓存')
  assert(workflow.includes('./mvnw --batch-mode --no-transfer-progress verify'), 'API 发布前必须执行 Maven verify')
  assert(workflow.includes('from_secret: online_deployment_ssh_private_key'), '服务器密钥必须由仓库秘密注入')
  assert(workflow.includes('/usr/local/libexec/qingjing-deploy deploy'), '发布必须只调用受限控制器')
  assert(!/(?:\d{1,3}\.){3}\d{1,3}/u.test(workflow), '流水线不得写入服务器 IP')
  assert(!workflow.includes('BEGIN OPENSSH PRIVATE KEY'), '流水线不得写入私钥')
  assert(sync.includes('skip_clone: true') && !sync.includes('from_secret'), '提交同步不得读取发布秘密')

  assert(controller.includes('BLUE_PORT=8080') && controller.includes('GREEN_PORT=8082'), '蓝绿 API 端口必须隔离')
  assert(controller.includes("printf 'MANAGEMENT_SERVER_PORT=%s\\n'"), 'Spring Boot 管理端口变量名必须正确')
  assert(!controller.includes("printf 'MANAGEMENT_PORT=%s\\n'"), '禁止使用无效的管理端口变量')
  assert(controller.includes('QJ_SCHEDULING_ENABLED=false') && controller.includes('QJ_SCHEDULING_ENABLED=true'), '定时任务必须保持单实例')
  assert(controller.includes('verify_slot_identity'), '候选 API 必须核对制品身份')
  assert(controller.includes('check_pending_migration_policy'), 'Flyway 发布必须检查破坏性语句')
  assert(controller.includes('verify_database_migration'), '候选 API 就绪后必须回读 Flyway 版本')
  assert(installer.includes('command="/usr/local/libexec/qingjing-deploy-ssh",restrict'), '发布密钥必须使用强制命令和 restrict')
  assert(unit.includes('User=qingjing') && unit.includes('ReadWritePaths=/opt/qingjing/shared/storage'), 'systemd 必须使用受限用户和持久化资源目录')
  assert(nginx.includes('include /opt/qingjing/runtime/api-upstream.conf'), 'Nginx 必须从原子上游文件读取活动槽')
  assert(packager.includes("path.join(bundleDirectory, 'runtime.env')"), '发布包必须携带可核验运行身份')
  assert(application.includes('scheduling-enabled: ${QJ_SCHEDULING_ENABLED:true}'), 'API 必须提供定时任务开关')
  assert(scheduler.includes('@ConditionalOnProperty'), '定时任务开关必须在 Spring 容器层生效')

  verify('bash', ['-n', 'infra/online/qingjing-deploy'], '发布控制器语法检查')
  verify('bash', ['-n', 'infra/online/install-blue-green-remote.sh'], '远端初始化器语法检查')
  verify(process.execPath, ['--check', 'tools/package-online-release.mjs'], '发布包生成器语法检查')
  verify(process.execPath, ['--check', 'tools/bootstrap-online-deployment.mjs'], '本地初始化器语法检查')
  process.stdout.write('ONLINE_MAIN 发布契约检查通过。\n')
} catch (error) {
  process.stderr.write(`${error instanceof Error ? error.message : 'ONLINE_MAIN 发布契约检查失败'}\n`)
  process.exit(1)
}
