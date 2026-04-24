#!/bin/bash
#
# Copyright 2022 N5 Technologies, Inc
#
# Licensed under the Apache License, Version 2.0.
#
# Publish the combined-bundle installer.
# Called by CI (RUMI-305) after the REST + MCP per-service installers
# are already published for the same VERSION.
#
# Required env:
#   VERSION      e.g. 1.0.0
#   S3_BUCKET    e.g. s3://downloads.n5corp.com
set -euo pipefail

: "${VERSION:?VERSION env var is required}"
: "${S3_BUCKET:?S3_BUCKET env var is required}"

readonly TEMPLATE="$(dirname "$0")/install_template.sh"
readonly WORK="$(mktemp -d)"
trap "rm -rf '${WORK}'" EXIT

# Substitute __VERSION__.
sed "s/__VERSION__/${VERSION}/g" "${TEMPLATE}" > "${WORK}/install.sh"
chmod +x "${WORK}/install.sh"

# Upload to the versioned prefix.
aws s3 cp "${WORK}/install.sh" "${S3_BUCKET}/rumi/appbuilder/${VERSION}/install.sh" \
    --content-type "text/x-shellscript"

# Flip 'latest'.
aws s3 cp "${WORK}/install.sh" "${S3_BUCKET}/rumi/appbuilder/latest/install.sh" \
    --content-type "text/x-shellscript"
echo "${VERSION}" | aws s3 cp - "${S3_BUCKET}/rumi/appbuilder/latest/version.txt" \
    --content-type "text/plain"

echo "Published combined-bundle version ${VERSION}."
