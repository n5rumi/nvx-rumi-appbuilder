# CI — App Builder Release Publishing

`ci/release.sh` orchestrates a full release: it builds dist tarballs for the REST service (per-arch), the MCP server (arch-agnostic), and the combined bundle installer, then uploads everything to `s3://downloads.n5corp.com/rumi/appbuilder{-rest,-mcp,}/...` via the per-service `publish_installer.sh` scripts.

## Required runner setup

- **Java 17+** on PATH (or via `JAVA_HOME`) for the REST build.
- **Python 3.11+** + `python -m pip install build` for the MCP build.
- **Maven** (matrix build across the four sandbox arches).
- **awscli v2** authenticated to a role with write access to `s3://downloads.n5corp.com`.
- Network egress to `nexus.rumidata.io` (for `nvx-rumi:sandbox-<arch>:tar.gz`) and to PyPI (for Python deps pulled at MCP wheel build).

## TeamCity build config

Recommended name: `RumiGroup_AppBuilder_10release` (matches the existing Rumi group's naming convention).

**Triggers:** manual with a required `VERSION` parameter (matches the release workflow for the CLI + Management Agent).

**Parameters:**

| Name            | Value                                   | Notes |
|-----------------|-----------------------------------------|-------|
| `VERSION`       | `%build.counter%` (or manual override)  | Release version. Written into every tarball + install.sh. |
| `S3_BUCKET`     | `s3://downloads.n5corp.com`             | Publish target. |
| `JAVA_HOME`     | `%env.JDK_17%`                          | Provided by the runner. |

**Steps:**

1. **Checkout** `nvx-rumi-appbuilder/develop` (or the release branch once 4.x branches land).
2. **Run** `bash ci/release.sh` with the env vars above. The script invokes `mvn` for the matrix REST build and runs each `publish_installer.sh` in turn.
3. **Tag** on success: `git tag v${VERSION} && git push origin v${VERSION}`.

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

```
s3://downloads.n5corp.com/rumi/
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
