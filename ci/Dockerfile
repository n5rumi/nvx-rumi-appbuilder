# App Builder release build toolchain.
#
# Run ci/release.sh inside this image (via ci/release-in-docker.sh) so a
# TeamCity agent needs nothing but Docker — no Python 3.11, no OpenSSL 1.1.1,
# no matching Maven/JDK installed on the host. This is what lets the release
# run on the existing Amazon Linux 2 agents (whose python3 is 3.7).
#
# This image is BUILD tooling ONLY. It has no bearing on how the App Builder
# is deployed: the REST service still ships as a native per-arch tarball +
# xvm.sh + systemd unit, and the MCP as a wheel-in-tarball — neither runs in
# a container.
FROM python:3.11-bookworm

# JDK 17 + Maven (from Debian bookworm) + the PEP 517 'build' frontend that
# build/build-dist.sh uses to produce the MCP wheel.
#
# Debian's Maven is 3.9, whose maven-default-http-blocker (3.8.1+) refuses
# http:// repos. That's fine: the build resolves everything over https — the
# App Builder pom's repositories + pluginRepositories point at
# https://nexus.n5corp.com/repository/maven-public/ — so the http neeve repos
# in the agent settings.xml are never needed (and thus never block).
RUN apt-get update \
    && apt-get install -y --no-install-recommends \
        openjdk-17-jdk-headless maven ca-certificates \
    && rm -rf /var/lib/apt/lists/* \
    && python3.11 -m pip install --no-cache-dir build

# ci/release.sh requires JAVA_HOME; provide it from the image (the host's
# %env.JDK_17% would point at a path that doesn't exist inside the container).
ENV JAVA_HOME=/usr/lib/jvm/java-17-openjdk-amd64
