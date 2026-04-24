#!/bin/bash
#
# Copyright 2022 N5 Technologies, Inc
#
# Licensed under the Apache License, Version 2.0.
#
# Rumi App Builder MCP — installer / upgrader / uninstaller.
#
# Shape mirrors the REST-only installer from RUMI-322: symlink
# versioning (current -> releases/v<version>), separate data
# directory preserved across upgrades, no privileged install required.
#
# The __VERSION__ placeholder is replaced at publish time. During
# local iteration, pass --local-dist <tarball> and --version <ver>.
set -u
set -o pipefail

# ---- Constants ------------------------------------------------------

readonly INSTALLER_NAME="Rumi App Builder MCP Installer"
readonly PRODUCT="rumi-appbuilder-mcp"
readonly ARTIFACT_ID="rumi-appbuilder-mcp"
readonly DEFAULT_VERSION="__VERSION__"
readonly DEFAULT_INSTALL_ROOT="${HOME}/rumi"
readonly DEFAULT_DOWNLOAD_BASE="${RUMI_APPBUILDER_MCP_DOWNLOAD_BASE:-https://downloads.n5corp.com/rumi/appbuilder-mcp}"
readonly HEALTH_TIMEOUT_SECS="${RUMI_APPBUILDER_MCP_HEALTH_TIMEOUT:-30}"
readonly MIN_PY_MINOR=11   # Python 3.11+

# ---- Globals --------------------------------------------------------

MODE="install"          # install | uninstall
VERSION="${DEFAULT_VERSION}"
INSTALL_ROOT=""
DATA_DIR=""
PORT=""
REST_URL=""
LOCAL_DIST=""
FORCE="false"
VERBOSE="false"
NO_START="false"
DOWNLOAD_ONLY="false"
PURGE="false"
PYTHON_BIN=""
DOWNLOADER=""

# ---- Pretty helpers -------------------------------------------------

readonly BOLD="$(tput bold 2>/dev/null || echo '')"
readonly DIM="$(tput dim 2>/dev/null || echo '')"
readonly RESET="$(tput sgr0 2>/dev/null || echo '')"
readonly RED="$(tput setaf 1 2>/dev/null || echo '')"
readonly GREEN="$(tput setaf 2 2>/dev/null || echo '')"
readonly YELLOW="$(tput setaf 3 2>/dev/null || echo '')"

info()  { echo "${GREEN}==>${RESET} $*"; }
warn()  { echo "${YELLOW}==>${RESET} $*" >&2; }
err()   { echo "${RED}==>${RESET} $*" >&2; }
debug() { [[ "${VERBOSE}" == "true" ]] && echo "${DIM}... $*${RESET}" >&2 || true; }
die()   { err "$*"; exit 1; }

