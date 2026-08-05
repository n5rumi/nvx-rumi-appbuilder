# CLAUDE.md

This file provides guidance to Claude Code when working in this repository.

## Project Overview

The **Rumi App Builder** is the library + service + MCP surface for
scaffolding and incrementally building Rumi applications (Rumi is an N5
product). Three sibling modules at the repository root, each a thin
layer on the one beneath it:

| Module | Language | Role |
|---|---|---|
| `nvx-rumi-appbuilder-sdk/` | Java | The library. All scaffolder logic lives here — `ApplicationBuilder`, `ServiceBuilder`, `ConfigInjector`, etc. Consumed directly by `nvx-rumi-cli` (no runtime dependency on the REST service). |
| `nvx-rumi-appbuilder-rest/` | Java | A canonical Rumi REST service (Mgmt-Agent-style lifecycle, Datafye-API-REST-style resource classes) that wraps the SDK and exposes every scaffolder operation as a REST endpoint. Consumed by Sutra, the Rumi Support Agent, CI jobs, and anything else that wants app-building via HTTP. |
| `nvx-rumi-appbuilder-mcp/` | Python | A Model Context Protocol server that wraps the REST service. Each MCP tool is a typed call to one REST endpoint. Consumed by external coding assistants (Claude Code, Cursor, etc.) and optionally by agents that prefer the MCP interface for tool-call visibility. |

## Scope

**In scope — strictly app-building "do" operations.** Scaffold apps, add
and remove services, handlers, message types, state entities, config
fragments. Inspect what's there.

**Out of scope — explicitly.**
- Learning about Rumi (docs, API reference, schema lookup). Served by
  docs-on-disk + the GitBook-hosted Rumi Docs MCP server.
- Environment lifecycle, cloud provisioning, analytics tooling — all of
  which stay in the `rumi` CLI.
- Package / build orchestration — consumers run `mvn` directly.

## Consumer story

| Consumer | Path | Why |
|---|---|---|
| `rumi` CLI | SDK (direct Maven dependency) | CLI stands alone — no runtime requirement on the REST service. |
| Sutra / Rumi Support Agent | REST (direct) or MCP | Both are localhost-local on the sandbox. REST for simpler Python code; MCP for better tool-call visibility in the Claude Agent SDK UI. Agents typically use MCP. |
| External coding assistants (Claude Code, Cursor) | MCP | Native to these tools. |
| Scripts, CI, IDE plugins, anything non-MCP | REST | No MCP client machinery needed. |

## Tech Stack

- **SDK**: Java 11+ (builds with Java 17). Dependencies: ClassGraph, Gson.
- **REST service**: Java, canonical Rumi REST stack — Rumi-managed
  lifecycle (provision/deploy/configure/launch), HK2 DI, AepEngine
  accessible to resource classes for stateful ops or outbound publishing.
  Resource class structure modelled on `datafye-api-rest`.
- **MCP**: Python 3.11+, official MCP SDK. Hand-written or
  auto-generated from the REST service's OpenAPI spec (TBD).
  **`mcp` is pinned `>=1.10,<2`** in `nvx-rumi-appbuilder-mcp/pyproject.toml`:
  `mcp 2.0.0` removed `mcp.server.fastmcp` (the FastMCP entrypoint `server.py`
  imports) and `<=1.9.x` has an older `Tool.from_function` that crashes on
  union/`Optional` tool params. The prior unbounded `mcp>=1.4.0` let AMI bakes
  resolve `mcp 2.0.0`, crash-looping the Dev MCP on every baked agent box.
  `httpx`/`pydantic` are likewise capped (`<1`/`<3`).

## Project Structure

```
nvx-rumi-appbuilder/
├── pom.xml                         # Parent + aggregator for the two Java modules
├── CLAUDE.md / PROJECT.md / LICENSE
├── nvx-rumi-appbuilder-sdk/        # Java library (the original appbuilder)
│   ├── pom.xml
│   └── src/
├── nvx-rumi-appbuilder-rest/       # Java REST service (canonical Rumi stack)
│   ├── pom.xml
│   └── src/
└── nvx-rumi-appbuilder-mcp/        # Python MCP wrapper
    ├── pyproject.toml              # Not in the Maven reactor
    └── src/
```

