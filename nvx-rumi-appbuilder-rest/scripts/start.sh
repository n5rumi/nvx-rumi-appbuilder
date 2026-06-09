#!/bin/bash
#
# Copyright 2022 N5 Technologies, Inc
#
# Licensed under the Apache License, Version 2.0.
#
# Start the Rumi App Builder REST service (direct xvm.sh launch — the
# standalone / non-controller path).
#
# Usage: start.sh [--foreground]
#   (default)      launch in the background (nohup); returns immediately.
#   --foreground   run in the foreground — for a supervisor (e.g. systemd
#                  with Type=simple) that owns the process lifecycle.
#
# Requires JAVA_HOME to point at a Java 17+ runtime (the wrapper runs the JVM
# from it; see conf/wrapper.env.vars set.NVJRE=%JAVA_HOME%).
#
# Stop the service with the sibling stop.sh. This script lives in the release's
# scripts/ dir, so it locates bin/xvm.sh relative to itself and works wherever
# the release is installed.
set -euo pipefail

FOREGROUND=no
case "${1:-}" in
    --foreground|-f) FOREGROUND=yes ;;
    "")              ;;
    *) echo "usage: $(basename "$0") [--foreground]" >&2; exit 1 ;;
esac

RELEASE_DIR="$(cd "$(dirname "$0")/.." && pwd)"
readonly XVM="${RELEASE_DIR}/bin/xvm.sh"
readonly XVM_NAME="appbuilder-rest"

[[ -n "${JAVA_HOME:-}" && -x "${JAVA_HOME}/bin/java" ]] \
    || { echo "error: JAVA_HOME must point at a Java 17+ runtime" >&2; exit 1; }
[[ -x "${XVM}" ]] || { echo "error: launcher not found: ${XVM}" >&2; exit 1; }

cd "${RELEASE_DIR}"

if [[ "${FOREGROUND}" == "yes" ]]; then
    exec "${XVM}" "${XVM_NAME}"
fi

# Background: nohup the wrapper. Graceful stop is discovery-based (stop.sh →
# xvm --action stop), so no pid file is needed to stop it later.
LOG="${RUMI_APPBUILDER_REST_LOGFILE:-${RELEASE_DIR}/${XVM_NAME}.out}"
mkdir -p "$(dirname "${LOG}")"
nohup "${XVM}" "${XVM_NAME}" >> "${LOG}" 2>&1 &
echo "Started ${XVM_NAME} (pid $!). Logs: ${LOG}"
echo "Stop it with: $(dirname "$0")/stop.sh"