print_usage() {
    cat <<EOF
${BOLD}${INSTALLER_NAME}${RESET}

Usage:
  install.sh [options]                         # install or upgrade (auto-detected)
  install.sh --uninstall [--purge]             # remove the installed service
  install.sh --help

Install / upgrade options:
  --install-dir DIR        Installation root (default: \$HOME/rumi).
                           Service lands at <install-dir>/${PRODUCT}/.
  --data-dir DIR           External data dir via symlink.
                           Default: <install-dir>/${PRODUCT}/data.
  --port N                 HTTP port for the MCP server (default: 3201).
  --rest-url URL           REST service base URL the MCP proxies to
                           (default: http://127.0.0.1:3200).
  --version VER            Release version (default: ${DEFAULT_VERSION}).
  --local-dist FILE        Use a local tarball instead of downloading.
  --python PATH            Python 3.${MIN_PY_MINOR}+ interpreter to build the
                           venv against (default: python3 on PATH).
  --download-only          Download and exit without installing.
  --no-start               Install without starting.
  --force                  Skip confirmation prompts.
  --verbose                Print extra diagnostic output.

Uninstall options:
  --uninstall              Stop, remove binaries; data/ preserved.
  --purge                  With --uninstall, also remove data/.

Environment:
  RUMI_APPBUILDER_MCP_DOWNLOAD_BASE   Override the downloads server.
  RUMI_APPBUILDER_MCP_HEALTH_TIMEOUT  Health-check timeout seconds (default: ${HEALTH_TIMEOUT_SECS}).
EOF
}

# ---- Argument parsing -----------------------------------------------

parse_args() {
    while [[ $# -gt 0 ]]; do
        case "$1" in
            -h|--help) print_usage; exit 0 ;;
            --install-dir)   INSTALL_ROOT="$2"; shift 2 ;;
            --install-dir=*) INSTALL_ROOT="${1#--install-dir=}"; shift ;;
            --data-dir)      DATA_DIR="$2"; shift 2 ;;
            --data-dir=*)    DATA_DIR="${1#--data-dir=}"; shift ;;
            --port)          PORT="$2"; shift 2 ;;
            --port=*)        PORT="${1#--port=}"; shift ;;
            --rest-url)      REST_URL="$2"; shift 2 ;;
            --rest-url=*)    REST_URL="${1#--rest-url=}"; shift ;;
            --version)       VERSION="$2"; shift 2 ;;
            --version=*)     VERSION="${1#--version=}"; shift ;;
            --local-dist)    LOCAL_DIST="$2"; shift 2 ;;
            --local-dist=*)  LOCAL_DIST="${1#--local-dist=}"; shift ;;
            --python)        PYTHON_BIN="$2"; shift 2 ;;
            --python=*)      PYTHON_BIN="${1#--python=}"; shift ;;
            --download-only) DOWNLOAD_ONLY="true"; shift ;;
            --no-start)      NO_START="true"; shift ;;
            --force)         FORCE="true"; shift ;;
            --verbose)       VERBOSE="true"; shift ;;
            --uninstall)     MODE="uninstall"; shift ;;
            --purge)         PURGE="true"; shift ;;
            *) die "unknown option '$1' (see --help)" ;;
        esac
    done
    INSTALL_ROOT="${INSTALL_ROOT:-${DEFAULT_INSTALL_ROOT}}"
    [[ "${VERSION}" == "__VERSION__" ]] && VERSION=""
}

# ---- Pre-flight ------------------------------------------------------

preflight() {
    PYTHON_BIN="${PYTHON_BIN:-$(command -v python3 || true)}"
    [[ -n "${PYTHON_BIN}" && -x "${PYTHON_BIN}" ]] \
        || die "No python3 on PATH; pass --python /path/to/python3."
    local pyver
    pyver="$("${PYTHON_BIN}" -c 'import sys; print(sys.version_info.minor)')"
    if [[ -z "${pyver}" || "${pyver}" -lt ${MIN_PY_MINOR} ]]; then
        die "Python 3.${MIN_PY_MINOR}+ required; got 3.${pyver} at ${PYTHON_BIN}."
    fi
    debug "Python 3.${pyver} at ${PYTHON_BIN}"

    if [[ -z "${LOCAL_DIST}" && "${MODE}" == "install" ]]; then
        if   command -v curl >/dev/null 2>&1; then DOWNLOADER="curl"
        elif command -v wget >/dev/null 2>&1; then DOWNLOADER="wget"
        else die "Neither curl nor wget found on PATH."
        fi
    fi
}

# ---- Paths ----------------------------------------------------------

product_root()     { echo "${INSTALL_ROOT}/${PRODUCT}"; }
releases_dir()     { echo "$(product_root)/releases"; }
current_link()     { echo "$(product_root)/current"; }
default_data_dir() { echo "$(product_root)/data"; }
resolved_data_dir() {
    if [[ -n "${DATA_DIR}" ]]; then
        echo "${DATA_DIR}"
    elif [[ -L "$(product_root)/data" ]]; then
        readlink "$(product_root)/data"
    else
        default_data_dir
    fi
}
pid_file()  { echo "$(resolved_data_dir)/run/${PRODUCT}.pid"; }
log_file()  { echo "$(resolved_data_dir)/logs/${PRODUCT}.out"; }
conf_file() { echo "$(resolved_data_dir)/conf/appbuilder-mcp.conf"; }

