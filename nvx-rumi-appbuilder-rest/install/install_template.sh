#!/bin/bash
#
# Copyright 2022 N5 Technologies, Inc
#
# Licensed under the Apache License, Version 2.0 (the "License").
#
# Rumi App Builder REST — installer / upgrader / uninstaller.
#
# Shape modelled on the Rumi Management Agent installer: symlink-based
# versioning (current -> releases/v<version>), separate data directory
# preserved across upgrades, no privileged install required. Adds
# --port and --uninstall flags which the Mgmt Agent installer does not
# yet have.
#
# Invocation:
#   curl -sSL https://downloads.n5corp.com/rumi/appbuilder-rest/<ver>/install.sh | bash
#   ./install.sh --from /path/to/<tarball>.tar.gz
#   ./install.sh --uninstall
#
# The __VERSION__ placeholder below is replaced by publish_installer.sh
# when the installer is published. During local iteration, pass
# --from <file> and --version <ver> to bypass the download.
set -u
set -o pipefail

# ---- Constants ------------------------------------------------------

readonly INSTALLER_NAME="Rumi App Builder REST Installer"
readonly PRODUCT="rumi-appbuilder-rest"
# XVM (Rumi container) name this service launches/stops as via xvm.sh.
readonly XVM_NAME="appbuilder-rest"
readonly ARTIFACT_GROUP="com.neeve"
readonly ARTIFACT_ID="nvx-rumi-appbuilder-rest"
readonly DEFAULT_VERSION="__VERSION__"
readonly DEFAULT_INSTALL_ROOT="${HOME}/rumi"
readonly DEFAULT_PORT_FILE_VAR="RUMI_APPBUILDER_REST_PORT"
readonly DEFAULT_DOWNLOAD_BASE="${RUMI_APPBUILDER_DOWNLOAD_BASE:-https://downloads.n5corp.com/rumi/appbuilder-rest}"
readonly HEALTH_PATH="/health"
readonly HEALTH_TIMEOUT_SECS="${RUMI_APPBUILDER_HEALTH_TIMEOUT:-30}"

# ---- Globals populated by parse_args --------------------------------

MODE="install"      # install | uninstall
VERSION="${DEFAULT_VERSION}"
INSTALL_ROOT=""
DATA_DIR=""
PORT=""
LOCAL_DIST=""
FORCE="false"
VERBOSE="false"
NO_START="false"
DOWNLOAD_ONLY="false"
PURGE="false"
ARCH=""

# ---- Pretty-print helpers -------------------------------------------

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

# ---- Usage -----------------------------------------------------------

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
  --data-dir DIR           External data dir (config, logs, PID) via symlink.
                           Default: <install-dir>/${PRODUCT}/data.
  --port N                 HTTP port for the REST service (default: 3200).
  --version VER            Release version to install (default: ${DEFAULT_VERSION}).
  --from FILE        Use a local tarball instead of downloading.
  --download-only          Download the tarball and exit without installing.
  --no-start               Install / upgrade without starting the service.
  --force                  Skip confirmation prompts on destructive operations.
  --verbose                Print extra diagnostic output.

Uninstall options:
  --uninstall              Stop the service, remove binaries. Preserves the
                           data directory unless --purge is set.
  --purge                  With --uninstall, also remove the data directory.

Environment:
  JAVA_HOME                Must point at a Java 17+ install.
  RUMI_APPBUILDER_DOWNLOAD_BASE
                           Override the default downloads server.
  RUMI_APPBUILDER_HEALTH_TIMEOUT
                           Seconds to wait for /health after start (default: ${HEALTH_TIMEOUT_SECS}).
EOF
}

# ---- Argument parsing -----------------------------------------------