The root `pom.xml` is both **aggregator** (its `<modules>` list is
`nvx-rumi-appbuilder-sdk` and, once it lands, `nvx-rumi-appbuilder-rest`)
and **parent** (the two Java modules inherit from it). The Python MCP
module has its own `pyproject.toml` and is not a Maven module.

## Build

```bash
# Build both Java modules (SDK and, when it lands, REST service)
mvn clean install

# Build only the SDK
mvn -pl nvx-rumi-appbuilder-sdk -am clean install

# Python MCP has its own build
cd nvx-rumi-appbuilder-mcp
pip install -e .
```

### Release / distribution

The full release is orchestrated by `ci/release.sh <downloads_root>` —
it builds the REST per-arch tarballs, the MCP wheel-in-tarball, and the
combined bundle, then publishes each by copying into the build agent's
local downloads tree (`${DOWNLOADS_ROOT}/rumi/appbuilder{,-rest,-mcp}/`)
and flipping a `latest` symlink. There is **no `aws s3 cp`** — it's the
same local-copy mechanism every other N5/Rumi/Datafye installer uses.

The release runs **inside a build-toolchain container** (`ci/Dockerfile`:
`python:3.11-bookworm` + OpenJDK 17 + Maven + the PEP 517 `build`
frontend), so a TeamCity agent needs only Docker. `ci/release-in-docker.sh`
is the local/manual convenience wrapper. `RELEASE_ARCHES` defaults to
**`linux-x86-64 osx-x86-64`** (x86 only — no arm build machine, and the
arm sandbox bases aren't published). See `ci/README.md` for the full
TeamCity build config and published-artifact layout.

Maven repositories (`<repositories>` and `<pluginRepositories>`) point at
`https://nexus.n5corp.com/repository/maven-public/`. This must be **https**:
the release builds inside a Maven 3.9 container whose default
http-blocker (Maven 3.8.1+) refuses plain-http repos. Parent of the App
Builder parent POM is `com.neeve:nvx-os-parent:1.1.5`.

## Conventions

- **Maven group**: `com.neeve` throughout.
- **Parent POM**: `com.neeve:nvx-os-parent:1.1.5` directly, not an
  inherited higher-level POM.
- **Sub-module naming**: `nvx-rumi-appbuilder-<role>` (e.g. `-sdk`,
  `-rest`, `-mcp`).
- **Language-mixed reactor**: the Python MCP sub-module has its own
  `pyproject.toml` and sits alongside the Maven children; it is not
  listed in the parent POM's `<modules>`.

## Key Design Decisions

- **Three modules, not one.** Each serves a different consumer shape.
  Bundling them would force the CLI to pull unused REST/MCP dependencies.
- **SDK is the source of truth.** REST and MCP are both thin layers on
  the SDK — no logic lives in them that isn't in the SDK.
- **CLI consumes the SDK directly.** No "CLI calls REST" pattern; the
  CLI must work offline and without a running service.
- **REST service is canonical Rumi stack.** Mgmt-Agent-style packaging
  and lifecycle, Datafye-API-REST-style resource classes. This gives us
  AMI-bake, systemd, auto-upgrade, HK2 DI, AepEngine access for free.
- **MCP wraps REST, not the SDK directly.** Keeps the Python code tiny
  (optionally auto-generated from OpenAPI) and lets any consumer choose
  REST-direct or MCP without feature asymmetry.
- **Runtime tool namespace `rumi-dev`.** The MCP server registers itself
  under that short prefix so tools appear as `mcp__rumi-dev__<tool_name>`.
  The directory name `nvx-rumi-appbuilder-mcp` is engineering convenience;
  the runtime namespace is for prompt readability.

## Relationship to Sister Projects

- **`nvx-rumi-cli`** — direct consumer of the SDK via Maven dependency
  (`nvx-rumi-appbuilder-sdk`).
- **Rumi Support Agent** (`nvx-rumi-agents/nvx-rumi-support-agent`) —
  consumes the MCP (or can go REST-direct if it wants). Agent installer
  fetches and installs both the REST service and the MCP server as
  sibling systemd units.
- **Sutra Agent** (`nvx-sutra/nvx-sutra-agent`, future) — same Dev MCP
  consumer, but framed as a primary build surface rather than a fallback
  tool.
- **Rumi Management Agent** (`nvx-rumi-management/rumi-agent`) —
  architectural template for the REST service's packaging, lifecycle, and
  deployment surface.
- **Datafye API REST service** (`github/datafye-platform/datafye-api/
  datafye-api-rest`) — architectural template for the REST resource
  class structure and HK2 wiring.
- **Rumi Docs MCP server** (GitBook-hosted, separate product) — the
  "learn about Rumi" complement. Not a dependency; called out here so
  the scope boundary is explicit.

## Branch Strategy

- `develop` — active development
- `main` — stable releases
- `1.0` — the current release line; 1.0.x releases are cut here
  (released `1.0.16` as of this writing). Release tags are
  `rumi-appbuilder-<version>`.
- **The three branches are kept in lockstep.** After pushing to `develop`,
  fast-forward `1.0` and `main` to it and push both.
- ⚠️ **The snapshot build tracks `1.0`, not `develop`.** The VCS root for
  `RumiGroup_Dev_..._10snapshot` resolves `build.appbuilder.branch = 1.0`,
  so a push to `develop` alone triggers **nothing** — no build, no error,
  silence. Always fast-forward `1.0` (and `main`) after pushing `develop`.
- `feature/connector-and-webservice-services` — **merged (fast-forward) into
  both `1.0` and `main`, deleted, and pushed.** Its work is now on `1.0` and
  `main`: webservice + generic `connector` service types (csvwriter renamed),
  connector snap-in, the in-process test harness + `/test-the-builder` skill,
  and the **complete model-editing epic** (slices 1–4). Model-editing operations
  shipped: field and api-operation editing; ROE-scoped message add/remove;
  embedded `<entity>` CRUD in ROE & service-message models; collections;
  entity-level attributes (`asEmbedded`); field-type normalization (canonical
  ADML scalar names); referential-safety-on-remove (blocks dangling
  field/collection/operation/handler references unless forced); and app-global
  factory-id never-reuse via the `.rumi-factory-ids` ledger. Ids are never
  reused — removals leave an `id=N reserved` tombstone (`ModelIdAllocator`).

## Milestone Version Bumps

When picking up a new Rumi milestone, **only `nvx.rumi.version` in the root
`pom.xml` changes.** It lives in the parent `<properties>` because it drives
two things now: the REST service's Rumi dependency, and the SDK's build-time
unpack of the `x-ddl`, `x-adml` and `x-asml` schemas (RUMI-377). The schemas
are no longer checked in, so they follow that one line automatically and
cannot drift.

Everything else in `nvx-rumi-appbuilder-rest/pom.xml`'s `<properties>` block
is effectively locked:

| Property | Locked at |
|---|---|
| `jetty.version` | `12.0.16` |
| `jersey.version` | `3.1.11` |
| `jackson.version` | `2.18.2` |
| `swagger.version` | `2.2.36` |
| `slf4j.version` | `2.0.16` |

⚠️ **`mvn versions:update-properties` pulls pre-release artifacts here.**
There is no version-range restriction configured (no `rulesUri`, no
`ignoredVersions`), so the plugin happily selects milestone and alpha
builds. On the 4.0.637 run it proposed jersey `4.0.0-M2` (a **milestone**)
and slf4j `2.1.0-alpha1` (an **alpha**), alongside jetty `12.1.11`,
jackson `2.22.1`, and swagger `2.2.52`. All were reverted.

**Review the full `git diff` before committing a version bump** — the bump
commit should touch exactly one line.

## Git Commits

Do not include `Co-Authored-By` trailers in commit messages.
