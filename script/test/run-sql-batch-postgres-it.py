"""Run SQL script transactions on a newly owned PostgreSQL fixture; fail on missing/skipped reports."""
import os
from pathlib import Path
import secrets
import shutil
import subprocess
import sys
import time
import uuid
import xml.etree.ElementTree as ET

ROOT = Path(__file__).resolve().parents[2]
SUITE = "ai.chat2db.community.domain.core.impl.task.imports.SQLScriptTransactionPostgreSQLIT"
REPORT = ROOT / "chat2db-community-server/chat2db-community-domain/chat2db-community-domain-core/target/surefire-reports" / ("TEST-" + SUITE + ".xml")


def docker(*args, env=None):
    result = subprocess.run(["docker", *args], env=env, capture_output=True, text=True)
    if result.returncode:
        raise RuntimeError("Docker " + args[0] + " failed (exit " + str(result.returncode) + ")")
    return result.stdout.strip()


def verify_report(path):
    suite = ET.parse(path).getroot()
    counts = {key: int(suite.attrib.get(key, 0)) for key in ("tests", "failures", "errors", "skipped")}
    if suite.attrib.get("name") != SUITE or counts != {"tests": 8, "failures": 0, "errors": 0, "skipped": 0}:
        raise RuntimeError("Invalid required PostgreSQL report: " + str(counts))
    return counts


def cleanup_container(cid, marker):
    try:
        owner = docker("inspect", "--format", '{{index .Config.Labels "chat2db.it.owner"}}', cid)
        if owner != marker:
            raise RuntimeError("Fixture ownership mismatch")
        docker("rm", "--force", "--volumes", cid)
        print("REMOVED " + cid, flush=True)
        return 0
    except Exception as error:
        print("CLEANUP_FAILED " + cid + ": " + type(error).__name__, flush=True)
        return 1


def main():
    marker = "c2d-script-tx-" + uuid.uuid4().hex[:12]
    cid = None
    env = os.environ.copy()
    env["POSTGRES_PASSWORD"] = secrets.token_hex(24)
    env["POSTGRES_DB"] = "c2d_script_tx_" + uuid.uuid4().hex[:12]
    primary_exit = 0
    cleanup_exit = 0
    try:
        cid = docker("run", "--detach", "--name", marker, "--label", "chat2db.it.owner=" + marker,
                     "--publish", "127.0.0.1::5432", "--env", "POSTGRES_PASSWORD", "--env", "POSTGRES_DB",
                     "postgres:16", env=env)
        mapped = docker("port", cid, "5432/tcp")
        if not mapped.startswith("127.0.0.1:"):
            raise RuntimeError("Fixture is not published on loopback")
        port = mapped.rsplit(":", 1)[1]
        print("CREATED " + cid + " loopback-port=" + port + " owner=" + marker, flush=True)
        deadline = time.monotonic() + 75
        while True:
            ready = subprocess.run(["docker", "exec", cid, "pg_isready", "-U", "postgres"], capture_output=True)
            if ready.returncode == 0:
                break
            if time.monotonic() >= deadline:
                raise RuntimeError("PostgreSQL fixture readiness timed out")
            time.sleep(1)
        env["C2D_SCRIPT_TX_JDBC_URL"] = "jdbc:postgresql://127.0.0.1:" + port + "/" + env["POSTGRES_DB"]
        env["C2D_SCRIPT_TX_PASSWORD"] = env["POSTGRES_PASSWORD"]
        # Only this suite's prior report is removed; an old report cannot make a missing run pass.
        if REPORT.exists():
            REPORT.unlink()
        command = [shutil.which("mvn") or "mvn", "-B", "-f", "chat2db-community-server/pom.xml",
                   "-pl", ":chat2db-community-domain-core", "-am", "-Dmaven.test.skip=false", "-DskipTests=false",
                   "-Dtest=SQLScriptTransactionPostgreSQLIT", "-Dsurefire.failIfNoSpecifiedTests=false",
                   "-Dmaven.test.failure.ignore=false", "test"]
        print("COMMAND " + " ".join(command), flush=True)
        primary_exit = subprocess.run(command, cwd=ROOT, env=env).returncode
        if primary_exit == 0:
            print("POSTGRES_SQL_SCRIPT_PASSED " + str(verify_report(REPORT)), flush=True)
    except Exception as error:
        primary_exit = 1
        print("POSTGRES_SQL_SCRIPT_FAILED " + type(error).__name__, flush=True)
    finally:
        if cid is not None:
            cleanup_exit = cleanup_container(cid, marker)
    return primary_exit or cleanup_exit


if __name__ == "__main__":
    sys.exit(main())
