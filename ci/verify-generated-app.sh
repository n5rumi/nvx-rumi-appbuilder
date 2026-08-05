#!/bin/bash
#
# Copyright 2022 N5 Technologies, Inc
#
# Licensed under the Apache License, Version 2.0.
#
# RUMI-379 — prove the builder produces apps that BUILD AND RUN, before the
# release publishes anything.
#
# Why this is a release gate and not a nice-to-have
# -------------------------------------------------
# `mvn package` on this repo compiles the *scaffolder*, never the *templates*.
# A template that references a missing API, emits malformed XML, or pins the
# wrong dependency sails through the entire test suite and only explodes in
# the user's generated app. The manual /test-the-builder skill earned its keep
# the first time it ran, catching four such bugs — ConfigInjector emitting
# xmlns="" that EmbeddedXVM rejects, Rumi 4.0 needing BOTH javax and jakarta
# JAXB before the engine would start, a `--` inside a POM comment, and port
# 8080 collisions. Every one passed the unit tests and would have shipped.
#
# Those are exactly the failures an agent cannot diagnose: the builder reports
# success, the app does not build, and the agent concludes its own code is
# wrong. So this runs automatically, and a failure blocks the release.
#
# What it does
#   1. builds + installs the SDK
#   2. for EACH scaffold mode (with samples, and sample-free):
#      a. scaffolds a `demo` app with EVERY service type (processor, driver,
#         connector, webservice) plus a custom connector snapped into the
#         processor
#      b. drops in the in-process JUnit/EmbeddedXVM tests
#      c. builds and RUNS the generated system
#
# Both modes, because both ship (RUMI-382)
# ----------------------------------------
# The sample-free mode is not a lesser variant to be spot-checked — it is what
# every agent driving the Dev MCP gets, so in practice it is the mode most
# generated apps are built from. It also removes code, which is the direction
# that breaks things: an emptied model, a JAX-RS resource down to its last
# endpoint, an import left pointing at a package that no longer has types in
# it. None of that shows up in a compile of the scaffolder. Verifying only the
# sample-rich mode would leave the common path unproven.
#
# Usage:
#   ci/verify-generated-app.sh
#
# Optional env:
#   RUMI_VERSION   — Rumi version for the generated app. Defaults to the
#                    nvx.rumi.version the builder itself targets, which is the
#                    version we actually want proven.
#   WORK_DIR       — scratch dir (default: mktemp -d). Kept on failure so the
#                    broken generated app can be inspected.
#   MVN            — maven binary (default: mvn). Must be >= 3.9.
#   MODES          — space-separated subset of "samples bare" for local
#                    iteration. Not a release skip flag: the release runs both,
#                    and the default here is both.
#
# Exit code is non-zero on the first failure.
set -euo pipefail

REPO_DIR="$(cd "$(dirname "$0")/.." && pwd)"
MVN="${MVN:-mvn}"

info() { echo "==> $*"; }
fail() { echo "!! $*" >&2; exit 1; }

: "${JAVA_HOME:?JAVA_HOME env var is required (Java 17+)}"

# The generated app must be proven against the Rumi version this builder
# targets. Reading it from the POM rather than hardcoding means a milestone
# bump moves this check with it — the whole point of RUMI-377.
if [[ -z "${RUMI_VERSION:-}" ]]; then
    RUMI_VERSION="$("${MVN}" -q -N -DforceStdout \
        help:evaluate -Dexpression=nvx.rumi.version -f "${REPO_DIR}/pom.xml" 2>/dev/null | tail -1)"
fi
[[ -n "${RUMI_VERSION}" ]] || fail "Could not determine nvx.rumi.version from the parent POM"
info "Verifying generated apps against Rumi ${RUMI_VERSION}"

WORK_DIR="${WORK_DIR:-$(mktemp -d)}"
mkdir -p "${WORK_DIR}"

# Rumi 4.0's engine needs Java-17 module access at runtime. Without these the
# generated app compiles and then dies on startup, which is precisely the
# class of failure this script exists to catch — so they are set here rather
# than left to the caller's environment.
export MAVEN_OPTS="${MAVEN_OPTS:-} -Xmx2g \
--add-exports=java.base/sun.nio.ch=ALL-UNNAMED \
--add-opens=java.base/java.nio=ALL-UNNAMED \
--add-opens=java.base/jdk.internal.ref=ALL-UNNAMED"

# ---- 1. SDK ---------------------------------------------------------

info "Building the SDK"
"${MVN}" -q -pl nvx-rumi-appbuilder-sdk -am install -DskipTests -f "${REPO_DIR}/pom.xml" \
    || fail "SDK build failed"

"${MVN}" -q -pl nvx-rumi-appbuilder-sdk dependency:build-classpath \
    -Dmdep.outputFile="${WORK_DIR}/sdk-cp.txt" -f "${REPO_DIR}/pom.xml" \
    || fail "Could not resolve the SDK classpath"

