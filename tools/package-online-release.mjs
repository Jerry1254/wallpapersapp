import { createHash } from 'node:crypto'
import {
  chmodSync,
  cpSync,
  existsSync,
  mkdirSync,
  readFileSync,
  readdirSync,
  rmSync,
  statSync,
  writeFileSync
} from 'node:fs'
import path from 'node:path'
import process from 'node:process'
import { spawnSync } from 'node:child_process'
import { fileURLToPath } from 'node:url'

const root = fileURLToPath(new URL('..', import.meta.url))
const runtimeDirectory = path.join(root, '.runtime', 'online-releases')

function fail(message) {
  throw new Error(message)
}

function assert(condition, message) {
  if (!condition) {
    fail(message)
  }
}

function sha256(filePath) {
  return createHash('sha256').update(readFileSync(filePath)).digest('hex')
}

function sourceSha256() {
  const files = execute(
    'git',
    ['ls-files', '--', 'services/api-server', 'contracts/openapi/openapi.yaml'],
    'API 源码列表读取'
  ).split('\n').filter(Boolean).sort()
  const digest = createHash('sha256')
  for (const relative of files) {
    const file = path.join(root, relative)
    assert(existsSync(file), `API 源码缺失：${relative}`)
    digest.update(relative)
    digest.update('\0')
    digest.update(readFileSync(file))
    digest.update('\0')
  }
  return digest.digest('hex')
}

