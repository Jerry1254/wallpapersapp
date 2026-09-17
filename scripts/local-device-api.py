#!/usr/bin/env python3
"""Build, deploy, and verify the immutable LOCAL_DEVICE API artifact."""

import hashlib
import json
import os
import re
import shlex
import shutil
import signal
import subprocess
import sys
import time
import urllib.error
import urllib.request
from pathlib import Path


ROOT = Path(__file__).resolve().parent.parent
API_DIR = ROOT / "services/api-server"
SOURCE_JAR = API_DIR / "target/wallpaper-api-server-0.1.0-SNAPSHOT.jar"
RUNTIME = ROOT / ".runtime/android-api12"
ENV_FILE = RUNTIME / "compose.env"
STORAGE = (RUNTIME / "storage").resolve()
STATE_FILE = RUNTIME / "api-process-state.json"
LOG_FILE = RUNTIME / "api-local-device-current.log"
PORT = 8083
ENVIRONMENT_ID = "LOCAL_DEVICE"
DEPLOYMENT_STAGE = "LOCAL"
ALLOWED_SCOPES = [
    "com.qingjing.bizhi.local",
    "com.qingjing.bizhi.internal",
    "com.qingjing.bizhi.lab",
]


def fail(message):
    raise SystemExit(message)


def command(args, cwd=ROOT, capture=False, env=None):
    return subprocess.run(
        [str(arg) for arg in args],
        cwd=cwd,
        check=True,
        text=True,
        capture_output=capture,
        env=env,
    )


def output(args, cwd=ROOT):
    return command(args, cwd=cwd, capture=True).stdout.strip()


def load_environment():
    if not ENV_FILE.is_file() or not STORAGE.is_dir():
        fail("LOCAL_DEVICE compose.env or storage directory is missing")
    values = {}
    for raw in ENV_FILE.read_text().splitlines():
        line = raw.strip()
        if not line or line.startswith("#") or "=" not in line:
            continue
        key, value = line.split("=", 1)
        parsed = shlex.split(value)
        values[key] = parsed[0] if parsed else ""
    expected = {
        "QJ_MYSQL_HOST_PORT": "3311",
        "QJ_MYSQL_DATABASE": "wallpaper_android",
        "QJ_REDIS_HOST_PORT": "6391",
        "QJ_REDIS_PORT": "6391",
    }
    for key, value in expected.items():
        if values.get(key) != value:
            fail(f"LOCAL_DEVICE guard rejected {key}={values.get(key)!r}; expected {value!r}")
    if "127.0.0.1:3311/wallpaper_android" not in values.get("QJ_MYSQL_URL", ""):
        fail("LOCAL_DEVICE guard rejected the MySQL URL")
    configured_storage = Path(values.get("QJ_STORAGE_ROOT", "")).expanduser().resolve()
    if configured_storage != STORAGE:
        fail(f"LOCAL_DEVICE guard rejected storage {configured_storage}; expected {STORAGE}")
    return values


def build_environment():
    media_tools = ROOT / ".runtime/media-tools"
    ffmpeg = media_tools / "ffmpeg"
    ffprobe = media_tools / "ffprobe"
    if not ffmpeg.is_file() or not ffprobe.is_file():
        fail("project-pinned ffmpeg/ffprobe is missing from .runtime/media-tools")
    bridge = Path.home() / ".cache/qingjing-wallpaper/media-tools"
    if bridge.is_symlink():
        fail(f"refusing unsafe media-tool bridge: {bridge}")
    bridge.mkdir(parents=True, exist_ok=True, mode=0o700)
    bridge.chmod(0o700)
    bridged = {}
    for name, source in (("ffmpeg", ffmpeg), ("ffprobe", ffprobe)):
        target = bridge / name
        if target.exists() or target.is_symlink():
            target.unlink()
        target.symlink_to(source)
        bridged[name] = target
    values = os.environ.copy()
    values.update({
        "LC_ALL": "en_US.UTF-8",
        "LANG": "en_US.UTF-8",
        "PATH": str(bridge) + os.pathsep + values.get("PATH", ""),
        "QJ_FFMPEG": str(bridged["ffmpeg"]),
        "QJ_FFPROBE": str(bridged["ffprobe"]),
    })
    return values


def contract_version():
    text = (ROOT / "contracts/openapi/openapi.yaml").read_text()
    match = re.search(r"(?m)^  version:\s*([^\s#]+)", text)
    if not match:
        fail("OpenAPI info.version was not found")
    return match.group(1).strip("'\"")


