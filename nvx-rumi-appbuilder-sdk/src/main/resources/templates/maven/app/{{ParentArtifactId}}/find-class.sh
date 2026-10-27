#!/usr/bin/env bash
#
# Which Rumi artifact declares this class?
#
# Rumi is ~40 jars and the package name does not name the artifact: RogXStringMap
# is `com.neeve.rog.*` and ships in **nvx-rumi-ods**, which nothing about the name
# tells you. Finding it the hard way costs two wrong `javap` guesses and then a
# scan of every jar on the machine, and the answer is needed at exactly the
# moment you are least able to go looking: mid-edit, with a `cannot find symbol`.
#
# So this answers one question -- which artifact, and what to paste into a POM --
# by looking ONLY at the Rumi jars for the version this app is pinned to.
#
#   ./find-class.sh RogXStringMap        # a bare name, or any fragment
#   ./find-class.sh com.neeve.rog.RogXStringMap
#
# It reads the local Maven repository and starts no build, so it works offline
# and costs nothing. A jar Maven has not fetched yet is invisible to it -- build
# once if a class you are sure exists does not turn up.
set -euo pipefail
cd "$(dirname "$0")"

if [ "$#" -lt 1 ]; then
    echo "usage: ./find-class.sh <ClassName|package.ClassName|fragment>" >&2
    exit 2
fi

RUMI_VERSION="{{RumiVersion}}"
REPO="${HOME}/.m2/repository/com/neeve"
# A dotted name is a path inside a jar; a bare name matches any package. Both
# anchor on `/` + name + `.class` so `RogNode` does not also report `RogNodeImpl`
# -- a substring match here returns a list to triage, which is the problem.
NEEDLE="${1//.//}"
case "$1" in
    *.*) PATTERN="(^|/)${NEEDLE}\.class$" ;;
    *)   PATTERN="/${NEEDLE}\.class$" ;;
esac

if [ ! -d "${REPO}" ]; then
    echo "No Rumi artifacts in ${REPO} -- build this app once, then try again." >&2
    exit 1
fi

# -print0/-d '' so a path with a space cannot split. `|| true` on find: a
# permission error on one subtree must not take the whole search down.
FOUND=0
while IFS= read -r -d '' jar; do
    # unzip -l rather than `jar tf`: no JDK needed, and it is the faster of the
    # two on an archive this size.
    #
    # WARNING: NOT `grep -q`. `grep -q` exits the instant it matches, `unzip` then dies
    # of SIGPIPE, and `set -o pipefail` above reports the whole pipeline as
    # FAILED -- so a successful match reads as no match and this script finds
    # nothing, ever. Redirecting to /dev/null instead makes grep consume all of
    # the input, so unzip finishes normally and the exit status means what it says.
    if unzip -l "${jar}" 2>/dev/null | grep -E "${PATTERN}" >/dev/null; then
        artifact="$(basename "$(dirname "$(dirname "${jar}")")")"
        echo "${artifact}"
        echo "    jar: ${jar}"
        echo "    <dependency>"
        echo "      <groupId>com.neeve</groupId>"
        echo "      <artifactId>${artifact}</artifactId>"
        echo "      <version>\${nvx.rumi.version}</version>"
        echo "    </dependency>"
        FOUND=$((FOUND + 1))
    fi
done < <(find "${REPO}" -name "*-${RUMI_VERSION}.jar" ! -name "*-sources.jar" \
             ! -name "*-javadoc.jar" -print0 2>/dev/null || true)

if [ "${FOUND}" -eq 0 ]; then
    echo "No Rumi ${RUMI_VERSION} artifact declares a class matching '$1'." >&2
    echo "Check the spelling, or build once so Maven fetches the jars." >&2
    exit 1
fi