CP="${REPO_DIR}/nvx-rumi-appbuilder-sdk/target/classes:$(cat "${WORK_DIR}/sdk-cp.txt")"

# ---- 2. Scaffold every service type ---------------------------------

info "Preparing the scaffolding driver"
cat > "${WORK_DIR}/Build.java" <<'EOF'
import com.neeve.appbuilder.ConnectorEditor;
import com.neeve.appbuilder.test.TestAppFactory;
import java.nio.file.Path;
import java.nio.file.Paths;

/**
 * Scaffolds the system the release is verified against. Uses only builder
 * operations, so what runs is what a user would get.
 *
 * <p>args: <parentDir> <rumiVersion> <includeSamples>. The services are added
 * with no per-service sample setting on purpose: the mode is recorded in the
 * app's .rumi and inherited, and that inheritance is itself part of what this
 * gate proves.
 */
public class Build {
    public static void main(String[] args) throws Exception {
        Path parent = Paths.get(args[0]);
        String rumiVersion = args[1];
        boolean includeSamples = Boolean.parseBoolean(args[2]);

        Path app = TestAppFactory.newApp("demo")
            .packageName("com.example.demo")
            .rumiVersion(rumiVersion)
            .rumiBindingsVersion(rumiVersion)
            .includeSamples(includeSamples)
            .scaffoldAt(parent);

        TestAppFactory.addProcessor(app, "order-processor");
        TestAppFactory.addDriver(app, "feeder");
        TestAppFactory.addConnector(app, "sink");
        TestAppFactory.addWebservice(app, "gateway");
        // A custom connector snapped into an existing service — a different
        // code path from the `connector` service type above.
        ConnectorEditor.addConnector(app, "order-processor", "audit", false);

        System.out.println(app);
    }
}
EOF

# Explicitly JAVA_HOME's javac/java: a `javac` on PATH may well be an older
# JDK (Java 8 is a common default), which cannot read the SDK's Java 11
# class files and fails with a bare "wrong version" error.
"${JAVA_HOME}/bin/javac" -cp "${CP}" -d "${WORK_DIR}" "${WORK_DIR}/Build.java" \
    || fail "Could not compile the scaffolding driver"

# These live beside the /test-the-builder skill, so the manual and automated
# paths exercise the same tests rather than drifting into two versions.
EXAMPLES="${REPO_DIR}/.claude/skills/test-the-builder/examples"
[[ -d "${EXAMPLES}" ]] || fail "Missing test sources at ${EXAMPLES}"

# ---- 3 & 4. Per mode: scaffold, stage tests, build AND run ----------

# verify_mode <label> <includeSamples> <test...>
#
# SystemBootTest boots all four service types plus the snapped connector and is
# mode-agnostic, so both modes run it. The webservice HTTP round trip is not:
# with samples it is the /echo -> engine -> state -> reply path, and sample-free
# it is the /health probe, which is all a bare resource still exposes.
# FlowTest is deliberately excluded from both: it needs hand edits the builder
# still has no operation for, so it cannot run unattended.
verify_mode() {
    local label="$1"; shift
    local include_samples="$1"; shift
    local out_dir="${WORK_DIR}/out-${label}"

    info "[${label}] Scaffolding a demo app with every service type"
    mkdir -p "${out_dir}"
    local app
    app="$("${JAVA_HOME}/bin/java" -cp "${CP}:${WORK_DIR}" Build \
        "${out_dir}" "${RUMI_VERSION}" "${include_samples}" | tail -1)"
    [[ -d "${app}" ]] || fail "[${label}] Scaffolding did not produce an app at '${app}'"
    info "[${label}] Scaffolded ${app}"

    local test_dir="${app}/test-demo-system/src/test/java/com/example/demo"
    mkdir -p "${test_dir}"
    local t
    for t in "$@"; do
        cp "${EXAMPLES}/${t}.java" "${test_dir}/" || fail "[${label}] Could not stage ${t}"
    done

    info "[${label}] Building and running the generated system"
    if ! (cd "${app}" && "${MVN}" test -DfailIfNoTests=false); then
        echo >&2
        echo "!! [${label}] The generated app failed to build or run." >&2
        echo "!! This is a builder defect, not a test defect — the generated app is" >&2
        echo "!! the product. Inspect it at: ${app}" >&2
        exit 1
    fi
    info "[${label}] Generated app built and ran cleanly against Rumi ${RUMI_VERSION}"
}

for mode in ${MODES:-samples bare}; do
    case "${mode}" in
        samples) verify_mode samples true  SystemBootTest WebserviceTest ;;
        bare)    verify_mode bare    false SystemBootTest BareWebserviceTest ;;
        *)       fail "Unknown mode '${mode}'; expected 'samples' or 'bare'" ;;
    esac
done

info "Generated apps built and ran cleanly against Rumi ${RUMI_VERSION} in every scaffold mode"

# Only clean up on success; a failed run leaves the app for inspection.
if [[ -z "${WORK_DIR_PRESERVE:-}" ]]; then
    rm -rf "${WORK_DIR}"
fi
