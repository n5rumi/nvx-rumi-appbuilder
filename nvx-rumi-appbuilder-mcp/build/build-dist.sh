#!/bin/bash
#
# Copyright 2022 N5 Technologies, Inc
#
# Licensed under the Apache License, Version 2.0.
#
# Build the MCP-only deployable tarball.
#
# Produces:
#   target/rumi-appbuilder-mcp-<version>.tar.gz
#
# Layout inside the tarball:
#   rumi-appbuilder-mcp/
#   ├── wheel/rumi_appbuilder_mcp-<version>-py3-none-any.whl
#   ├── conf/appbuilder-mcp.conf.sample
#   ├── scripts/launch.sh
#   ├── scripts/shutdown.sh
#   ├── install.sh             # __VERSION__-substituted installer
#   └── VERSION
#
# Invocation:
#   bash build/build-dist.sh
#
# Requires a Python 3.11+ interpreter with the 'build' package. Run
# 'pip install build' once if missing.
set -euo pipefail

MODULE_DIR="$(cd "$(dirname "$0")/.." && pwd)"
cd "${MODULE_DIR}"

VERSION="$(python3 -c 'import tomllib, pathlib; print(tomllib.loads(pathlib.Path("pyproject.toml").read_text())["project"]["version"])')"
[[ -n "${VERSION}" ]] || { echo "Could not determine version from pyproject.toml"; exit 1; }

TARGET="${MODULE_DIR}/target"
STAGING="${TARGET}/dist-staging/rumi-appbuilder-mcp"

echo "==> Building rumi-appbuilder-mcp ${VERSION}"
rm -rf "${TARGET}/dist-staging" "${TARGET}"/*.tar.gz 2>/dev/null || true
mkdir -p "${STAGING}"/{wheel,conf,scripts}

# 1. Build the wheel.
echo "==> Building wheel via python -m build"
python3 -m build --wheel --outdir "${TARGET}/wheels" >/dev/null
cp "${TARGET}/wheels/rumi_appbuilder_mcp-${VERSION}-py3-none-any.whl" "${STAGING}/wheel/"

# 2. Copy the static bits.
cp conf/appbuilder-mcp.conf.sample "${STAGING}/conf/"
cp scripts/launch.sh               "${STAGING}/scripts/"
cp scripts/shutdown.sh             "${STAGING}/scripts/"
chmod +x "${STAGING}/scripts/"*.sh

# 3. Installer, with the version baked in.
sed "s/__VERSION__/${VERSION}/g" install/install_template.sh > "${STAGING}/install.sh"
chmod +x "${STAGING}/install.sh"

# 4. Version stamp.
echo "${VERSION}" > "${STAGING}/VERSION"

# 5. Tar it up.
TARBALL="${TARGET}/rumi-appbuilder-mcp-${VERSION}.tar.gz"
tar -czf "${TARBALL}" -C "${TARGET}/dist-staging" rumi-appbuilder-mcp
rm -rf "${TARGET}/dist-staging"
echo "==> Built ${TARBALL}"
