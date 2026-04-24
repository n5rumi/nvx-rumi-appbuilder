#!/bin/bash
#
# Copyright 2022 N5 Technologies, Inc
#
# Licensed under the Apache License, Version 2.0.
#
# End-to-end smoke test for the MCP installer.
#
# Builds the dist tarball, installs under a throwaway prefix, probes
# the MCP port, verifies an upgrade-in-place preserves data/, then
# uninstalls without --purge and confirms data/ survives.
#
# Run from the module root:
#   bash tests/install_smoke.sh
set -euo pipefail

readonly MODULE_DIR="$(cd "$(dirname "$0")/.." && pwd)"
readonly ARTIFACT="rumi-appbuilder-mcp"
readonly PORT=13201

cd "${MODULE_DIR}"

# 1. Build the tarball if missing.
if [[ -z "$(ls "${MODULE_DIR}/target/${ARTIFACT}"-*.tar.gz 2>/dev/null || true)" ]]; then
    echo "==> Building dist tarball"
    bash build/build-dist.sh
fi
TARBALL="$(ls "${MODULE_DIR}/target/${ARTIFACT}"-*.tar.gz | head -1)"

# 2. Throwaway install prefix.
PREFIX="$(mktemp -d)"
cleanup() {
    pkill -9 -f "rumi_appbuilder_mcp" 2>/dev/null || true
    rm -rf "${PREFIX}"
}
trap cleanup EXIT

# 3. Install.
echo "==> Installing into ${PREFIX}"
"${MODULE_DIR}/install/install_template.sh" \
    --install-dir "${PREFIX}" \
    --local-dist "${TARBALL}" \
    --port "${PORT}" \
    --force --verbose

# 4. Port should be listening.
echo "==> Probing MCP port ${PORT}"
python3 -c "import socket; s=socket.socket(); s.settimeout(2); s.connect(('127.0.0.1', ${PORT})); s.close()" \
    || { echo "FAIL: port ${PORT} not listening"; exit 1; }

# 5. Upgrade path — re-run install, confirm data/ preserved.
STAMP="${PREFIX}/${ARTIFACT}/data/logs/.smoke-stamp"
touch "${STAMP}"
echo "==> Re-running install to validate the upgrade path"
"${MODULE_DIR}/install/install_template.sh" \
    --install-dir "${PREFIX}" \
    --local-dist "${TARBALL}" \
    --port "${PORT}" \
    --force --verbose >/dev/null
[[ -f "${STAMP}" ]] || { echo "FAIL: upgrade clobbered data/"; exit 1; }
python3 -c "import socket; s=socket.socket(); s.settimeout(2); s.connect(('127.0.0.1', ${PORT})); s.close()" \
    || { echo "FAIL: port ${PORT} down after upgrade"; exit 1; }
echo "    upgrade preserved data/ and MCP came back on :${PORT}"

# 6. Uninstall (no --purge). data/ should survive; binaries should not.
echo "==> Uninstalling (data preserved)"
"${MODULE_DIR}/install/install_template.sh" \
    --install-dir "${PREFIX}" --uninstall --force
[[ -d "${PREFIX}/${ARTIFACT}/data" ]] || { echo "FAIL: data/ was removed without --purge"; exit 1; }
[[ ! -L "${PREFIX}/${ARTIFACT}/current" ]] || { echo "FAIL: current/ symlink still exists"; exit 1; }
echo "    data/ preserved, binaries gone."

echo
echo "==> MCP install smoke PASSED."