parse_args() {
    while [[ $# -gt 0 ]]; do
        case "$1" in
            -h|--help) print_usage; exit 0 ;;
            --install-dir)    INSTALL_ROOT="$2"; shift 2 ;;
            --install-dir=*)  INSTALL_ROOT="${1#--install-dir=}"; shift ;;
            --data-dir)       DATA_DIR="$2"; shift 2 ;;
            --data-dir=*)     DATA_DIR="${1#--data-dir=}"; shift ;;
            --port)           PORT="$2"; shift 2 ;;
            --port=*)         PORT="${1#--port=}"; shift ;;
            --version)        VERSION="$2"; shift 2 ;;
            --version=*)      VERSION="${1#--version=}"; shift ;;
            --from)     LOCAL_DIST="$2"; shift 2 ;;
            --from=*)   LOCAL_DIST="${1#--from=}"; shift ;;
            --download-only)  DOWNLOAD_ONLY="true"; shift ;;
            --no-start)       NO_START="true"; shift ;;
            --force)          FORCE="true"; shift ;;
            --verbose)        VERBOSE="true"; shift ;;
            --uninstall)      MODE="uninstall"; shift ;;
            --purge)          PURGE="true"; shift ;;
            *) die "unknown option '$1' (see --help)" ;;
        esac
    done

    INSTALL_ROOT="${INSTALL_ROOT:-${DEFAULT_INSTALL_ROOT}}"
    # Note: sentinel is split so sed's __VERSION__ substitution doesn't replace it
    [[ "${VERSION}" == "__""VERSION__" ]] && VERSION=""
}

# ---- Pre-flight ------------------------------------------------------

preflight() {
    # Java.
    [[ -n "${JAVA_HOME:-}" ]] || die "JAVA_HOME is not set. Point it at a Java 17+ install and retry."
    [[ -x "${JAVA_HOME}/bin/java" ]] || die "JAVA_HOME/bin/java is not executable at ${JAVA_HOME}/bin/java."
    local jver
    jver="$("${JAVA_HOME}/bin/java" -version 2>&1 | awk -F[\".] '/version/ {print $2; exit}')"
    if [[ -z "${jver}" || "${jver}" -lt 17 ]]; then
        die "Java 17+ required (JAVA_HOME points at Java '${jver}')."
    fi
    debug "Java ${jver} at ${JAVA_HOME}"

    # Download tool (only needed if we'll download).
    if [[ -z "${LOCAL_DIST}" && "${MODE}" == "install" ]]; then
        if   command -v curl  >/dev/null 2>&1; then DOWNLOADER="curl"
        elif command -v wget  >/dev/null 2>&1; then DOWNLOADER="wget"
        else die "Neither curl nor wget found on PATH; install one or use --from."
        fi
        debug "Downloader: ${DOWNLOADER}"
    fi
}

detect_arch() {
    local os cpu
    case "$(uname -s)" in
        Darwin)  os="osx" ;;
        Linux)   os="linux" ;;
        *)       die "Unsupported OS: $(uname -s)" ;;
    esac
    case "$(uname -m)" in
        x86_64|amd64)  cpu="x86-64" ;;
        aarch64|arm64) cpu="arm-64" ;;
        *)             die "Unsupported CPU: $(uname -m)" ;;
    esac
    # macOS: no arm build is published yet, and Rosetta runs the x86-64 build on
    # Apple Silicon, so always use x86-64 on osx until an arm dist exists.
    if [[ "${os}" == "osx" ]]; then cpu="x86-64"; fi
    ARCH="${os}-${cpu}"
    debug "Detected arch: ${ARCH}"
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
pid_file()         { echo "$(resolved_data_dir)/run/${PRODUCT}.pid"; }
log_file()         { echo "$(resolved_data_dir)/logs/${PRODUCT}.out"; }

# ---- Version detection (upgrade vs fresh install) -------------------

current_version() {
    local link="$(current_link)"
    [[ -L "${link}" ]] || { echo ""; return; }
    local target
    target="$(readlink "${link}")"
    basename "${target}" | sed 's/^v//'
}

# ---- Tarball acquisition --------------------------------------------

