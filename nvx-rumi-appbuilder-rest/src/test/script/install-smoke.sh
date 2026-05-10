#!/bin/bash
#
# Copyright 2022 N5 Technologies, Inc
#
# Licensed under the Apache License, Version 2.0.
#
# End-to-end smoke test for install_template.sh.
#
# Builds the dist tarball for the host arch, runs install.sh against it
# under a throwaway prefix, probes /health, upgrades in place to confirm
# the data dir is preserved, and uninstalls cleanly.
#
# Run from the module root:
#   bash src/test/script/install-smoke.sh
#
# Or via the full reactor:
#   mvn -pl nvx-rumi-appbuilder-rest package -DskipTests \
#     && bash nvx-rumi-appbuilder-rest/src/test/script/install-smoke.sh
#
# Skipped by default in the main test run — this is manually invoked.
set -euo pipefail

readonly MODULE_DIR="$(cd "$(dirname "$0")/../../.." && pwd)"
readonly ARTIFACT="nvx-rumi-appbuilder-rest"
readonly PORT=13200

# ---- Detect arch ----------------------------------------------------
case "$(uname -s)_$(uname -m)" in
    Darwin_arm64)    ARCH="osx-arm-64" ;;
    Darwin_x86_64)   ARCH="osx-x86-64" ;;
    Linux_x86_64)    ARCH="linux-x86-64" ;;
    Linux_aarch64)   ARCH="linux-arm-64" ;;
    *) echo "Unsupported arch: $(uname -s)/$(uname -m)"; exit 1 ;;
esac

# ---- Java required --------------------------------------------------
[[ -n "${JAVA_HOME:-}" ]] || { echo "JAVA_HOME is not set"; exit 1; }

# ---- Build the tarball if missing -----------------------------------
cd "${MODULE_DIR}/.."
if [[ -z "$(ls "${MODULE_DIR}/target/${ARTIFACT}"-*-"${ARCH}.tar.gz" 2>/dev/null)" ]]; then
    echo "==> Building dist tarball for ${ARCH}"
    mvn -pl nvx-rumi-appbuilder-rest -Pdist -Darch="${ARCH}" package -DskipTests >/dev/null
fi
TARBALL="$(ls "${MODULE_DIR}/target/${ARTIFACT}"-*-"${ARCH}.tar.gz" | head -1)"
[[ -f "${TARBALL}" ]] || { echo "No tarball at ${MODULE_DIR}/target/"; exit 1; }

# ---- Throwaway install prefix ---------------------------------------
PREFIX="$(mktemp -d)"
trap 'echo "==> Cleaning up"; pkill -9 -f "com.neeve.server.Main" 2>/dev/null || true; rm -rf "${PREFIX}"' EXIT

# Clean any stale NVJRE from the shell.
unset NVJRE

# ---- Install --------------------------------------------------------
echo "==> Installing into ${PREFIX}"
"${MODULE_DIR}/install/install_template.sh" \
    --install-dir "${PREFIX}" \
    --from "${TARBALL}" \
    --port "${PORT}" \
    --force \
    --verbose

# ---- Probe /health --------------------------------------------------
echo "==> Probing /health on port ${PORT}"
HEALTH="$(curl -fsS -m 5 "http://127.0.0.1:${PORT}/health")" || { echo "FAIL: /health"; exit 1; }
[[ "${HEALTH}" == *'"status":"ok"'* ]] || { echo "FAIL: unexpected /health body: ${HEALTH}"; exit 1; }
echo "    ${HEALTH}"

# ---- Upgrade-in-place path (re-run install, confirm data preserved)
STAMP_FILE="${PREFIX}/rumi-appbuilder-rest/data/logs/.smoke-stamp"
touch "${STAMP_FILE}"
echo "==> Re-running install to validate the upgrade path"
"${MODULE_DIR}/install/install_template.sh" \
    --install-dir "${PREFIX}" \
    --from "${TARBALL}" \
    --port "${PORT}" \
    --force \
    --verbose >/dev/null
[[ -f "${STAMP_FILE}" ]] || { echo "FAIL: upgrade clobbered the data dir (stamp missing)"; exit 1; }
curl -fsS -m 5 "http://127.0.0.1:${PORT}/health" >/dev/null || { echo "FAIL: /health after upgrade"; exit 1; }
echo "    upgrade preserved data/ and service came back on :${PORT}"

# ---- Uninstall (without --purge — data should survive) --------------
echo "==> Uninstalling (data should be preserved)"
"${MODULE_DIR}/install/install_template.sh" \
    --install-dir "${PREFIX}" \
    --uninstall \
    --force
[[ -d "${PREFIX}/rumi-appbuilder-rest/data" ]] || { echo "FAIL: data/ was removed without --purge"; exit 1; }
[[ ! -L "${PREFIX}/rumi-appbuilder-rest/current" ]] || { echo "FAIL: current/ symlink still exists"; exit 1; }
echo "    data/ preserved, binaries gone."

echo
echo "==> Install smoke PASSED."
