# CI — App Builder Release Publishing

`ci/release.sh` orchestrates a full release: it builds dist tarballs for the REST service (per-arch), the MCP server (arch-agnostic), and the combined bundle installer, then copies everything into the build agent's local downloads tree at `${DOWNLOADS_ROOT}/rumi/appbuilder{-rest,-mcp,}/...` via the per-service `publish_installer.sh` scripts. This is the same local-copy mechanism every other N5/Rumi/Datafye installer uses (the downloads tree is what fronts `downloads.n5corp.com`) — no S3/CDN client is involved.

## Required runner setup

The release runs inside a build-toolchain container (`ci/Dockerfile`) via
`ci/release-in-docker.sh`, so the agent itself needs only:

- **Docker** — to build the toolchain image and run the release in it.
- **Write access to the local downloads tree** (`DOWNLOADS_ROOT`, e.g. `~/downloads`) that fronts `downloads.n5corp.com` — the same one the agent/CLI release jobs publish into. It is bind-mounted into the container so published artifacts land on the host tree, not the throwaway container.
- Network egress (from the container) to `https://nexus.n5corp.com/repository/maven-public/` (for `nvx-rumi:sandbox-<arch>:tar.gz` and all other Maven artifacts — the pom resolves over https so the Maven 3.9 http-blocker never trips) and to PyPI (for the MCP wheel build backends).

The container image carries Java 17, Maven, and Python 3.11 + `build`, so the
host needs none of those — this is what lets the release run on the existing
Amazon Linux 2 agents (whose `python3` is 3.7, with no modern OpenSSL).

You can still run `ci/release.sh` directly on a host that already has Java 17 +
Maven + Python 3.11 + `build` (e.g. an Amazon Linux 2023 agent) — the container
wrapper is just the zero-host-setup path.

## TeamCity build config

The 1.0-RELEASE build (`RumiGroup_Dev_2_AppBuilder_2_10_10release`) runs the
release inside the toolchain container using TeamCity's native steps:

1. **Build App Builder toolchain image** — Docker runner (`build`) → `appbuilder-build-tools:local` from `ci/Dockerfile` (local, not pushed).
2. **Release REST + MCP + bundle** — command `bash ci/release.sh /downloads`, with **"Run step within Docker container" = `appbuilder-build-tools:local`** and additional docker args `-v %downloads.root%:/downloads -v /home/teamcity/.m2:/root/.m2`. `ci/release.sh` takes the downloads tree as its argument (`/downloads`, the in-container mount point); the host side comes from the shared `downloads.root` parameter.

**Parameters:**

| Name | Value | Notes |
|------|-------|-------|
| `downloads.root` | `/home/teamcity/downloads` | **Defined on the TeamCity Root project**, inherited everywhere. The local downloads tree that fronts downloads.n5corp.com — the same one every other module's publish build writes into. Used as the host side of the `-v` mount. |
| `VERSION` (`env.VERSION`) | `%build.number%` | Release version. Stamped onto the Maven + Python coordinates and written into every tarball + install.sh. |

(No `JAVA_HOME` / `DOWNLOADS_ROOT` build params — Java 17 comes from the image; the downloads root is passed to `ci/release.sh` as an argument.)

The SDK is also deployed to nexus by the preceding `SDK: …` Maven steps in the
same build. On success, tag: `git tag v${VERSION} && git push origin v${VERSION}`.

## TeamCity snapshot build config

The 1.0-SNAPSHOT build (`RumiGroup_Dev_2_AppBuilder_2_10_10snapshot`) is
VCS-triggered off the `1.0` branch and has three steps:

1. **SDK + REST: Build & Deploy** — Maven `clean deploy -U` over the whole reactor.
2. **REST: Package dist** — Maven `clean package -pl nvx-rumi-appbuilder-rest -Pdist -Darch=linux-x86-64 -DskipTests`.
3. **MCP: Validate syntax** — command-line `python3 -m compileall -q nvx-rumi-appbuilder-mcp/src`.

**Both App Builder build configs pin `teamcity.agent.name = Default Agent`.**
The release build needs it for the downloads tree; the snapshot build needs it
because **step 3 requires a `python3` on `PATH`, and only two of the four
authorized agents have one**:

| Agent | `python3` |
|---|---|
| Default Agent | ✅ `/usr/bin/python3` |
| Lab Agent1 (Perf1) | ❌ python2 only |
| Lab Agent2 (Perf1) | ❌ python2 only |
| OSX Agent | ✅ `/usr/bin/python3` |

Without the pin the snapshot build passes or fails purely on where the scheduler
happens to place it — on a lab agent step 3 dies with `python3: command not
found` / exit 127 while the Java steps go green. If you add a step with a new
host-tool dependency, check it against the agent inventory (or add a matching
agent requirement) rather than relying on placement luck.

## Smoke after publish

Once the build reports success, a lightweight integration check:

```bash
curl -sSL https://downloads.n5corp.com/rumi/appbuilder/${VERSION}/install.sh \
    | bash -s -- --install-dir /tmp/smoke-${VERSION} --force
curl -fsS http://127.0.0.1:3200/health
curl -fsS http://127.0.0.1:3201/mcp -X POST -H 'content-type: application/json' \
    -d '{"jsonrpc":"2.0","id":1,"method":"tools/list"}' | head -c 200
bash /tmp/smoke-${VERSION}/rumi-appbuilder-mcp/current/install.sh \
    --install-dir /tmp/smoke-${VERSION} --uninstall --force
bash /tmp/smoke-${VERSION}/rumi-appbuilder-rest/current/install.sh \
    --install-dir /tmp/smoke-${VERSION} --uninstall --force
```

## MCP end-to-end verification (the second release gate)

`ci/release.sh` also runs `ci/verify-mcp-end-to-end.sh` before publishing. It boots
the JAX-RS layer on an ephemeral loopback port, drives the **real** MCP tool
implementations against it, then builds a model **entirely through `apply_model`**
and compiles the generated app.

This exists because the three modules were each well tested and never tested
*together*. Every MCP test mocks the HTTP layer with `respx`, so the suite asserts
what the MCP *would have sent* and never that the service accepts it; the REST
tests drive the resources directly. A field-name mismatch between the MCP's JSON
and a REST DTO passes the entire suite and only fails on a live agent box — the
same shape of failure the generated-app gate exists for, one layer up.

It was not hypothetical. RUMI-412 shipped a `MESSAGE` edit whose `scope` never
reached the editor at all, so `scope: "roe"` wrote into the service's private
model and returned 200; 365 unit tests passed. Writing this check is also what
found that `create_app` requires its `app_dir` to already exist and that the app
lands at `<app_dir>/<prefix>-<name>` — neither of which a mocked test can know.

Like the generated-app gate, it has **no `SKIP_` flag**. Skipping it would let
through exactly the defect it exists to catch.

Optional env: `PORT` (default `0`, an ephemeral port so concurrent builds on one
agent cannot collide), `WORK_DIR`, `MVN`, `VENV`.

## Generated-app verification (the release gate)

Before anything is published, `ci/release.sh` runs `ci/verify-generated-app.sh`.
It scaffolds a `demo` app with **every** service type — processor, driver,
connector, webservice — plus a custom connector snapped into the processor, then
**builds and runs** it through the in-process JUnit/`EmbeddedXVM` harness every
generated app ships. Takes roughly a minute.

This exists because `mvn package` on this repo compiles the *scaffolder*, never
the *templates*. A template that references a missing API, emits malformed XML,
or pins the wrong dependency passes the entire test suite and only explodes in
the user's generated app. When `/test-the-builder` was first run manually it
immediately caught four such bugs — `xmlns=""` on injected config fragments,
Rumi 4.0 needing both javax and jakarta JAXB, a `--` inside a POM comment, and
port-8080 collisions — every one of which had passed the unit tests.

It runs against the Rumi version from `nvx.rumi.version`, read from the parent
POM, so a milestone bump moves the check with it. The test sources are shared
with the `/test-the-builder` skill (`.claude/skills/test-the-builder/examples/`)
rather than duplicated, so the manual and automated paths cannot drift.

There is deliberately **no skip flag**. The other steps have one because
skipping them degrades a release; skipping this one would ship precisely the
defect it exists to catch. On failure the generated app is left on disk and its
path printed — a failure here is a builder defect, not a test defect.

## Skipping parts of a release

- `SKIP_REST=1` — patch-only release that leaves REST untouched.
- `SKIP_MCP=1` — REST-only release.
- `SKIP_BUNDLE=1` — republishing a per-service installer without touching the combined one (rare).

Note there is no skip for the generated-app verification above, by design.

## Layout of published artifacts

Relative to `${DOWNLOADS_ROOT}` (served at `https://downloads.n5corp.com`):

```
rumi/
├── appbuilder/
│   ├── <version>/install.sh            # combined-bundle installer
│   └── latest/install.sh               # rolling pointer
├── appbuilder-rest/
│   ├── <version>/install.sh
│   ├── <version>/nvx-rumi-appbuilder-rest-<version>-linux-x86-64.tar.gz
│   ├── <version>/nvx-rumi-appbuilder-rest-<version>-osx-x86-64.tar.gz
│   │   (one tarball per built arch — currently the x86 arches; arm bases
│   │    aren't published yet)
│   ├── latest/install.sh
│   └── latest/version.txt
└── appbuilder-mcp/
    ├── <version>/install.sh
    ├── <version>/rumi-appbuilder-mcp-<version>.tar.gz
    ├── latest/install.sh
    └── latest/version.txt
```

End users curl the `install.sh` they want and the script figures out the rest.
