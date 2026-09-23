#!/usr/bin/env bash
#
# Run this whole system IN ONE PROCESS, so you can put it in front of someone.
#
# This is one of THREE ways to run a Rumi app:
#
#   in-process  (this script)        every service in one JVM, loopback bus,
#                                    `test` profile. Seconds to start. No broker,
#                                    no Docker, no `mvn install`.
#   local       (`rumi cloud local`) Docker on this machine running the `cloud`
#                                    profile. NOT a preview -- it exists to PROVE
#                                    the cloud profile before you promote it.
#   cloud                            the real deployment, off this machine.
#
# Nothing here is called "local", because that word is already taken by the
# second one. See {{SystemArtifactId}}/src/main/java/.../InProcessRun.java.
#
# The `-am` is what makes this work without `mvn install`: it builds the sibling
# service modules in the same reactor, so the classpath resolves from the build
# rather than from your local repository. Trying to assemble that classpath by
# hand is the thing this script exists to stop you doing.
set -euo pipefail
cd "$(dirname "$0")"
# Anything you pass is forwarded to the RUNNER's JVM, e.g. to move a port that
# something else on this machine already holds:
#   ./run-in-process.sh -D{{AppTokenName}}.local.<service>.http.port=8188
EXTRA=()
if [ "$#" -gt 0 ]; then EXTRA=("-Drumi.inprocess.args=$*"); fi
# ${EXTRA[@]:+...} and not "${EXTRA[@]}": expanding an EMPTY array under `set -u`
# is an unbound-variable error in bash < 4.4, and /bin/bash on macOS is 3.2 -- so
# the plain form breaks the no-argument invocation, which is the documented one.
# CI on bash 5 can never catch it.
exec mvn -q -am -pl {{SystemArtifactId}} -Pin-process process-classes ${EXTRA[@]:+"${EXTRA[@]}"}
