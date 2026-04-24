#!/bin/bash
#
# Copyright 2022 N5 Technologies, Inc
#
# Licensed under the Apache License, Version 2.0.
#
# Publish the versioned installer to the downloads server.
# Invoked by CI (RUMI-305) after the release build produces the dist
# tarballs for every arch.
#
# What it does:
# 1. Substitute __VERSION__ in install_template.sh with the target
#    release version.
# 2. Upload the resulting install.sh plus every per-arch dist tarball
#    to s3://downloads.n5corp.com/rumi/appbuilder-rest/<version>/.
# 3. Flip the 'latest' marker so
#    https://downloads.n5corp.com/rumi/appbuilder-rest/latest/install.sh
#    points at the new release.
#
# Inputs (env vars):
#   VERSION                 - Release version (e.g. 1.0.0)
#   DIST_DIR                - Directory holding the per-arch dist tarballs
#                             named <artifact>-<version>-<arch>.tar.gz.
#   DOWNLOAD_BASE_URL       - https://downloads.n5corp.com/rumi/appbuilder-rest
#                             (for status messages; upload target is configured
#                             by whatever AWS credentials are in scope).
#   S3_BUCKET               - s3://downloads.n5corp.com
#
# This script is a skeleton; CI fills in the S3/CDN specifics when
# RUMI-305 picks it up.
set -euo pipefail

: "${VERSION:?VERSION env var is required}"
: "${DIST_DIR:?DIST_DIR env var is required}"
: "${S3_BUCKET:?S3_BUCKET env var is required}"

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

# 3. Upload to the release prefix.
aws s3 cp "${WORK}/install.sh" "${S3_BUCKET}/rumi/appbuilder-rest/${VERSION}/install.sh" \
    --content-type "text/x-shellscript"
for arch in linux-x86-64 linux-arm-64 osx-x86-64 osx-arm-64; do
    aws s3 cp "${DIST_DIR}/${ARTIFACT}-${VERSION}-${arch}.tar.gz" \
        "${S3_BUCKET}/rumi/appbuilder-rest/${VERSION}/${ARTIFACT}-${VERSION}-${arch}.tar.gz" \
        --content-type "application/gzip"
done

# 4. Flip the 'latest' pointer.
aws s3 cp "${WORK}/install.sh" "${S3_BUCKET}/rumi/appbuilder-rest/latest/install.sh" \
    --content-type "text/x-shellscript"
echo "${VERSION}" | aws s3 cp - "${S3_BUCKET}/rumi/appbuilder-rest/latest/version.txt" \
    --content-type "text/plain"

echo "Published version ${VERSION}."
