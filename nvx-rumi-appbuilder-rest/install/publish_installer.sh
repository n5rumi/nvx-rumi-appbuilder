#!/bin/bash
#
# Copyright 2022 N5 Technologies, Inc
#
# Licensed under the Apache License, Version 2.0.
#
# Publish the versioned installer to the downloads server.
# Invoked by ci/release.sh after the release build produces the dist
# tarballs for every arch.
#
# What it does:
# 1. Substitute __VERSION__ in install_template.sh with the target
#    release version.
# 2. Copy the resulting install.sh plus every per-arch dist tarball into the
#    local downloads tree at <DOWNLOADS_ROOT>/rumi/appbuilder-rest/<version>/.
# 3. Flip the 'latest' symlink so
#    https://downloads.n5corp.com/rumi/appbuilder-rest/latest/install.sh
#    points at the new release.
#
# Publishing uses the same local-copy mechanism as every other N5/Rumi/Datafye
# installer (the build agent's downloads tree is what fronts
# downloads.n5corp.com), so there is no S3/CDN client dependency.
#
# Inputs (env vars):
#   VERSION                 - Release version (e.g. 1.0.0)
#   DIST_DIR                - Directory holding the per-arch dist tarballs
#                             named <artifact>-<version>-<arch>.tar.gz.
#   DOWNLOADS_ROOT          - Local downloads tree root on the build agent
#                             (e.g. ~/downloads), fronting downloads.n5corp.com.
set -euo pipefail

: "${VERSION:?VERSION env var is required}"
: "${DIST_DIR:?DIST_DIR env var is required}"
: "${DOWNLOADS_ROOT:?DOWNLOADS_ROOT env var is required (local downloads tree on the build agent)}"

readonly ARTIFACT="nvx-rumi-appbuilder-rest"
readonly TEMPLATE="$(dirname "$0")/install_template.sh"
readonly WORK="$(mktemp -d)"
trap "rm -rf '${WORK}'" EXIT

# 1. Substitute __VERSION__.
sed "s/__VERSION__/${VERSION}/g" "${TEMPLATE}" > "${WORK}/install.sh"
chmod +x "${WORK}/install.sh"

# 2. Sanity-check: every expected arch tarball is present.
for arch in linux-x86-64 linux-arm-64 osx-x86-64 osx-arm-64; do
    f="${DIST_DIR}/${ARTIFACT}-${VERSION}-${arch}.tar.gz"
    [[ -f "${f}" ]] || { echo "Missing tarball: ${f}" >&2; exit 1; }
done

# 3. Copy into the local downloads tree (no S3/CDN client).
readonly BASE="${DOWNLOADS_ROOT}/rumi/appbuilder-rest"
readonly DEST="${BASE}/${VERSION}"
mkdir -p "${DEST}"
cp "${WORK}/install.sh" "${DEST}/install.sh"
chmod +x "${DEST}/install.sh"
for arch in linux-x86-64 linux-arm-64 osx-x86-64 osx-arm-64; do
    cp "${DIST_DIR}/${ARTIFACT}-${VERSION}-${arch}.tar.gz" "${DEST}/"
done

# 4. Flip the 'latest' symlink + version stamp.
[[ -L "${BASE}/latest" ]] && rm -f "${BASE}/latest"
( cd "${BASE}" && ln -sfn "${VERSION}" latest )
echo "${VERSION}" > "${DEST}/version.txt"

echo "Published version ${VERSION} to ${DEST}."