# ---- Version detection ----------------------------------------------

current_version() {
    local link="$(current_link)"
    [[ -L "${link}" ]] || { echo ""; return; }
    basename "$(readlink "${link}")" | sed 's/^v//'
}

# ---- Tarball acquisition --------------------------------------------

fetch_tarball() {
    local dest="$1"
    if [[ -n "${LOCAL_DIST}" ]]; then
        [[ -f "${LOCAL_DIST}" ]] || die "--local-dist file not found: ${LOCAL_DIST}"
        info "Using local dist: ${LOCAL_DIST}"
        cp "${LOCAL_DIST}" "${dest}"
        if [[ -z "${VERSION}" ]]; then
            VERSION="$(basename "${LOCAL_DIST}" \
                | sed -E "s/^${ARTIFACT_ID}-//" | sed -E "s/\.tar\.gz\$//")"
            debug "Derived version from filename: ${VERSION}"
        fi
        return
    fi
    [[ -n "${VERSION}" ]] || die "No version to install. Pass --version VER or --local-dist FILE."
    local url="${DEFAULT_DOWNLOAD_BASE}/${VERSION}/${ARTIFACT_ID}-${VERSION}.tar.gz"
    info "Downloading ${url}"
    case "${DOWNLOADER}" in
        curl) curl -fsSL -o "${dest}" "${url}" || die "download failed: ${url}" ;;
        wget) wget -q -O   "${dest}" "${url}" || die "download failed: ${url}" ;;
    esac
}

# ---- Install / upgrade ----------------------------------------------

do_install() {
    local existing
    existing="$(current_version)"
    [[ -n "${existing}" ]] && info "Upgrade path — existing install at v${existing}." \
                          || info "Fresh install."

    local staging
    staging="$(mktemp -d)"
    trap "rm -rf '${staging}'" EXIT
    local tarball="${staging}/dist.tar.gz"
    fetch_tarball "${tarball}"

    if [[ "${DOWNLOAD_ONLY}" == "true" ]]; then
        local keep="${PWD}/${ARTIFACT_ID}-${VERSION:-unknown}.tar.gz"
        mv "${tarball}" "${keep}"
        info "Tarball saved at ${keep}"
        trap - EXIT; rm -rf "${staging}"
        return
    fi

    [[ -n "${VERSION}" ]] || die "Version unknown after fetch."

    mkdir -p "$(releases_dir)" || die "cannot create $(releases_dir)"
    ensure_data_dir

    if [[ -n "${existing}" ]]; then
        stop_service || warn "Could not stop existing service cleanly — continuing."
    fi

    local release_dir="$(releases_dir)/v${VERSION}"
    if [[ -d "${release_dir}" ]]; then
        [[ "${FORCE}" == "true" ]] || confirm "Release dir ${release_dir} exists. Replace?" \
            || die "Aborted."
        rm -rf "${release_dir}"
    fi
    mkdir -p "${release_dir}"
    info "Unpacking tarball into ${release_dir}"
    # Tarball contains a single top-level dir 'rumi-appbuilder-mcp/'.
    # Strip it so release_dir holds the contents directly.
    tar -xzf "${tarball}" -C "${release_dir}" --strip-components=1 || die "tar extraction failed"

    # Build the venv and install the wheel into it.
    info "Creating venv + installing wheel"
    "${PYTHON_BIN}" -m venv "${release_dir}/venv" || die "venv creation failed"
    "${release_dir}/venv/bin/pip" install --quiet --upgrade pip setuptools wheel >/dev/null
    local wheel
    wheel="$(ls "${release_dir}/wheel/"*.whl | head -1)"
    [[ -f "${wheel}" ]] || die "No wheel found under ${release_dir}/wheel/"
    "${release_dir}/venv/bin/pip" install --quiet "${wheel}" >/dev/null \
        || die "wheel install failed"

    chmod +x "${release_dir}/scripts/"*.sh 2>/dev/null || true

    # Bootstrap conf file on first install; preserve across upgrades.
    if [[ ! -f "$(conf_file)" ]]; then
        info "Bootstrapping config: $(conf_file)"
        cp "${release_dir}/conf/appbuilder-mcp.conf.sample" "$(conf_file)"
    fi

    # Apply overrides into the data-dir conf file. Preserved across upgrades.
    [[ -n "${PORT}"     ]] && set_conf "RUMI_APPBUILDER_MCP_PORT" "${PORT}"
    [[ -n "${REST_URL}" ]] && set_conf "RUMI_APPBUILDER_REST_URL" "${REST_URL}"

    # Atomic symlink retarget.
    local link="$(current_link)"
    local tmp_link="${link}.new"
    rm -f "${tmp_link}"
    ln -s "${release_dir}" "${tmp_link}"
    mv -fT "${tmp_link}" "${link}" 2>/dev/null || { rm -f "${link}"; mv "${tmp_link}" "${link}"; }
    info "current → $(readlink "${link}")"

    [[ "${NO_START}" == "true" ]] && { info "Skipping start (--no-start)."; return; }
    start_service
    health_check
    print_info
    trap - EXIT; rm -rf "${staging}"
}