function contractVersion() {
  const contract = readFileSync(path.join(root, 'contracts/openapi/openapi.yaml'), 'utf8')
  const match = contract.match(/^\s{2}version:\s*([^\s#]+)/mu)
  assert(match, '未找到 OpenAPI 版本')
  return match[1].replace(/^['"]|['"]$/gu, '')
}

function resolveApiJar() {
  const target = path.join(root, 'services/api-server/target')
  const candidates = readdirSync(target)
    .filter((name) => /^wallpaper-api-server-.+\.jar$/u.test(name) && !name.startsWith('original-'))
    .map((name) => ({
      path: path.join(target, name),
      modifiedAt: statSync(path.join(target, name)).mtimeMs
    }))
    .sort((left, right) => right.modifiedAt - left.modifiedAt)

  assert(candidates.length > 0, '未找到 API 部署包')
  assert(statSync(candidates[0].path).size > 0, 'API 部署包为空')
  return candidates[0].path
}

function execute(command, args, label) {
  const result = spawnSync(command, args, {
    cwd: root,
    encoding: 'utf8',
    stdio: 'pipe'
  })
  if (result.error || result.status !== 0) {
    fail(`${label}失败`)
  }
  return String(result.stdout || '').trim()
}

try {
  process.env.COPYFILE_DISABLE = '1'
  process.env.COPY_EXTENDED_ATTRIBUTES_DISABLE = '1'

  const args = process.argv.slice(2).filter((value) => value !== '--')
  assert(args.length === 1, '必须提供发布版本号')
  const releaseId = args[0]
  assert(/^[0-9]{8}-[0-9]{6}-[a-f0-9]{7,40}$/u.test(releaseId), '发布版本号格式不合法')

  const worktree = execute('git', ['status', '--porcelain', '--untracked-files=no'], '工作区检查')
  assert(
    process.env.ALLOW_DIRTY_RELEASE === '1' || !worktree,
    '跟踪文件还有未提交改动；必须先提交代码，再生成发布包'
  )
  const headCommit = execute('git', ['rev-parse', 'HEAD'], '提交版本检查')
  const releaseCommit = releaseId.split('-').at(-1)
  assert(headCommit.startsWith(releaseCommit), '发布版本号必须以当前提交短哈希结尾')
  for (const declaredCommit of [process.env.GITHUB_SHA, process.env.GIT_COMMIT].filter(Boolean)) {
    assert(headCommit === declaredCommit, '声明的发布提交与当前工作区提交不一致')
  }

  const apiJar = resolveApiJar()
  const adminDirectory = path.join(root, 'apps/admin-web/dist')
  const adminIndex = path.join(adminDirectory, 'index.html')
  const migrationDirectory = path.join(root, 'services/api-server/src/main/resources/db/migration')
  assert(statSync(adminIndex).size > 0, '管理后台构建产物缺少 index.html')

  const migrationFiles = readdirSync(migrationDirectory)
    .filter((name) => /^V[0-9]+__[A-Za-z0-9_]+\.sql$/u.test(name))
    .sort((left, right) => Number(left.match(/^V([0-9]+)/u)[1]) - Number(right.match(/^V([0-9]+)/u)[1]))
  assert(migrationFiles.length > 0, '发布包缺少 Flyway 迁移')

  mkdirSync(runtimeDirectory, { recursive: true })
  const bundleDirectory = path.join(runtimeDirectory, releaseId)
  const archivePath = path.join(runtimeDirectory, `${releaseId}.tar.gz`)
  rmSync(bundleDirectory, { recursive: true, force: true })
  rmSync(archivePath, { force: true })
  mkdirSync(path.join(bundleDirectory, 'db', 'migration'), { recursive: true })

  cpSync(apiJar, path.join(bundleDirectory, 'wallpaper-api.jar'))
  cpSync(adminDirectory, path.join(bundleDirectory, 'admin'), { recursive: true })
  for (const migration of migrationFiles) {
    const packagedMigration = path.join(bundleDirectory, 'db', 'migration', migration)
    cpSync(
      path.join(migrationDirectory, migration),
      packagedMigration
    )
    chmodSync(packagedMigration, 0o644)
    assert(
      (statSync(packagedMigration).mode & 0o777) === 0o644,
      `Flyway 迁移文件权限规范化失败：${migration}`
    )
  }

  const apiSha = sha256(path.join(bundleDirectory, 'wallpaper-api.jar'))
  const sourceSha = sourceSha256()
  const apiContractVersion = contractVersion()
  const latestMigration = migrationFiles.at(-1).match(/^V([0-9]+)__/u)?.[1]
  const manifest = {
    schemaVersion: 1,
    releaseId,
    commit: headCommit,
    note: process.env.RELEASE_NOTE || null,
    createdAt: new Date().toISOString(),
    apiSha256: apiSha,
    sourceSha256: sourceSha,
    contractVersion: apiContractVersion,
    adminIndexSha256: sha256(path.join(bundleDirectory, 'admin', 'index.html')),
    migrationCount: migrationFiles.length,
    latestMigration
  }
  writeFileSync(
    path.join(bundleDirectory, 'manifest.json'),
    `${JSON.stringify(manifest, null, 2)}\n`,
    { mode: 0o644 }
  )
  writeFileSync(
    path.join(bundleDirectory, 'runtime.env'),
    [
      `QJ_DEPLOYMENT_GIT_COMMIT=${headCommit}`,
      `QJ_DEPLOYMENT_SOURCE_SHA256=${sourceSha}`,
      `QJ_DEPLOYMENT_ARTIFACT_SHA256=${apiSha}`,
      `QJ_API_CONTRACT_VERSION=${apiContractVersion}`,
      `QJ_FLYWAY_VERSION=${latestMigration}`,
      ''
    ].join('\n'),
    { mode: 0o644 }
  )

  execute('tar', ['-C', bundleDirectory, '-czf', archivePath, '.'], '完整发布包生成')
  assert(statSync(archivePath).size > 0, '完整发布包为空')
  const archiveEntries = execute('tar', ['-tzf', archivePath], '发布包内容检查')
  assert(
    !archiveEntries.split('\n').some((entry) => /(?:^|\/)\._/u.test(entry)),
    '发布包包含 macOS AppleDouble 元数据文件'
  )
  process.stdout.write(`${archivePath}\n`)
} catch (error) {
  process.stderr.write(`${error instanceof Error ? error.message : '完整发布包生成失败'}\n`)
  process.exit(1)
}
