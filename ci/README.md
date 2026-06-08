# CI — App Builder Release Publishing

`ci/release.sh` orchestrates a full release: it builds dist tarballs for the REST service (per-arch), the MCP server (arch-agnostic), and the combined bundle installer, then copies everything into the build agent's local downloads tree at `${DOWNLOADS_ROOT}/rumi/appbuilder{-rest,-mcp,}/...` via the per-service `publish_installer.sh` scripts. This is the same local-copy mechanism every other N5/Rumi/Datafye installer uses (the downloads tree is what fronts `downloads.n5corp.com`) — no S3/CDN client is involved.

## Required runner setup

The release runs inside a build-toolchain container (`ci/Dockerfile`) via
`ci/release-in-docker.sh`, so the agent itself needs only:

- **Docker** — to build the toolchain image and run the release in it.
- **Write access to the local downloads tree** (`DOWNLOADS_ROOT`, e.g. `~/downloads`) that fronts `downloads.n5corp.com` — the same one the agent/CLI release jobs publish into. It is bind-mounted into the container so published artifacts land on the host tree, not the throwaway container.
- Network egress (from the container) to `nexus.rumidata.io` (for `nvx-rumi:sandbox-<arch>:tar.gz`) and to PyPI (for the MCP wheel build backends).

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

## Skipping parts of a release

- `SKIP_REST=1` — patch-only release that leaves REST untouched.
- `SKIP_MCP=1` — REST-only release.
- `SKIP_BUNDLE=1` — republishing a per-service installer without touching the combined one (rare).

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
│   ├── <version>/nvx-rumi-appbuilder-rest-<version>-linux-arm-64.tar.gz
│   ├── <version>/nvx-rumi-appbuilder-rest-<version>-osx-x86-64.tar.gz
│   ├── <version>/nvx-rumi-appbuilder-rest-<version>-osx-arm-64.tar.gz
│   ├── latest/install.sh
│   └── latest/version.txt
└── appbuilder-mcp/
    ├── <version>/install.sh
    ├── <version>/rumi-appbuilder-mcp-<version>.tar.gz
    ├── latest/install.sh
    └── latest/version.txt
```

End users curl the `install.sh` they want and the script figures out the rest.
