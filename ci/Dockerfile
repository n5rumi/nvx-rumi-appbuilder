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

# JDK 17 + Maven + the PEP 517 'build' frontend (for build/build-dist.sh).
#
# Maven is installed from Apache, NOT apt: Debian bookworm ships Maven 3.9,
# whose maven-default-http-blocker (added in 3.8.1) refuses the http:// Neeve/
# Rumi nexus repos the nvx-os-parent POM hierarchy declares. Pin a pre-3.8.1
# Maven so the container resolves those (open, http) repos the same way the
# org's build agents (Maven 3.5.4) do.
ARG MAVEN_VERSION=3.6.3
RUN apt-get update \
    && apt-get install -y --no-install-recommends \
        openjdk-17-jdk-headless ca-certificates curl \
    && rm -rf /var/lib/apt/lists/* \
    && curl -fsSL "https://archive.apache.org/dist/maven/maven-3/${MAVEN_VERSION}/binaries/apache-maven-${MAVEN_VERSION}-bin.tar.gz" \
        | tar -xz -C /opt \
    && ln -sf "/opt/apache-maven-${MAVEN_VERSION}/bin/mvn" /usr/local/bin/mvn \
    && python3.11 -m pip install --no-cache-dir build

# ci/release.sh requires JAVA_HOME; provide it from the image (the host's
# %env.JDK_17% would point at a path that doesn't exist inside the container).
ENV JAVA_HOME=/usr/lib/jvm/java-17-openjdk-amd64
