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

## Model validation (RUMI-376/377/378/379)

- **Schemas are not checked in.** `x-ddl.xsd`, `x-adml.xsd` and `x-asml.xsd` are
  unpacked at `generate-resources` from the `nvx-rumi-ddl` / `nvx-rumi-adm` /
  `nvx-rumi-client` artifacts matching `${nvx.rumi.version}`. Load them through
  `Schemas`; never add a copy back to `src/main/resources/schemas`.
- **Every model write goes through `ModelWriter.saveValidated`**, which
  validates first and writes only if valid, so a rejected edit leaves the file
  untouched. A new model editor must use it rather than calling
  `XmlDomUtils.saveXmlDocument` directly. Dry runs validate too.
- **Validation is two layers.** The schema does *not* catch the ADML rules —
  `field/@type` is `xs:string` and XSD does no cross-referencing, so
  `type="long"`, a non-`asEmbedded` entity used as a field type, and an
  undefined type all pass. `ModelValidator`'s semantic layer covers those, and
  is conservative: it stays silent when an `<import>` could not be read.
- **`ModelValidationException` extends `IllegalStateException`**, which is what
  gives it the REST layer's 422 mapping. Do not change that hierarchy.
- **A new mutating operation needs a test in `MutatingOperationValidityTest`.**
  `MutatingOperationCoverageTest` derives the operation set by scanning for
  public static `ChangeSet`-returning methods and fails the build otherwise.
- ⚠️ **ADML has no `key` attribute on a field — it is `isKey`.** The SDK passes
  `FieldDef` attributes through verbatim, so a wrong name reaches the model and
  fails at codegen.
- ⚠️ **Do not declare `maven-enforcer-plugin` in a module's `<plugins>`.** That
  activates `nvx-os-parent`'s release-only `enforce-no-snapshots` execution and
  fails every SNAPSHOT build.

## Sample-free scaffolds (RUMI-382)

- **Two modes from one template tree.** Templates mark their demo regions inline
  with `// @sample-begin` / `// @sample-end` (and `<!-- ... -->` in XML);
  `// @bare-begin` / `// @bare-end` is the mirror, for content that appears only
  in the sample-free mode. `SampleMarkers.resolve` drops whichever side the
  caller did not ask for. **Never add a parallel "bare" template tree** — the two
  would drift, which is the failure this design exists to prevent.
- **Markers are only honoured under `templates/<tool>/app/` and
  `templates/<tool>/service/`**, the two trees `TemplateProcessor.applyTemplate`
  materializes. `ConfigInjector`, `ScriptInjector` and `ConnectorEditor` render
  the other trees and know nothing about the mode; worse, `ServiceRemover`
  re-renders script snippets to work out what to delete, so a marker there would
  let a service be scaffolded in one mode and un-scaffolded as the other.
  `SampleMarkerBalanceTest` enforces this, along with marker balance.
- **Defaults differ per layer, deliberately.** SDK and REST default to
  samples-**on**, so the `rumi` CLI and existing REST callers are unchanged. The
  **MCP tools default to bare** — that is the agent-facing surface, and the whole
  point is that an agent should not have to delete demo code first.
- **The mode is a property of the app.** It is recorded in `.rumi` at creation
  and `ServiceParams` inherits it, so services added later stay consistent.
  ⚠️ `AppParams.includeSamples` is a nullable `Boolean`, not a primitive: Gson
  leaves an absent key as `false` on a primitive, which would silently flip every
  pre-existing app to bare. Null means "not recorded" and reads as true.
- **Bare keeps the wiring and the javadoc**, and drops only compilable sample
  artifacts. The webservice keeps a `/health` endpoint on purpose — a JAX-RS
  resource stripped to zero resource methods is not a shape worth shipping, and
  it gives `BareWebserviceTest` something to prove.
- `ci/verify-generated-app.sh` builds and runs **both** modes. `MODES="bare"`
  narrows it for local iteration; the release always runs both.
- ⚠️ **Values read out of Maven must be colour-proofed** (RUMI-384). `mvn -q
  -DforceStdout help:evaluate` appends ANSI codes when stdout is a TTY, which it
  is under TeamCity's Docker wrapper and is not when you pipe it locally. A
  contaminated `nvx.rumi.version` gets stamped into the generated app's POM and
  every dependency 404s at `4.0.637<ESC>[0m`. The capture disables colour, strips
  escapes, and validates the shape — keep all three if you touch it, and test
  with a TTY (`script -q /dev/null …`), not just a pipe.

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
| `jetty.version` | `12.0.36` |
| `jersey.version` | `3.1.11` |
| `jackson.version` | `2.18.9` |
| `swagger.version` | `2.2.36` |
| `slf4j.version` | `2.0.16` |

"Locked" means *a milestone bump does not touch these*, not *these never
move*. A deliberate security bump is a different thing and is expected: jetty
and jackson were bumped for RUMI-381 to clear 14 Dependabot alerts. Two rules
make that safe. Stay within the same minor line, so the bump cannot cross a
Jakarta baseline the way jersey `4.0.0-M2` would. And edit the properties by
hand — never reach for `versions:update-properties`, for the reason below.

⚠️ **The webservice service template has its own `jetty.version`**, in
`nvx-rumi-appbuilder-sdk/src/main/resources/templates/maven/service/webservice/sr/{{ServiceArtifactId}}/pom.xml`.
That one is the POM of *generated apps*, so it ships to users and needs
bumping too — it had drifted nine patch releases behind the REST service's own
pin before anyone noticed. `ci/verify-generated-app.sh` is what proves such a
bump has not broken generated apps: it boots a scaffolded webservice and
exercises the HTTP round trip.

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
