#!/bin/bash
#
# Copyright 2022 N5 Technologies, Inc
#
# Licensed under the Apache License, Version 2.0.
#
# Stop the Rumi App Builder REST service gracefully (the standalone /
# non-controller path).
#
# Uses 'xvm.sh <name> --action stop': com.neeve.server.Main discovers the
# running XVM via the configured discovery descriptor, connects to its admin
# port, and issues a clean shutdown — the XVM stops its own JVM (no orphan).
# Killing the launcher pid does NOT do this, because the wrapper forks the JVM.
#
# Requires JAVA_HOME to point at a Java 17+ runtime. Lives in the release's
# scripts/ dir, so it locates bin/xvm.sh relative to itself.
set -euo pipefail

RELEASE_DIR="$(cd "$(dirname "$0")/.." && pwd)"
readonly XVM="${RELEASE_DIR}/bin/xvm.sh"
readonly XVM_NAME="appbuilder-rest"

[[ -n "${JAVA_HOME:-}" && -x "${JAVA_HOME}/bin/java" ]] \
    || { echo "error: JAVA_HOME must point at a Java 17+ runtime" >&2; exit 1; }
[[ -x "${XVM}" ]] || { echo "error: launcher not found: ${XVM}" >&2; exit 1; }

cd "${RELEASE_DIR}"
exec "${XVM}" "${XVM_NAME}" --action stop
