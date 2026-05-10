#!/bin/bash
#
# Copyright 2022 N5 Technologies, Inc
#
# Licensed under the Apache License, Version 2.0.
#
# Rumi App Builder — combined REST + MCP bundle installer.
#
# Thin orchestrator that installs the two services as siblings under
# the same install root:
#
#     <install-dir>/rumi-appbuilder-rest/
#     <install-dir>/rumi-appbuilder-mcp/
#
# Delegates to each service's standalone install.sh (landed under
# RUMI-322 and RUMI-325). The value-add here is that it wires the MCP
# --rest-url automatically to the REST install's port, so a single
# bundle install yields a working REST+MCP pair.
#
# Publishing:
#   https://downloads.n5corp.com/rumi/appbuilder/<version>/install.sh
# which fetches:
#   https://downloads.n5corp.com/rumi/appbuilder-rest/<version>/install.sh + tarball
#   https://downloads.n5corp.com/rumi/appbuilder-mcp/<version>/install.sh + tarball
#
# The __VERSION__ placeholder is replaced at publish time.
set -u
set -o pipefail

# ---- Constants ------------------------------------------------------

readonly INSTALLER_NAME="Rumi App Builder (combined REST + MCP) Installer"
readonly DEFAULT_VERSION="__VERSION__"
readonly DEFAULT_INSTALL_ROOT="${HOME}/rumi"
readonly DEFAULT_DOWNLOAD_BASE="${RUMI_APPBUILDER_DOWNLOAD_BASE:-https://downloads.n5corp.com/rumi}"

# ---- Globals --------------------------------------------------------

MODE="install"          # install | uninstall
VERSION="${DEFAULT_VERSION}"
INSTALL_ROOT=""
REST_PORT=""
MCP_PORT=""
REST_LOCAL_DIST=""
MCP_LOCAL_DIST=""
FORCE="false"
VERBOSE="false"
NO_START="false"
PURGE="false"

# ---- Pretty helpers -------------------------------------------------

readonly BOLD="$(tput bold 2>/dev/null || echo '')"
readonly RESET="$(tput sgr0 2>/dev/null || echo '')"
readonly RED="$(tput setaf 1 2>/dev/null || echo '')"
readonly GREEN="$(tput setaf 2 2>/dev/null || echo '')"
readonly YELLOW="$(tput setaf 3 2>/dev/null || echo '')"

info() { echo "${GREEN}##${RESET} $*"; }
warn() { echo "${YELLOW}##${RESET} $*" >&2; }
err()  { echo "${RED}##${RESET} $*" >&2; }
die()  { err "$*"; exit 1; }

print_usage() {
    cat <<EOF
${BOLD}${INSTALLER_NAME}${RESET}

Usage:
  install.sh [options]                         # install or upgrade both services
  install.sh --uninstall [--purge]             # remove both services
  install.sh --help

Install / upgrade options:
  --install-dir DIR        Root for both services (default: \$HOME/rumi).
  --rest-port N            Port for the REST service (default: 3200).
  --mcp-port N             Port for the MCP server (default: 3201).
  --version VER            Release version (default: ${DEFAULT_VERSION}).
  --from-rest FILE   Use a local REST tarball instead of downloading.
  --from-mcp FILE    Use a local MCP tarball instead of downloading.
  --no-start               Install both without starting either.
  --force                  Skip confirmation prompts.
  --verbose                Verbose output.

Uninstall options:
  --uninstall              Stop + remove both services. Preserves data.
  --purge                  With --uninstall, also remove data dirs.

The combined installer is deliberately thin — it delegates to each
service's own install.sh. For per-service flags that aren't exposed
here (e.g. --data-dir), invoke the per-service installer directly.
EOF
}

# ---- Argument parsing -----------------------------------------------

parse_args() {
    while [[ $# -gt 0 ]]; do
        case "$1" in
            -h|--help) print_usage; exit 0 ;;
            --install-dir)     INSTALL_ROOT="$2"; shift 2 ;;
            --install-dir=*)   INSTALL_ROOT="${1#--install-dir=}"; shift ;;
            --rest-port)       REST_PORT="$2"; shift 2 ;;
            --rest-port=*)     REST_PORT="${1#--rest-port=}"; shift ;;
            --mcp-port)        MCP_PORT="$2"; shift 2 ;;
            --mcp-port=*)      MCP_PORT="${1#--mcp-port=}"; shift ;;
            --version)         VERSION="$2"; shift 2 ;;
            --version=*)       VERSION="${1#--version=}"; shift ;;
            --from-rest) REST_LOCAL_DIST="$2"; shift 2 ;;
            --from-mcp)  MCP_LOCAL_DIST="$2"; shift 2 ;;
            --no-start)        NO_START="true"; shift ;;
            --force)           FORCE="true"; shift ;;
            --verbose)         VERBOSE="true"; shift ;;
            --uninstall)       MODE="uninstall"; shift ;;
            --purge)           PURGE="true"; shift ;;
            *) die "unknown option '$1' (see --help)" ;;
        esac
    done
    INSTALL_ROOT="${INSTALL_ROOT:-${DEFAULT_INSTALL_ROOT}}"
    # Note: sentinel is split so sed's __VERSION__ substitution doesn't replace it
    [[ "${VERSION}" == "__""VERSION__" ]] && VERSION=""
    REST_PORT="${REST_PORT:-3200}"
    MCP_PORT="${MCP_PORT:-3201}"
}

