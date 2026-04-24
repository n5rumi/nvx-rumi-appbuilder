#!/bin/bash
#
# Copyright 2022 N5 Technologies, Inc
#
# Licensed under the Apache License, Version 2.0.
#
# Publish the versioned MCP installer + tarball to the downloads bucket.
# Invoked by CI (RUMI-305) after build/build-dist.sh produces the
# tarball for a given release.
#
# Required env:
#   VERSION        e.g. 1.0.0
#   DIST_DIR       dir containing rumi-appbuilder-mcp-<version>.tar.gz
#   S3_BUCKET      e.g. s3://downloads.n5corp.com
set -euo pipefail

: "${VERSION:?VERSION env var is required}"
: "${DIST_DIR:?DIST_DIR env var is required}"
: "${S3_BUCKET:?S3_BUCKET env var is required}"

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

# 3. Upload to the release prefix.
aws s3 cp "${WORK}/install.sh" "${S3_BUCKET}/rumi/appbuilder-mcp/${VERSION}/install.sh" \
    --content-type "text/x-shellscript"
aws s3 cp "${TARBALL}" "${S3_BUCKET}/rumi/appbuilder-mcp/${VERSION}/${ARTIFACT}-${VERSION}.tar.gz" \
    --content-type "application/gzip"

# 4. Flip the 'latest' pointer.
aws s3 cp "${WORK}/install.sh" "${S3_BUCKET}/rumi/appbuilder-mcp/latest/install.sh" \
    --content-type "text/x-shellscript"
echo "${VERSION}" | aws s3 cp - "${S3_BUCKET}/rumi/appbuilder-mcp/latest/version.txt" \
    --content-type "text/plain"

echo "Published MCP version ${VERSION}."
