#!/bin/bash
#
# Copyright 2022 N5 Technologies, Inc
#
# Licensed under the Apache License, Version 2.0.
#
# Run the App Builder release (ci/release.sh) inside the build-toolchain
# container (ci/Dockerfile), so the build host needs only Docker — no
# Python 3.11 / OpenSSL 1.1.1 / Maven / JDK installed locally.
#
# The downloads tree and the Maven cache are bind-mounted from the host so
# that (a) published artifacts land on the HOST tree that fronts
# downloads.n5corp.com (not in the throwaway container), and (b) Maven reuses
# the host ~/.m2 settings.xml (nexus creds) + local repo cache.
#
# Required env:
#   VERSION          release version, e.g. 1.0.1
#   DOWNLOADS_ROOT   HOST path of the local downloads tree that fronts
#                    downloads.n5corp.com (e.g. ~/downloads). Bind-mounted in.
#
# Optional env (passed through to ci/release.sh):
#   SKIP_REST / SKIP_MCP / SKIP_BUNDLE / RELEASE_ARCHES
set -euo pipefail

: "${VERSION:?VERSION env var is required}"
: "${DOWNLOADS_ROOT:?DOWNLOADS_ROOT env var is required (host downloads tree)}"

REPO_DIR="$(cd "$(dirname "$0")/.." && pwd)"
IMAGE="appbuilder-build-tools:local"
M2_DIR="${HOME}/.m2"

mkdir -p "${DOWNLOADS_ROOT}" "${M2_DIR}"

echo "==> Building toolchain image ${IMAGE}"
docker build -t "${IMAGE}" "${REPO_DIR}/ci"

echo "==> Running ci/release.sh in ${IMAGE} (VERSION=${VERSION})"
docker run --rm \
    -v "${REPO_DIR}":/work -w /work \
    -v "${DOWNLOADS_ROOT}":/downloads \
    -v "${M2_DIR}":/root/.m2 \
    -e VERSION="${VERSION}" \
    -e SKIP_REST="${SKIP_REST:-}" \
    -e SKIP_MCP="${SKIP_MCP:-}" \
    -e SKIP_BUNDLE="${SKIP_BUNDLE:-}" \
    -e RELEASE_ARCHES="${RELEASE_ARCHES:-}" \
    "${IMAGE}" \
    bash ci/release.sh /downloads

echo "==> Release ${VERSION} complete (artifacts under ${DOWNLOADS_ROOT}/rumi/appbuilder*)."
