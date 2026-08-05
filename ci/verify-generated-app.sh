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
#   2. scaffolds a `demo` app with EVERY service type (processor, driver,
#      connector, webservice) plus a custom connector snapped into the
#      processor
#   3. drops in the in-process JUnit/EmbeddedXVM tests
#   4. builds and RUNS the generated system
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

info "Scaffolding a demo app with every service type"
cat > "${WORK_DIR}/Build.java" <<'EOF'
import com.neeve.appbuilder.ConnectorEditor;
import com.neeve.appbuilder.test.TestAppFactory;
import java.nio.file.Path;
import java.nio.file.Paths;

/**
 * Scaffolds the system the release is verified against. Uses only builder
 * operations, so what runs is what a user would get.
 */
public class Build {
    public static void main(String[] args) throws Exception {
        Path parent = Paths.get(args[0]);
        String rumiVersion = args[1];

        Path app = TestAppFactory.newApp("demo")
            .packageName("com.example.demo")
            .rumiVersion(rumiVersion)
            .rumiBindingsVersion(rumiVersion)
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

APP="$("${JAVA_HOME}/bin/java" -cp "${CP}:${WORK_DIR}" Build "${WORK_DIR}/out" "${RUMI_VERSION}" | tail -1)"
[[ -d "${APP}" ]] || fail "Scaffolding did not produce an app at '${APP}'"
info "Scaffolded ${APP}"

# ---- 3. Drop in the in-process tests --------------------------------

# These live beside the /test-the-builder skill, so the manual and automated
# paths exercise the same tests rather than drifting into two versions.
EXAMPLES="${REPO_DIR}/.claude/skills/test-the-builder/examples"
TEST_DIR="${APP}/test-demo-system/src/test/java/com/example/demo"
[[ -d "${EXAMPLES}" ]] || fail "Missing test sources at ${EXAMPLES}"
mkdir -p "${TEST_DIR}"

# SystemBootTest boots all four service types plus the snapped connector;
# WebserviceTest drives the full HTTP -> engine -> state -> reply round trip.
# FlowTest is deliberately excluded: it needs hand edits the builder still has
# no operation for, so it cannot run unattended.
for t in SystemBootTest WebserviceTest; do
    cp "${EXAMPLES}/${t}.java" "${TEST_DIR}/" \
        || fail "Could not stage ${t}"
done

# ---- 4. Build AND run -----------------------------------------------

info "Building and running the generated system"
cd "${APP}"
if ! "${MVN}" test -DfailIfNoTests=false; then
    echo >&2
    echo "!! The generated app failed to build or run." >&2
    echo "!! This is a builder defect, not a test defect — the generated app is" >&2
    echo "!! the product. Inspect it at: ${APP}" >&2
    exit 1
fi

info "Generated app built and ran cleanly against Rumi ${RUMI_VERSION}"

# Only clean up on success; a failed run leaves the app for inspection.
if [[ -z "${WORK_DIR_PRESERVE:-}" ]]; then
    rm -rf "${WORK_DIR}"
fi
