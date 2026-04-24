#!/bin/bash
#
# Copyright 2022 N5 Technologies, Inc
#
# Licensed under the Apache License, Version 2.0.
#
# Stop the Rumi App Builder MCP server by PID. Invoked by the
# installer during upgrade / uninstall; also safe for operators to
# call directly.
#
# Usage: shutdown.sh <pid-file>
set -euo pipefail

PID_FILE="${1:?usage: shutdown.sh <pid-file>}"
[[ -f "${PID_FILE}" ]] || { echo "No PID file at ${PID_FILE}; nothing to stop."; exit 0; }

PID="$(cat "${PID_FILE}" 2>/dev/null || true)"
[[ -n "${PID}" ]] || { rm -f "${PID_FILE}"; exit 0; }

echo "Stopping MCP server (pid ${PID})"
if kill -0 "${PID}" 2>/dev/null; then
    pkill -TERM -P "${PID}" 2>/dev/null || true
    kill  -TERM "${PID}"    2>/dev/null || true
fi

# Wait up to 15s.
waited=0
while [[ ${waited} -lt 15 ]] && kill -0 "${PID}" 2>/dev/null; do
    sleep 1
    waited=$((waited + 1))
done

if kill -0 "${PID}" 2>/dev/null; then
    echo "Did not exit cleanly; sending KILL."
    pkill -KILL -P "${PID}" 2>/dev/null || true
    kill  -KILL "${PID}"    2>/dev/null || true
fi

rm -f "${PID_FILE}"