# Idempotent setter: strip any prior KEY= line, then append KEY=VALUE.
set_conf() {
    local key="$1" val="$2" conf="$(conf_file)"
    info "Setting ${key}=${val} in $(basename "${conf}")"
    local tmp="${conf}.new"
    awk -v k="${key}" '$0 !~ "^"k"=" {print}' "${conf}" > "${tmp}"
    echo "${key}=${val}" >> "${tmp}"
    mv "${tmp}" "${conf}"
}

ensure_data_dir() {
    local data_link="$(product_root)/data"
    if [[ -L "${data_link}" ]]; then
        local existing_target
        existing_target="$(readlink "${data_link}")"
        if [[ -n "${DATA_DIR}" && "${existing_target}" != "${DATA_DIR}" ]]; then
            [[ "${FORCE}" == "true" ]] || confirm \
                "Data symlink points at ${existing_target}. Retarget to ${DATA_DIR}?" \
                || die "Aborted."
            mkdir -p "${DATA_DIR}"
            rm "${data_link}"
            ln -s "${DATA_DIR}" "${data_link}"
        fi
    elif [[ -d "${data_link}" ]]; then
        if [[ -n "${DATA_DIR}" ]]; then
            [[ "${FORCE}" == "true" ]] || confirm \
                "Convert existing data/ into a symlink to ${DATA_DIR}?" || die "Aborted."
            mkdir -p "${DATA_DIR}"
            cp -R "${data_link}"/. "${DATA_DIR}/" 2>/dev/null || true
            rm -rf "${data_link}"
            ln -s "${DATA_DIR}" "${data_link}"
        fi
    else
        if [[ -n "${DATA_DIR}" ]]; then
            mkdir -p "${DATA_DIR}"
            ln -s "${DATA_DIR}" "${data_link}"
        else
            mkdir -p "${data_link}"
        fi
    fi
    mkdir -p "$(resolved_data_dir)/logs" \
             "$(resolved_data_dir)/run" \
             "$(resolved_data_dir)/conf"
}

# ---- Start / stop / health ------------------------------------------

start_service() {
    local link="$(current_link)"
    [[ -d "${link}" ]] || die "No install found at ${link}"
    local launcher="${link}/scripts/launch.sh"
    [[ -x "${launcher}" ]] || die "Launcher not executable: ${launcher}"

    info "Starting service (daemonized)"
    mkdir -p "$(dirname "$(pid_file)")" "$(dirname "$(log_file)")"

    (
        cd "${link}"
        nohup "${launcher}" "$(conf_file)" >> "$(log_file)" 2>&1 &
        echo $! > "$(pid_file)"
    )
    debug "PID $(cat "$(pid_file)") → $(log_file)"
}