def flyway_version():
    versions = []
    for path in (API_DIR / "src/main/resources/db/migration").glob("V*__*.sql"):
        match = re.match(r"V(\d+)__", path.name)
        if match:
            versions.append(int(match.group(1)))
    if not versions:
        fail("no Flyway migration was found")
    return str(max(versions))


def git_commit():
    return output(["git", "rev-parse", "HEAD"])


def require_committed_runtime_sources():
    changed = output([
        "git", "status", "--porcelain", "--",
        "services/api-server", "contracts/openapi", "scripts/local-device-api.py",
    ])
    if changed:
        fail(
            "LOCAL_DEVICE deployment requires committed API, contract, and deploy-script changes:\n"
            + changed
        )


def require_committed_api_sources():
    changed = output([
        "git", "status", "--porcelain", "--",
        "services/api-server", "contracts/openapi/openapi.yaml",
    ])
    if changed:
        fail("UNCOMMITTED_API_CHANGES\n" + changed)


def sha256(path):
    digest = hashlib.sha256()
    with path.open("rb") as stream:
        for chunk in iter(lambda: stream.read(1024 * 1024), b""):
            digest.update(chunk)
    return digest.hexdigest()


def source_sha256():
    tracked = output([
        "git", "ls-files", "--",
        "services/api-server", "contracts/openapi/openapi.yaml",
    ]).splitlines()
    digest = hashlib.sha256()
    for relative in sorted(tracked):
        path = ROOT / relative
        digest.update(relative.encode())
        digest.update(b"\0")
        digest.update(path.read_bytes())
        digest.update(b"\0")
    return digest.hexdigest()


def listener_pid():
    result = subprocess.run(
        ["lsof", "-t", f"-iTCP:{PORT}", "-sTCP:LISTEN"],
        text=True,
        capture_output=True,
    )
    pids = [line for line in result.stdout.splitlines() if line]
    if len(pids) > 1:
        fail(f"multiple processes listen on {PORT}: {pids}")
    return int(pids[0]) if pids else None


def running_jar(pid):
    executable = Path(output(["ps", "-p", str(pid), "-o", "comm="])).name
    args = shlex.split(output(["ps", "-p", str(pid), "-o", "args="]))
    if executable != "java" or "-jar" not in args:
        fail(f"port {PORT} is owned by an unexpected process; refusing to stop it")
    jar_name = Path(args[args.index("-jar") + 1]).name
    jar = RUNTIME / jar_name
    if not jar_name.startswith("api-") or not jar.is_file():
        fail(f"port {PORT} Java process does not use a managed LOCAL_DEVICE artifact: {jar_name}")
    return jar


def read_json(url, timeout=3):
    try:
        with urllib.request.urlopen(url, timeout=timeout) as response:
            return response.status, json.loads(response.read().decode())
    except urllib.error.HTTPError as error:
        body = error.read().decode()
        return error.code, json.loads(body) if body else {}


def wait_until_ready(process=None):
    for _ in range(120):
        if process is not None and process.poll() is not None:
            fail(f"LOCAL_DEVICE API exited with code {process.returncode}; inspect {LOG_FILE}")
        try:
            status, body = read_json(f"http://127.0.0.1:{PORT}/actuator/health/readiness", 1)
            if status == 200 and body.get("status") == "UP":
                return
        except (OSError, ValueError, json.JSONDecodeError):
            pass
        time.sleep(0.25)
    fail(f"LOCAL_DEVICE API readiness timed out; inspect {LOG_FILE}")