fetch_tarball() {
    local dest="$1"

    if [[ -n "${LOCAL_DIST}" ]]; then
        [[ -f "${LOCAL_DIST}" ]] || die "--from file not found: ${LOCAL_DIST}"
        info "Using local dist: ${LOCAL_DIST}"
        cp "${LOCAL_DIST}" "${dest}"
        # Derive version from the filename if not supplied.
        if [[ -z "${VERSION}" ]]; then
            VERSION="$(basename "${LOCAL_DIST}" \
                | sed -E "s/^${ARTIFACT_ID}-//" \
                | sed -E "s/-${ARCH}\.tar\.gz\$//" \
                | sed -E "s/\.tar\.gz\$//")"
            debug "Derived version from filename: ${VERSION}"
        fi
        return
    fi

    [[ -n "${VERSION}" ]] || die "No version to install. Pass --version VER or --from FILE."
    local url="${DEFAULT_DOWNLOAD_BASE}/${VERSION}/${ARTIFACT_ID}-${VERSION}-${ARCH}.tar.gz"
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
    if [[ -n "${existing}" ]]; then
        info "Upgrade path — existing install found at version ${existing}."
    else
        info "Fresh install."
    fi

    # Acquire tarball to a temp path.
    local staging
    staging="$(mktemp -d)"
    trap "rm -rf '${staging}'" EXIT
    local tarball="${staging}/dist.tar.gz"
    fetch_tarball "${tarball}"

    if [[ "${DOWNLOAD_ONLY}" == "true" ]]; then
        local keep="${PWD}/${ARTIFACT_ID}-${VERSION:-unknown}-${ARCH}.tar.gz"
        mv "${tarball}" "${keep}"
        info "Tarball saved at ${keep}"
        trap - EXIT; rm -rf "${staging}"
        return
    fi

    [[ -n "${VERSION}" ]] || die "Version unknown after fetch — cannot determine release name."

    # Create the product tree + data dir.
    mkdir -p "$(releases_dir)" || die "cannot create $(releases_dir)"
    ensure_data_dir

    # Stop an existing service before swapping symlinks.
    if [[ -n "${existing}" ]]; then
        stop_service || warn "Could not stop existing service cleanly — continuing."
    fi

    # Unpack into releases/v<version>/.
    local release_dir="$(releases_dir)/v${VERSION}"
    if [[ -d "${release_dir}" ]]; then
        if [[ "${FORCE}" != "true" ]]; then
            confirm "Release directory ${release_dir} already exists. Replace it?" || die "Aborted."
        fi
        rm -rf "${release_dir}"
    fi
    mkdir -p "${release_dir}"
    info "Unpacking tarball into ${release_dir}"
    tar -xzf "${tarball}" -C "${release_dir}" || die "tar extraction failed"

    # Apply --port override by appending to the release's wrapper.env.vars.
    # Preserved across upgrades because we re-apply on every install when
    # --port is given; the env file lives under releases/v<ver>/conf/,
    # which is re-extracted on each upgrade.
    if [[ -n "${PORT}" ]]; then
        local envf="${release_dir}/conf/wrapper.env.vars"
        if [[ -f "${envf}" ]]; then
            info "Pinning service port to ${PORT} in wrapper.env.vars"
            # Strip any prior entry for this key, then append.
            awk -v k="set.${DEFAULT_PORT_FILE_VAR}" '!index($0, k"=") {print}' "${envf}" > "${envf}.new"
            echo "set.${DEFAULT_PORT_FILE_VAR}=${PORT}" >> "${envf}.new"
            mv "${envf}.new" "${envf}"
        else
            warn "wrapper.env.vars not found at ${envf}; skipping port pin."
        fi
    fi

    # Make scripts and launcher executable.
    chmod +x "${release_dir}"/bin/*.sh "${release_dir}"/scripts/* 2>/dev/null || true

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
    print_urls
    trap - EXIT; rm -rf "${staging}"
}

ensure_data_dir() {
    local data_link="$(product_root)/data"
    local target="${DATA_DIR:-$(default_data_dir)}"

    if [[ -L "${data_link}" ]]; then
        local existing_target
        existing_target="$(readlink "${data_link}")"
        if [[ -n "${DATA_DIR}" && "${existing_target}" != "${DATA_DIR}" ]]; then
            if [[ "${FORCE}" != "true" ]]; then
                confirm "Data symlink currently points at ${existing_target}. Retarget to ${DATA_DIR}?" \
                    || die "Aborted — keep existing data dir or re-run without --data-dir."
            fi
            mkdir -p "${DATA_DIR}"
            rm "${data_link}"
            ln -s "${DATA_DIR}" "${data_link}"
        fi
    elif [[ -d "${data_link}" ]]; then
        if [[ -n "${DATA_DIR}" ]]; then
            if [[ "${FORCE}" != "true" ]]; then
                confirm "Convert existing data/ directory into a symlink pointing at ${DATA_DIR}?" \
                    || die "Aborted."
            fi
            mkdir -p "${DATA_DIR}"
            cp -R "${data_link}"/. "${DATA_DIR}/" 2>/dev/null || true
            rm -rf "${data_link}"
            ln -s "${DATA_DIR}" "${data_link}"
        fi
    else
        # Fresh.
        if [[ -n "${DATA_DIR}" ]]; then
            mkdir -p "${DATA_DIR}"
            ln -s "${DATA_DIR}" "${data_link}"
        else
            mkdir -p "${data_link}"
        fi
    fi

    # Subdirs used for logs and PIDs.
    mkdir -p "$(resolved_data_dir)/logs" "$(resolved_data_dir)/run"
}

# ---- Start / stop / health ------------------------------------------

start_service() {
    local link="$(current_link)"
    [[ -d "${link}" ]] || die "No install found at ${link}"
    local xvm_script="${link}/bin/xvm.sh"
    [[ -x "${xvm_script}" ]] || die "Launcher not executable: ${xvm_script}"

    info "Starting service (daemonized)"
    mkdir -p "$(dirname "$(pid_file)")" "$(dirname "$(log_file)")"

    # Launch in a detached subshell so the PID we record is the long-
    # running wrapper, not this shell. The wrapper forks a JVM and
    # manages restarts itself.
    (
        cd "${link}"
        nohup "${xvm_script}" "${XVM_NAME}" >> "$(log_file)" 2>&1 &
        echo $! > "$(pid_file)"
    )
    debug "PID $(cat "$(pid_file)") → $(log_file)"
}

stop_service() {
    local link="$(current_link)"
    local xvm_script="${link}/bin/xvm.sh"
    local pf="$(pid_file)"

    if [[ ! -x "${xvm_script}" ]]; then
        debug "No xvm.sh at ${xvm_script}; nothing to stop."
        rm -f "${pf}"
        return 0
    fi

    # The container is launched directly via xvm.sh, so it is stopped the same
    # way: Main's '--action stop' discovers the running XVM, connects to its
    # admin port, and issues a graceful shutdown — the XVM then stops its own
    # JVM (no orphaned process). Killing the launcher pid does NOT do this,
    # because the wrapper forks/detaches the JVM. (The scripts/launch|shutdown
    # DSL is only for the controller / xar deployment path, not direct launch.)
    info "Stopping service (xvm --action stop)"
    ( cd "${link}" && "${xvm_script}" "${XVM_NAME}" --action stop ) >> "$(log_file)" 2>&1 \
        || warn "Graceful stop reported an error (the XVM may not have been running)."
    rm -f "${pf}"
}

health_check() {
    local port="${PORT:-3200}"
    local url="http://127.0.0.1:${port}${HEALTH_PATH}"
    info "Waiting for ${url} (up to ${HEALTH_TIMEOUT_SECS}s)"
    local waited=0
    while [[ ${waited} -lt ${HEALTH_TIMEOUT_SECS} ]]; do
        if curl -fsS -m 2 "${url}" >/dev/null 2>&1; then
            info "Service is healthy."
            return 0
        fi
        sleep 1
        waited=$((waited + 1))
    done
    warn "/health did not respond within ${HEALTH_TIMEOUT_SECS}s. Check the log:"
    warn "  tail -n 80 $(log_file)"
    return 1
}

print_urls() {
    local port="${PORT:-3200}"
    echo
    info "Service endpoints:"
    echo "    ${BOLD}health${RESET}     http://127.0.0.1:${port}/health"
    echo "    ${BOLD}swagger${RESET}    http://127.0.0.1:${port}/swagger"
    echo "    ${BOLD}openapi${RESET}    http://127.0.0.1:${port}/openapi"
    echo
    info "Manage the service with:"
    echo "    $(current_link)/scripts/launch"
    echo "    $(current_link)/scripts/shutdown"
    echo "    ${BOLD}tail${RESET}        tail -f $(log_file)"
    echo
}

# ---- Uninstall ------------------------------------------------------

do_uninstall() {
    local root="$(product_root)"
    [[ -d "${root}" ]] || die "Nothing to uninstall at ${root}"

    if [[ "${FORCE}" != "true" ]]; then
        echo "This will remove ${root}/{current,releases}."
        [[ "${PURGE}" == "true" ]] && echo "Data directory will ALSO be removed (--purge)."
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
    local prompt="$1"
    local reply
    read -r -p "${prompt} [y/N] " reply </dev/tty || return 1
    [[ "${reply}" =~ ^[Yy]([Ee][Ss])?$ ]]
}

# ---- Main -----------------------------------------------------------

main() {
    parse_args "$@"
    detect_arch
    preflight

    case "${MODE}" in
        install)   do_install ;;
        uninstall) do_uninstall ;;
        *)         die "Unknown mode '${MODE}'" ;;
    esac
}

main "$@"
