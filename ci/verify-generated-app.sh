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
#
# RUMI-384: this capture has to be colour-proof. Maven colourises its output
# when it believes stdout is a terminal, and under TeamCity's Docker wrapper it
# does, so the value came back as "4.0.637<ESC>[0m<ESC>[0m". That string is
# stamped straight into the generated app's <nvx.rumi.version>, and every
# dependency it then resolves 404s at a version containing an escape sequence.
# It never reproduced locally because a piped stdout is not a TTY.
#
# Three independent guards, deliberately. Any one of them fixes the observed
# failure; together they also cover the next variant of it.
if [[ -z "${RUMI_VERSION:-}" ]]; then
    RUMI_VERSION="$("${MVN}" -q -B -Dstyle.color=never -N -DforceStdout \
        help:evaluate -Dexpression=nvx.rumi.version -f "${REPO_DIR}/pom.xml" 2>/dev/null | tail -1)"
fi
# 2. Strip any escape sequence that got through anyway, plus stray whitespace/CR.
ESC="$(printf '\033')"
RUMI_VERSION="$(printf '%s' "${RUMI_VERSION}" \
    | sed "s/${ESC}\[[0-9;]*[a-zA-Z]//g" \
    | tr -d '[:space:]')"
[[ -n "${RUMI_VERSION}" ]] || fail "Could not determine nvx.rumi.version from the parent POM"
# 3. A version that is not a version means we captured decoration rather than
#    data. Say so here, naming the value, instead of letting it surface three
#    minutes later as an unresolvable dependency that reads like a build defect.
[[ "${RUMI_VERSION}" =~ ^[0-9][0-9A-Za-z.+_-]*$ ]] \
    || fail "nvx.rumi.version resolved to '$(printf '%q' "${RUMI_VERSION}")', which is not a version"
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

    # ---- the in-process runner (RUMI-426) ----------------------------------
    # Asserted here rather than in a unit test because none of it exists until an
    # app is scaffolded: the runner is a TEMPLATE, and a template that references
    # a missing API or ships at mode 644 passes every test in this repo.
    local runner="${app}/test-demo-system/src/main/java/com/example/demo/InProcessRun.java"
    [[ -f "${runner}" ]] || fail "[${label}] No in-process runner was scaffolded"
    [[ -x "${app}/run-in-process.sh" ]] \
        || fail "[${label}] run-in-process.sh is not executable -- ./run-in-process.sh will fail with permission denied"
    # The name is load-bearing: "local" already means the Docker deployment that
    # proves the cloud profile, and an agent asked to run an app reached for that
    # word unprompted in three separate builds.
    ! grep -qiE 'LocalRun|run-local' "${runner}" "${app}/run-in-process.sh" \
        || fail "[${label}] The runner calls itself 'local', which already means the Docker deployment"
    grep -q 'in-process' "${app}/README.md" \
        || fail "[${label}] The generated README does not explain the in-process runner"
    # A scaffolded app compiles at release 8 while it runs on 17, so anything the
    # JDK gained after 8 compiles nowhere. The compile above catches it, but name
    # the usual offenders so the failure says WHY rather than 'cannot find symbol'.
    # Comment lines stripped first: the runner's javadoc deliberately NAMES the
    # APIs it must not use, and grepping the raw file flagged that prose. A check
    # that fires on its own documentation trains people to delete the check.
    #
    # Materialised into a variable rather than piped into `grep -q`, because under
    # `pipefail` a `grep -q` that matches exits early, the upstream filter dies of
    # SIGPIPE (141), the pipeline status goes non-zero, `!` inverts THAT, and the
    # check silently passes. Latent while the file is small; a no-op once it grows
    # past the pipe buffer.
    local runner_code
    runner_code="$(sed -E 's://.*::' "${runner}" | grep -vE '^[[:space:]]*(\*|/\*)' || true)"
    if printf '%s' "${runner_code}" | grep -qE 'ProcessHandle|List\.of\(|Map\.of\(|Set\.of\('; then
        fail "[${label}] The runner uses a post-Java-8 API; scaffolded modules compile at release 8"
    fi
    # A literal `--` inside an XML comment is illegal and Maven rejects the POM.
    # This repo has been bitten by it before (see the header), and it recurred
    # three times writing the runner, so it is a check rather than a habit.
    local bad_pom
    bad_pom="$(python3 - "${app}" <<'PYEOF'
