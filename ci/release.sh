#!/bin/bash
#
# Copyright 2022 N5 Technologies, Inc
#
# Licensed under the Apache License, Version 2.0.
#
# Top-level release orchestrator for the App Builder.
#
# Builds and publishes:
#   1. REST service dist tarballs, one per arch
#      (linux-x86-64 | linux-arm-64 | osx-x86-64 | osx-arm-64)
#   2. MCP server dist tarball (arch-agnostic; pure Python)
#   3. Combined bundle installer (thin orchestrator; no tarball of its own)
#
# Requires the three publishers already in the repo:
#   nvx-rumi-appbuilder-rest/install/publish_installer.sh
#   nvx-rumi-appbuilder-mcp/install/publish_installer.sh
#   install-bundle/publish_installer.sh
#
# Required env:
#   VERSION                    — release version, e.g. 1.0.0
#   S3_BUCKET                  — e.g. s3://downloads.n5corp.com
#   JAVA_HOME                  — Java 17+ (for REST build)
#   AWS_ACCESS_KEY_ID / AWS_SECRET_ACCESS_KEY (or role on the runner)
#
# Optional env:
#   RELEASE_ARCHES             — space-separated list of arches to
#                                publish REST for. Default: all four.
#   SKIP_REST=1                — skip REST publish (e.g. patch-only to MCP).
#   SKIP_MCP=1                 — skip MCP publish.
#   SKIP_BUNDLE=1              — skip combined bundle publish.
#
# Exit code is non-zero on the first failure.
set -euo pipefail

: "${VERSION:?VERSION env var is required}"
: "${S3_BUCKET:?S3_BUCKET env var is required}"
: "${JAVA_HOME:?JAVA_HOME env var is required}"

REPO_DIR="$(cd "$(dirname "$0")/.." && pwd)"
REST_MODULE="${REPO_DIR}/nvx-rumi-appbuilder-rest"
MCP_MODULE="${REPO_DIR}/nvx-rumi-appbuilder-mcp"

RELEASE_ARCHES="${RELEASE_ARCHES:-linux-x86-64 linux-arm-64 osx-x86-64 osx-arm-64}"

info()  { echo "==> $*"; }
fail()  { echo "!! $*" >&2; exit 1; }

# ---- 1. REST: matrix build + publish --------------------------------

if [[ "${SKIP_REST:-}" != "1" ]]; then
    info "Building REST dist tarballs for [${RELEASE_ARCHES}]"
    cd "${REPO_DIR}"
    local_archives_dir="${REST_MODULE}/target/release"
    rm -rf "${local_archives_dir}"
    mkdir -p "${local_archives_dir}"

    for arch in ${RELEASE_ARCHES}; do
        info "REST: ${arch}"
        mvn -pl nvx-rumi-appbuilder-rest -Pdist -Darch="${arch}" \
            clean package -DskipTests >/dev/null \
            || fail "REST build failed for ${arch}"
        cp "${REST_MODULE}/target/nvx-rumi-appbuilder-rest-${VERSION}-${arch}.tar.gz" \
           "${local_archives_dir}/" \
            || fail "Expected tarball missing after REST build for ${arch}"
    done

    info "Publishing REST installer + ${#RELEASE_ARCHES}-arch tarballs"
    VERSION="${VERSION}" DIST_DIR="${local_archives_dir}" S3_BUCKET="${S3_BUCKET}" \
        "${REST_MODULE}/install/publish_installer.sh" \
        || fail "REST publish failed"
else
    info "Skipping REST (SKIP_REST=1)"
fi

# ---- 2. MCP: build + publish ----------------------------------------

if [[ "${SKIP_MCP:-}" != "1" ]]; then
    info "Building MCP dist tarball"
    bash "${MCP_MODULE}/build/build-dist.sh" >/dev/null \
        || fail "MCP build failed"

    info "Publishing MCP installer + tarball"
    VERSION="${VERSION}" DIST_DIR="${MCP_MODULE}/target" S3_BUCKET="${S3_BUCKET}" \
        "${MCP_MODULE}/install/publish_installer.sh" \
        || fail "MCP publish failed"
else
    info "Skipping MCP (SKIP_MCP=1)"
fi

# ---- 3. Combined bundle ---------------------------------------------

if [[ "${SKIP_BUNDLE:-}" != "1" ]]; then
    info "Publishing combined bundle installer"
    VERSION="${VERSION}" S3_BUCKET="${S3_BUCKET}" \
        "${REPO_DIR}/install-bundle/publish_installer.sh" \
        || fail "Combined bundle publish failed"
else
    info "Skipping combined bundle (SKIP_BUNDLE=1)"
fi

info "Release ${VERSION} published."
echo
echo "End-user URLs:"
echo "  REST only:     curl -sSL ${S3_BUCKET/s3:\/\//https:\/\/}/rumi/appbuilder-rest/${VERSION}/install.sh | bash"
echo "  MCP only:      curl -sSL ${S3_BUCKET/s3:\/\//https:\/\/}/rumi/appbuilder-mcp/${VERSION}/install.sh | bash"
echo "  Combined:      curl -sSL ${S3_BUCKET/s3:\/\//https:\/\/}/rumi/appbuilder/${VERSION}/install.sh | bash"
echo
echo "  Latest (rolling): replace ${VERSION} with 'latest' in any of the above."
