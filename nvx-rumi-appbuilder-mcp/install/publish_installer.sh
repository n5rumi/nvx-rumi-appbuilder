#!/bin/bash
#
# Copyright 2022 N5 Technologies, Inc
#
# Licensed under the Apache License, Version 2.0.
#
# Publish the versioned MCP installer + tarball to the downloads tree.
# Invoked by ci/release.sh after build/build-dist.sh produces the
# tarball for a given release.
#
# Publishing uses the same local-copy mechanism as every other N5/Rumi/Datafye
# installer (the build agent's downloads tree fronts downloads.n5corp.com),
# so there is no S3/CDN client dependency.
#
# Required env:
#   VERSION         e.g. 1.0.0
#   DIST_DIR        dir containing rumi-appbuilder-mcp-<version>.tar.gz
#   DOWNLOADS_ROOT  local downloads tree root on the build agent (e.g. ~/downloads)
set -euo pipefail

: "${VERSION:?VERSION env var is required}"
: "${DIST_DIR:?DIST_DIR env var is required}"
: "${DOWNLOADS_ROOT:?DOWNLOADS_ROOT env var is required (local downloads tree on the build agent)}"

readonly ARTIFACT="rumi-appbuilder-mcp"
readonly TEMPLATE="$(dirname "$0")/install_template.sh"
readonly WORK="$(mktemp -d)"
trap "rm -rf '${WORK}'" EXIT

# 1. Substitute __VERSION__ in install_template.sh.
sed "s/__VERSION__/${VERSION}/g" "${TEMPLATE}" > "${WORK}/install.sh"
chmod +x "${WORK}/install.sh"

# 2. Tarball must exist.
TARBALL="${DIST_DIR}/${ARTIFACT}-${VERSION}.tar.gz"
[[ -f "${TARBALL}" ]] || { echo "Missing tarball: ${TARBALL}" >&2; exit 1; }

# 3. Copy into the local downloads tree (no S3/CDN client).
readonly BASE="${DOWNLOADS_ROOT}/rumi/appbuilder-mcp"
readonly DEST="${BASE}/${VERSION}"
mkdir -p "${DEST}"
cp "${WORK}/install.sh" "${DEST}/install.sh"
chmod +x "${DEST}/install.sh"
cp "${TARBALL}" "${DEST}/${ARTIFACT}-${VERSION}.tar.gz"

# 4. Flip the 'latest' symlink + version stamp.
[[ -L "${BASE}/latest" ]] && rm -f "${BASE}/latest"
( cd "${BASE}" && ln -sfn "${VERSION}" latest )
echo "${VERSION}" > "${DEST}/version.txt"

echo "Published MCP version ${VERSION} to ${DEST}."
