#!/bin/bash
#
# Copyright 2022 N5 Technologies, Inc
#
# Licensed under the Apache License, Version 2.0.
#
# Publish the combined-bundle installer.
# Called by ci/release.sh after the REST + MCP per-service installers
# are already published for the same VERSION.
#
# Publishing uses the same local-copy mechanism as every other N5/Rumi/Datafye
# installer (the build agent's downloads tree fronts downloads.n5corp.com),
# so there is no S3/CDN client dependency.
#
# Required env:
#   VERSION         e.g. 1.0.0
#   DOWNLOADS_ROOT  local downloads tree root on the build agent (e.g. ~/downloads)
set -euo pipefail

: "${VERSION:?VERSION env var is required}"
: "${DOWNLOADS_ROOT:?DOWNLOADS_ROOT env var is required (local downloads tree on the build agent)}"

readonly TEMPLATE="$(dirname "$0")/install_template.sh"
readonly WORK="$(mktemp -d)"
trap "rm -rf '${WORK}'" EXIT

# Substitute __VERSION__.
sed "s/__VERSION__/${VERSION}/g" "${TEMPLATE}" > "${WORK}/install.sh"
chmod +x "${WORK}/install.sh"

# Copy into the local downloads tree (no S3/CDN client).
readonly BASE="${DOWNLOADS_ROOT}/rumi/appbuilder"
readonly DEST="${BASE}/${VERSION}"
mkdir -p "${DEST}"
cp "${WORK}/install.sh" "${DEST}/install.sh"
chmod +x "${DEST}/install.sh"

# Flip 'latest' symlink + version stamp.
[[ -L "${BASE}/latest" ]] && rm -f "${BASE}/latest"
( cd "${BASE}" && ln -sfn "${VERSION}" latest )
echo "${VERSION}" > "${DEST}/version.txt"

echo "Published combined-bundle version ${VERSION} to ${DEST}."