stop_service() {
    local link="$(current_link)"
    local shutdown_script="${link}/scripts/shutdown.sh"
    if [[ -x "${shutdown_script}" ]]; then
        "${shutdown_script}" "$(pid_file)" || true
    else
        # Fallback when current/ is already gone (uninstall path).
        local pf="$(pid_file)"
        [[ -f "${pf}" ]] || return 0
        local pid
        pid="$(cat "${pf}" 2>/dev/null || true)"
        [[ -n "${pid}" ]] || { rm -f "${pf}"; return 0; }
        info "Stopping service (pid ${pid})"
        kill -TERM "${pid}" 2>/dev/null || true
        sleep 3
        kill -KILL "${pid}" 2>/dev/null || true
        rm -f "${pf}"
    fi
}

# MCP streamable-http doesn't expose /health, but the listen port comes
# up as soon as the server is ready. Probe it with a short TCP connect.
health_check() {
    local port host
    port="$(awk -F= '/^RUMI_APPBUILDER_MCP_PORT=/ {print $2}' "$(conf_file)" | tail -1)"
    host="$(awk -F= '/^RUMI_APPBUILDER_MCP_HOST=/ {print $2}' "$(conf_file)" | tail -1)"
    port="${port:-3201}"; host="${host:-127.0.0.1}"

    info "Waiting for ${host}:${port} to accept connections (up to ${HEALTH_TIMEOUT_SECS}s)"
    local waited=0
    while [[ ${waited} -lt ${HEALTH_TIMEOUT_SECS} ]]; do
        if python3 -c "import socket,sys; s=socket.socket(); s.settimeout(1); s.connect(('${host}', ${port})); s.close()" 2>/dev/null; then
            info "MCP server is listening."
            return 0
        fi
        sleep 1
        waited=$((waited + 1))
    done
    warn "MCP port did not open within ${HEALTH_TIMEOUT_SECS}s. Check: tail -n 80 $(log_file)"
    return 1
}

print_info() {
    local port host
    port="$(awk -F= '/^RUMI_APPBUILDER_MCP_PORT=/ {print $2}' "$(conf_file)" | tail -1)"
    host="$(awk -F= '/^RUMI_APPBUILDER_MCP_HOST=/ {print $2}' "$(conf_file)" | tail -1)"
    port="${port:-3201}"; host="${host:-127.0.0.1}"
    echo
    info "MCP endpoint:       http://${host}:${port}/mcp"
    info "Config:             $(conf_file)"
    info "Logs:               tail -f $(log_file)"
    echo
}

# ---- Uninstall ------------------------------------------------------

do_uninstall() {
    local root="$(product_root)"
    [[ -d "${root}" ]] || die "Nothing to uninstall at ${root}"

    if [[ "${FORCE}" != "true" ]]; then
        echo "This will remove ${root}/{current,releases}."
        [[ "${PURGE}" == "true" ]] && echo "Data dir will ALSO be removed (--purge)."
        confirm "Proceed?" || die "Aborted."
    fi

    stop_service || true
    rm -f "$(current_link)"
    rm -rf "$(releases_dir)"
    info "Removed current/ and releases/."

    if [[ "${PURGE}" == "true" ]]; then
        local data="$(resolved_data_dir)"
        if [[ -n "${data}" && -e "${data}" ]]; then
            rm -rf "${data}"
            info "Removed data directory: ${data}"
        fi
        rm -rf "${root}"
    fi
    info "Uninstall complete."
}

# ---- Utilities ------------------------------------------------------

confirm() {
    local prompt="$1" reply
    read -r -p "${prompt} [y/N] " reply </dev/tty || return 1
    [[ "${reply}" =~ ^[Yy]([Ee][Ss])?$ ]]
}

# ---- Main -----------------------------------------------------------

main() {
    parse_args "$@"
    preflight
    case "${MODE}" in
        install)   do_install ;;
        uninstall) do_uninstall ;;
    esac
}

main "$@"
