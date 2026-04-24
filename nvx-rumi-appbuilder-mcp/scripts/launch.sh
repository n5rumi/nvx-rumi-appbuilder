#!/bin/bash
#
# Copyright 2022 N5 Technologies, Inc
#
# Licensed under the Apache License, Version 2.0.
#
# Foreground launcher for the Rumi App Builder MCP server. Invoked by
# install.sh under nohup when the installer daemonizes the service.
#
# Expects this file to live at <release>/scripts/launch.sh, adjacent
# to <release>/venv/ created at install time.
#
# Config file path passed as $1 (absolute path to the per-install
# appbuilder-mcp.conf). Every RUMI_APPBUILDER_* key in that file
# becomes an env var visible to the Python process.
set -euo pipefail

CONF="${1:?usage: launch.sh <path-to-appbuilder-mcp.conf>}"
RELEASE_DIR="$(cd "$(dirname "$0")/.." && pwd)"
VENV_BIN="${RELEASE_DIR}/venv/bin"

[[ -x "${VENV_BIN}/python" ]] || { echo "venv python missing at ${VENV_BIN}/python" >&2; exit 1; }
[[ -f "${CONF}" ]]             || { echo "config file not found: ${CONF}" >&2; exit 1; }

# Load keys from the conf file as env vars.
set -a
# shellcheck disable=SC1090
source "${CONF}"
set +a

# Defaults if the conf omits a key.
: "${RUMI_APPBUILDER_MCP_PORT:=3201}"
: "${RUMI_APPBUILDER_MCP_HOST:=127.0.0.1}"
: "${RUMI_APPBUILDER_REST_URL:=http://127.0.0.1:3200}"
: "${RUMI_APPBUILDER_MCP_TRANSPORT:=streamable-http}"

exec "${VENV_BIN}/python" -m rumi_appbuilder_mcp \
    --transport "${RUMI_APPBUILDER_MCP_TRANSPORT}" \
    --host      "${RUMI_APPBUILDER_MCP_HOST}" \
    --port      "${RUMI_APPBUILDER_MCP_PORT}" \
    --rest-url  "${RUMI_APPBUILDER_REST_URL}"
