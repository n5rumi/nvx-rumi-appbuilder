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
# Publishing uses the same local-copy mechanism as every other N5/Rumi/Datafye
# installer: each per-service publisher copies its install.sh + tarballs into
# the build agent's local downloads tree (which fronts downloads.n5corp.com).
# No S3/CDN client is involved.
#
# Requires the three publishers already in the repo:
#   nvx-rumi-appbuilder-rest/install/publish_installer.sh
#   nvx-rumi-appbuilder-mcp/install/publish_installer.sh
#   install-bundle/publish_installer.sh
#
# Usage:
#   ci/release.sh <downloads_root>
#
# Argument:
#   <downloads_root>           — local downloads tree on the build agent
#                                (e.g. ~/downloads), fronting downloads.n5corp.com.
#                                Same role as the downloads_root arg the other
#                                N5/Rumi/Datafye publish_installer scripts take.
#
# Required env:
#   VERSION                    — release version, e.g. 1.0.0
#   JAVA_HOME                  — Java 17+ (for REST build)
#
# Optional env:
#   DOWNLOAD_BASE_URL          — public base URL for the end-user hints printed
#                                at the end. Default https://downloads.n5corp.com.
#   RELEASE_ARCHES             — space-separated list of arches to publish REST
#                                for. Default: the x86 arches (linux-x86-64
#                                osx-x86-64). The arm sandbox bases aren't
#                                published yet (no arm build machine).
#   SKIP_REST=1                — skip REST publish (e.g. patch-only to MCP).
#   SKIP_MCP=1                 — skip MCP publish.
#   SKIP_BUNDLE=1              — skip combined bundle publish.
#
# Exit code is non-zero on the first failure.
set -euo pipefail

DOWNLOADS_ROOT="${1:?usage: ci/release.sh <downloads_root> (local downloads tree fronting downloads.n5corp.com)}"
: "${VERSION:?VERSION env var is required}"
: "${JAVA_HOME:?JAVA_HOME env var is required}"

DOWNLOAD_BASE_URL="${DOWNLOAD_BASE_URL:-https://downloads.n5corp.com}"

REPO_DIR="$(cd "$(dirname "$0")/.." && pwd)"
REST_MODULE="${REPO_DIR}/nvx-rumi-appbuilder-rest"
MCP_MODULE="${REPO_DIR}/nvx-rumi-appbuilder-mcp"

# Only the x86 sandbox bases (com.neeve:nvx-rumi:sandbox-<arch>) are published
# today — there's no arm build machine, so linux-arm-64 / osx-arm-64 bases don't
# exist. Default to the x86 arches; add the arm ones to RELEASE_ARCHES once their
# bases are published.
RELEASE_ARCHES="${RELEASE_ARCHES:-linux-x86-64 osx-x86-64}"

info()  { echo "==> $*"; }
fail()  { echo "!! $*" >&2; exit 1; }

# ---- 1. REST: matrix build + publish --------------------------------

if [[ "${SKIP_REST:-}" != "1" ]]; then
    info "Building REST dist tarballs for [${RELEASE_ARCHES}]"
    cd "${REPO_DIR}"

    # Stamp the release version onto the Maven coordinates so the produced
    # tarballs are named nvx-rumi-appbuilder-rest-${VERSION}-<arch>.tar.gz
    # (the build agent works on an ephemeral checkout, so this is not committed).
    mvn -q -pl nvx-rumi-appbuilder-rest -am versions:set \
        -DnewVersion="${VERSION}" -DgenerateBackupPoms=false \
        || fail "Could not set Maven version to ${VERSION}"

    # Collect the per-arch tarballs OUTSIDE the module's target/ — each arch's
    # `clean package` wipes target/, so a staging dir under it would be deleted
    # (taking earlier arches' tarballs with it).
    local_archives_dir="${REPO_DIR}/.release-dist"
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
    VERSION="${VERSION}" DIST_DIR="${local_archives_dir}" DOWNLOADS_ROOT="${DOWNLOADS_ROOT}" \
        "${REST_MODULE}/install/publish_installer.sh" \
        || fail "REST publish failed"
else
    info "Skipping REST (SKIP_REST=1)"
fi

# ---- 2. MCP: build + publish ----------------------------------------

if [[ "${SKIP_MCP:-}" != "1" ]]; then
    info "Building MCP dist tarball"
    # Stamp the release version into pyproject.toml so build-dist.sh names the
    # wheel + tarball with ${VERSION} (ephemeral checkout, not committed).
    sed -i.bak -E "s/^version = \".*\"/version = \"${VERSION}\"/" \
        "${MCP_MODULE}/pyproject.toml" \
        && rm -f "${MCP_MODULE}/pyproject.toml.bak" \
        || fail "Could not set MCP version to ${VERSION}"

    bash "${MCP_MODULE}/build/build-dist.sh" >/dev/null \
        || fail "MCP build failed"

    info "Publishing MCP installer + tarball"
    VERSION="${VERSION}" DIST_DIR="${MCP_MODULE}/target" DOWNLOADS_ROOT="${DOWNLOADS_ROOT}" \
        "${MCP_MODULE}/install/publish_installer.sh" \
        || fail "MCP publish failed"
else
    info "Skipping MCP (SKIP_MCP=1)"
fi

# ---- 3. Combined bundle ---------------------------------------------

if [[ "${SKIP_BUNDLE:-}" != "1" ]]; then
    info "Publishing combined bundle installer"
    VERSION="${VERSION}" DOWNLOADS_ROOT="${DOWNLOADS_ROOT}" \
        "${REPO_DIR}/install-bundle/publish_installer.sh" \
        || fail "Combined bundle publish failed"
else
    info "Skipping combined bundle (SKIP_BUNDLE=1)"
fi

info "Release ${VERSION} published."
echo
echo "End-user URLs:"
echo "  REST only:     curl -sSL ${DOWNLOAD_BASE_URL}/rumi/appbuilder-rest/${VERSION}/install.sh | bash"
echo "  MCP only:      curl -sSL ${DOWNLOAD_BASE_URL}/rumi/appbuilder-mcp/${VERSION}/install.sh | bash"
echo "  Combined:      curl -sSL ${DOWNLOAD_BASE_URL}/rumi/appbuilder/${VERSION}/install.sh | bash"
echo
echo "  Latest (rolling): replace ${VERSION} with 'latest' in any of the above."