# ---- Fetch per-service installer ------------------------------------

fetch_installer() {
    local service="$1" dest="$2"
    [[ -n "${VERSION}" ]] || die "No version to fetch ${service} installer. Pass --version or per-service --*-local-dist."
    local url="${DEFAULT_DOWNLOAD_BASE}/appbuilder-${service}/${VERSION}/install.sh"
    info "Fetching ${service} installer: ${url}"
    if command -v curl >/dev/null 2>&1; then
        curl -fsSL -o "${dest}" "${url}" || die "download failed: ${url}"
    elif command -v wget >/dev/null 2>&1; then
        wget -q -O "${dest}" "${url}" || die "download failed: ${url}"
    else
        die "Neither curl nor wget found on PATH."
    fi
    chmod +x "${dest}"
}

# ---- Install --------------------------------------------------------

do_install() {
    local staging
    staging="$(mktemp -d)"
    trap "rm -rf '${staging}'" EXIT

    local rest_installer mcp_installer
    rest_installer="${staging}/rest-install.sh"
    mcp_installer="${staging}/mcp-install.sh"
    fetch_installer "rest" "${rest_installer}"
    fetch_installer "mcp"  "${mcp_installer}"

    local common_flags=(--install-dir "${INSTALL_ROOT}")
    [[ "${FORCE}"    == "true" ]] && common_flags+=(--force)
    [[ "${VERBOSE}"  == "true" ]] && common_flags+=(--verbose)
    [[ "${NO_START}" == "true" ]] && common_flags+=(--no-start)
    [[ -n "${VERSION}" ]] && common_flags+=(--version "${VERSION}")

    # 1. REST first (it's the backend the MCP proxies to).
    info "Installing REST service"
    local rest_flags=("${common_flags[@]}" --port "${REST_PORT}")
    [[ -n "${REST_LOCAL_DIST}" ]] && rest_flags+=(--from "${REST_LOCAL_DIST}")
    "${rest_installer}" "${rest_flags[@]}" || die "REST install failed."

    # 2. MCP second, wired to the REST install's port.
    info "Installing MCP server (wiring --rest-url to http://127.0.0.1:${REST_PORT})"
    local mcp_flags=("${common_flags[@]}" --port "${MCP_PORT}" \
        --rest-url "http://127.0.0.1:${REST_PORT}")
    [[ -n "${MCP_LOCAL_DIST}" ]] && mcp_flags+=(--from "${MCP_LOCAL_DIST}")
    "${mcp_installer}" "${mcp_flags[@]}" || die "MCP install failed."

    info "Combined install complete."
    echo
    info "REST:  http://127.0.0.1:${REST_PORT}/health"
    info "MCP:   http://127.0.0.1:${MCP_PORT}/mcp"
}

# ---- Uninstall ------------------------------------------------------

do_uninstall() {
    local staging
    staging="$(mktemp -d)"
    trap "rm -rf '${staging}'" EXIT

    # Prefer already-installed install.sh scripts (the tarballs ship
    # them inside releases/v<ver>/). Fall back to fetching if absent.
    local rest_uninstaller mcp_uninstaller
    rest_uninstaller="$(find_installed_script "${INSTALL_ROOT}/rumi-appbuilder-rest/current/install.sh" "${staging}/rest-install.sh" "rest")"
    mcp_uninstaller="$(find_installed_script "${INSTALL_ROOT}/rumi-appbuilder-mcp/current/install.sh" "${staging}/mcp-install.sh" "mcp")"

    local flags=(--install-dir "${INSTALL_ROOT}" --uninstall)
    [[ "${FORCE}" == "true" ]] && flags+=(--force)
    [[ "${PURGE}" == "true" ]] && flags+=(--purge)

    # Reverse order: stop MCP first (it depends on REST), then REST.
    info "Uninstalling MCP server"
    [[ -x "${mcp_uninstaller}" ]] && "${mcp_uninstaller}" "${flags[@]}" || warn "MCP uninstall skipped (no installer found)."

    info "Uninstalling REST service"
    [[ -x "${rest_uninstaller}" ]] && "${rest_uninstaller}" "${flags[@]}" || warn "REST uninstall skipped (no installer found)."

    info "Combined uninstall complete."
}

# Print a path to a usable install.sh: either the already-installed
# one, or a freshly-fetched copy if the install is already gone.
find_installed_script() {
    local installed="$1" fallback="$2" service="$3"
    if [[ -x "${installed}" ]]; then
        echo "${installed}"
        return
    fi
    # Install dir may already be stripped; fetch to proceed.
    [[ -n "${VERSION}" ]] || die "No ${service} install.sh found at ${installed} and no --version to refetch."
    fetch_installer "${service}" "${fallback}" >&2
    echo "${fallback}"
}

# ---- Main -----------------------------------------------------------

main() {
    parse_args "$@"
    case "${MODE}" in
        install)   do_install ;;
        uninstall) do_uninstall ;;
    esac
}

main "$@"