import re, sys, pathlib
bad = []
for f in pathlib.Path(sys.argv[1]).rglob("pom.xml"):
    for m in re.finditer(r"<!--(.*?)-->", f.read_text(), re.S):
        if re.search(r"(?<!<!)--(?!>)", m.group(1)):
            bad.append(str(f))
print("\n".join(sorted(set(bad))))
PYEOF
)"
    [[ -z "${bad_pom}" ]] || fail "[${label}] Illegal '--' inside an XML comment: ${bad_pom}"

    info "[${label}] In-process runner scaffolded and named correctly"

    # ---- and prove it actually RUNS -----------------------------------------
    # Compiling it proves almost nothing about the thing users invoke: the
    # profile, the -pl/-am reactor invocation, %classpath, -Dbasedir, the config
    # parsing and XVM selection, and the store separation are ALL unexercised by a
    # compile. That is exactly the "passes every test, explodes in the user's app"
    # shape this script exists for.
    #
    # The gateway port is moved off 8080 on purpose: a developer machine often has
    # something on it, and a bind failure here would look like a runner defect.
    info "[${label}] Running the in-process runner"
    local run_log="${WORK_DIR}/${label}-in-process.log"
    (cd "${app}" && ./run-in-process.sh -Ddemo.local.gateway.http.port=8188 \
        > "${run_log}" 2>&1) &
    local runner_job=$!
    local waited=0
    while (( waited < 300 )); do
        grep -q 'service(s): pid=' "${run_log}" 2>/dev/null && break
        kill -0 "${runner_job}" 2>/dev/null || break
        sleep 5; waited=$(( waited + 5 ))
    done
    if ! grep -q 'service(s): pid=' "${run_log}"; then
        echo >&2; echo "!! [${label}] The in-process runner never came up. Log:" >&2
        tail -30 "${run_log}" >&2
        kill -9 "${runner_job}" 2>/dev/null || true
        exit 1
    fi
    local run_pid
    run_pid="$(grep -oE 'pid=[0-9]+' "${run_log}" | head -1 | cut -d= -f2)"
    # A (sev) line means a service faulted after starting -- the runner cannot
    # currently detect that itself, which is why the gate looks.
    # The runner's own advisory line contains the literal "(sev)" (it tells you to
    # look for one), so exclude its output. This is the second check here to fire
    # on prose it documents: a grep for a token the code also NAMES has to exclude
    # the naming, or it reports a fault on a healthy run.
    # awk, not `grep | grep -v`: the runner's own advisory line contains the
    # literal "(sev)" (it tells you to look for one), so the naming has to be
    # excluded or a healthy run reports a fault. And `grep -v` is not portable
    # here for the exit code -- ugrep, which shadows grep on some developer
    # machines, returns 1 from `-v` even when it prints matching lines, so
    # `grep -v ... && ...` silently never fires.
    if awk '/\(sev\)/ && $0 !~ /^\[in-process\]/{f=1} END{exit !f}' "${run_log}"; then
        echo >&2; echo "!! [${label}] A service faulted after starting:" >&2
        awk '/\(sev\)/ && $0 !~ /^\[in-process\]/' "${run_log}" | head -5 >&2
        kill -TERM "${run_pid}" 2>/dev/null || true
        exit 1
    fi
    [[ -d "${app}/test-demo-system/target/inprocess" ]] \
        || fail "[${label}] The runner did not use its own store root (target/inprocess)"
    # And it must be killable: the graceful path can hang inside the engine, so
    # the runner bounds it and halts. If this times out, that bound is broken.
    kill -TERM "${run_pid}" 2>/dev/null || true
    local gone=0
    for _ in $(seq 1 12); do
        sleep 3
        kill -0 "${run_pid}" 2>/dev/null || { gone=1; break; }
    done
    if (( gone == 0 )); then
        kill -9 "${run_pid}" 2>/dev/null || true
        fail "[${label}] The runner did not exit on SIGTERM -- the bounded shutdown is broken"
    fi
    info "[${label}] In-process runner started, served, and exited on SIGTERM"
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