def live_report():
    pid = listener_pid()
    if pid is None:
        fail(f"LOCAL_DEVICE API is not listening on {PORT}")
    jar = running_jar(pid)
    status, readiness = read_json(f"http://127.0.0.1:{PORT}/actuator/health/readiness")
    if status != 200 or readiness.get("status") != "UP":
        fail(f"LOCAL_DEVICE readiness is not UP: HTTP {status} {readiness}")
    status, info = read_json(f"http://127.0.0.1:{PORT}/actuator/info")
    if status != 200:
        fail(f"LOCAL_DEVICE actuator info is unavailable: HTTP {status}")
    app = info.get("app", {})
    actual_hash = sha256(jar)
    expected = {
        "environment-id": ENVIRONMENT_ID,
        "deployment-stage": DEPLOYMENT_STAGE,
        "contract-version": contract_version(),
        "flyway-version": flyway_version(),
        "source-sha256": source_sha256(),
        "artifact-sha256": actual_hash,
    }
    mismatches = {
        key: {"expected": value, "actual": app.get(key)}
        for key, value in expected.items()
        if app.get(key) != value
    }
    if mismatches:
        fail("STALE_OR_WRONG_API " + json.dumps(mismatches, ensure_ascii=False, sort_keys=True))
    return {
        "environment": app["environment-id"],
        "pid": pid,
        "jar": jar.name,
        "gitCommit": app.get("git-commit"),
        "sourceSha256": app["source-sha256"],
        "contractVersion": app["contract-version"],
        "artifactSha256": actual_hash,
        "flywayVersion": app["flyway-version"],
        "readiness": readiness["status"],
        "mysql": "127.0.0.1:3311/wallpaper_android",
        "redis": "127.0.0.1:6391",
        "storage": str(STORAGE.relative_to(ROOT)),
    }


def deploy():
    environment = load_environment()
    require_committed_runtime_sources()
    commit = git_commit()
    contract = contract_version()
    migration = flyway_version()
    source_hash = source_sha256()

    tools_environment = build_environment()
    command(
        ["./mvnw", "--batch-mode", "--no-transfer-progress", "verify"],
        cwd=API_DIR,
        env=tools_environment,
    )
    if not SOURCE_JAR.is_file():
        fail("Maven completed without producing the API jar")
    artifact_hash = sha256(SOURCE_JAR)
    artifact = RUNTIME / f"api-{contract}-{commit[:12]}-{artifact_hash[:12]}.jar"
    temporary = artifact.with_suffix(".jar.tmp")
    shutil.copyfile(SOURCE_JAR, temporary)
    temporary.chmod(0o600)
    os.replace(temporary, artifact)

    old_pid = listener_pid()
    if old_pid is not None:
        running_jar(old_pid)
        os.kill(old_pid, signal.SIGTERM)
        for _ in range(80):
            if listener_pid() is None:
                break
            time.sleep(0.25)
        else:
            fail(f"previous LOCAL_DEVICE API did not release port {PORT}")

    process_environment = os.environ.copy()
    process_environment.update(environment)
    process_environment.update({
        "LC_ALL": "en_US.UTF-8",
        "LANG": "en_US.UTF-8",
        "SPRING_PROFILES_ACTIVE": "local",
        "SERVER_PORT": str(PORT),
        "QJ_DB_POOL_SIZE": "2",
        "QJ_ENVIRONMENT_ID": ENVIRONMENT_ID,
        "QJ_DEPLOYMENT_STAGE": DEPLOYMENT_STAGE,
        "QJ_API_CONTRACT_VERSION": contract,
        "QJ_DEPLOYMENT_GIT_COMMIT": commit,
        "QJ_DEPLOYMENT_SOURCE_SHA256": source_hash,
        "QJ_DEPLOYMENT_ARTIFACT_SHA256": artifact_hash,
        "QJ_FLYWAY_VERSION": migration,
        "QJ_FFMPEG": tools_environment["QJ_FFMPEG"],
        "QJ_FFPROBE": tools_environment["QJ_FFPROBE"],
        "SPRING_APPLICATION_JSON": json.dumps({
            "qingjing": {"device": {"allowed-android-scopes": ALLOWED_SCOPES}}
        }, separators=(",", ":")),
    })
    java = shutil.which("java")
    if not java:
        fail("Java is not available")
    with LOG_FILE.open("wb") as log:
        process = subprocess.Popen(
            [java, "-Xmx512m", "-Dfile.encoding=UTF-8", "-jar", str(artifact)],
            cwd=ROOT,
            env=process_environment,
            stdout=log,
            stderr=subprocess.STDOUT,
            start_new_session=True,
        )
    wait_until_ready(process)
    report = live_report()
    STATE_FILE.write_text(json.dumps(report, ensure_ascii=False, indent=2) + "\n")
    print(json.dumps(report, ensure_ascii=False, indent=2))


def main():
    if len(sys.argv) != 2 or sys.argv[1] not in {"deploy", "status"}:
        fail("usage: scripts/local-device-api.py [deploy|status]")
    load_environment()
    if sys.argv[1] == "deploy":
        deploy()
    else:
        require_committed_api_sources()
        print(json.dumps(live_report(), ensure_ascii=False, indent=2))


if __name__ == "__main__":
    main()
