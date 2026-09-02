#!/bin/bash
#
# Copyright 2022 N5 Technologies, Inc
#
# Licensed under the Apache License, Version 2.0.
#
# RUMI-412 — prove the MCP tools actually work against the REST service.
#
# Why this exists
# ---------------
# Every MCP test mocks the HTTP layer (respx), so they assert "we would have
# sent this request" and nothing more. Every REST test drives the resources
# directly. Nothing anywhere put the two together, which means a field-name
# mismatch between the MCP's JSON and a REST DTO passed the entire suite and
# only failed on a live agent box -- where, as ci/verify-generated-app.sh puts
# it, "the builder reports success, the app does not build, and the agent
# concludes its own code is wrong".
#
# The gap was not hypothetical. Writing this found that create_app's app_dir
# must already exist and that the app lands at <app_dir>/<prefix>-<name>, both
# of which the mocked tests had no way to know.
#
# What it does
#   1. builds SDK + REST
#   2. boots the JAX-RS layer on a loopback port
#   3. drives the REAL MCP tool implementations against it (drive_tools.py)
#   4. builds a model ENTIRELY through apply_model, then compiles the generated
#      app -- the composition the unit suites cannot reach: batch-written ADML
#      -> code generation -> Java that references the generated accessors
#   5. tears the service down
#
# Exit code is non-zero on the first failure.
set -euo pipefail

REPO_DIR="$(cd "$(dirname "${BASH_SOURCE[0]}")/.." && pwd)"
MVN="${MVN:-mvn}"
PORT="${PORT:-13200}"
WORK_DIR="${WORK_DIR:-$(mktemp -d)}"
VENV="${VENV:-${REPO_DIR}/nvx-rumi-appbuilder-mcp/.venv}"

say() { echo "==> $*"; }

cleanup() {
  if [[ -n "${SERVER_PID:-}" ]]; then kill "${SERVER_PID}" 2>/dev/null || true; fi
}
trap cleanup EXIT

say "Building SDK + REST"
"${MVN}" -q -Pneeve install -DskipTests -f "${REPO_DIR}/pom.xml"

say "Resolving the REST classpath"
"${MVN}" -q -pl nvx-rumi-appbuilder-rest -Pneeve dependency:build-classpath \
  -Dmdep.outputFile="${WORK_DIR}/cp.txt" -f "${REPO_DIR}/pom.xml"
CP="${REPO_DIR}/nvx-rumi-appbuilder-rest/target/classes:${REPO_DIR}/nvx-rumi-appbuilder-sdk/target/classes:$(cat "${WORK_DIR}/cp.txt")"

# Boot only the JAX-RS layer. Main's full Rumi lifecycle needs a runtime this
# check has no business requiring; the resources are what the MCP talks to.
cat > "${WORK_DIR}/Launcher.java" <<'EOF'
import com.neeve.appbuilder.rest.HttpServer;
import com.neeve.appbuilder.rest.Main;
public class Launcher {
    public static void main(String[] a) throws Exception {
        HttpServer s = new HttpServer("127.0.0.1", Integer.parseInt(a[0]), new Main.ResourceConfig());
        s.start();
        System.out.println("READY");
        Thread.currentThread().join();
    }
}
EOF
javac -cp "${CP}" -d "${WORK_DIR}" "${WORK_DIR}/Launcher.java"

say "Starting the REST service on ${PORT}"
java -cp "${WORK_DIR}:${CP}" Launcher "${PORT}" > "${WORK_DIR}/server.log" 2>&1 &
SERVER_PID=$!
for _ in $(seq 1 60); do grep -q READY "${WORK_DIR}/server.log" 2>/dev/null && break; sleep 0.5; done
grep -q READY "${WORK_DIR}/server.log" || { cat "${WORK_DIR}/server.log"; echo "REST service did not start"; exit 1; }

if [[ ! -x "${VENV}/bin/python" ]]; then
  say "Creating the MCP venv"
  python3 -m venv "${VENV}"
  "${VENV}/bin/pip" -q install -e "${REPO_DIR}/nvx-rumi-appbuilder-mcp"
fi

say "Driving the real MCP tools against the real service"
BASE="http://127.0.0.1:${PORT}" "${VENV}/bin/python" "${REPO_DIR}/ci/mcp-e2e/drive_tools.py"

say "Building a model through apply_model, then compiling the generated app"
RUMI_VERSION="$("${MVN}" -q -B -Dstyle.color=never -N -DforceStdout \
  help:evaluate -Dexpression=nvx.rumi.version -f "${REPO_DIR}/pom.xml" | tr -d '\r' | sed 's/\x1b\[[0-9;]*m//g' | tail -1)"
APP="$(BASE="http://127.0.0.1:${PORT}" RUMI_VERSION="${RUMI_VERSION}" \
  "${VENV}/bin/python" "${REPO_DIR}/ci/mcp-e2e/build_model_and_compile.py" 2>/dev/null | tail -1)"
# `test`, not `package`: package pulls the xar plugin, which is not what this
# check is about (ci/verify-generated-app.sh does the same for the same reason).
( cd "${APP}" && "${MVN}" -q test -DfailIfNoTests=false )

say "MCP tools verified end to end against the REST service, and a model built"
say "entirely through apply_model code-generates and compiles."
