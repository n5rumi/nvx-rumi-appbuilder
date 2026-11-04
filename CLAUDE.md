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
| `nvx-rumi-appbuilder-mcp/` | Python | A Model Context Protocol server that wraps the REST service. Each MCP tool is a typed call to one REST endpoint, with a single deliberate exception: `tool_usage_report` (RUMI-415) reports on tool adoption from counters held in this process, because what is worth counting is which *tool* was reached for and REST sees only endpoints. It is named in `LOCAL_ONLY_TOOLS` in the coverage tests; a second such tool needs the same justification. Consumed by external coding assistants (Claude Code, Cursor, etc.) and optionally by agents that prefer the MCP interface for tool-call visibility. |

## Scope

**In scope — strictly app-building "do" operations.** Scaffold apps, add
and remove services, handlers, message types, state entities, config
fragments. Inspect what's there. **Compile what's there and return a verdict**
(RUMI-416) — see the process rule below.

**Out of scope — explicitly.**
- Learning about Rumi (docs, API reference, schema lookup). Served by
  docs-on-disk + the GitBook-hosted Rumi Docs MCP server.
- Environment lifecycle, cloud provisioning, analytics tooling — all of
  which stay in the `rumi` CLI.
- **Owning a long-lived process.** Running an app, holding a port, tailing
  logs, writing keep-warm markers, restarting a preview: all of it belongs to
  the consumer's own harness, never here.

### ⚠️ The process rule, and why it moved (RUMI-416)

This list used to read "**Package / build orchestration — consumers run `mvn`
directly**", which ruled out a build call outright. That line was replaced
deliberately on 2026-10-28, because it drew the boundary in the wrong place: it
separated *build* from *not-build*, when the distinction that actually matters
is **transient versus long-lived**.

- A **transient** process — one this service starts, waits for, and reaps within
  the call — is in scope. A compile is the example.
- A **long-lived** one is not, and that is the hard line. The moment something
  here owns a port, a log file or a liveness marker, two components believe they
  own the same running app. That is not hypothetical: it is the split DAT-221 had
  to untangle on the Datafye side, and the warm-signal ownership SUT-74 had to
  repair on Sutra's.

The argument for moving it is **attribution**, not convenience. `javac` reports
`OrderProcessor.java:84`; only this service knows that line came from a field
added to the `Order` state entity, because it wrote the file. That mapping exists
nowhere else in the stack, so without it every caller reverse-engineers it from a
log, every time. "Wrap `mvn` and hand back its output" was never worth the
boundary change and still is not — scaffolding a `run-in-process.sh` into the
generated app (RUMI-426) is the right answer whenever the caller just needs to
run something, and it keeps execution on the caller's side of the line.

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
  public static methods returning `ChangeSet` **or `BatchResult`**, and fails the
  build otherwise. ⚠️ The scan keys on the *return type*, so an operation that
  returns something else is invisible to it — `ModelBatch.apply` (RUMI-412) was,
  until `BatchResult` was added to the scan. A new result type needs adding there
  or the guard silently stops covering the thing it exists to cover.
- **A new REST resource must be registered in `Main.ResourceConfig`.** Registration
  is explicit rather than a package scan, so a resource class that is written but
  not registered serves 404 with no error anywhere. `ResourceRegistrationTest`
  compares the `@Path` classes on disk against what the config registers.
- **A new MCP tool needs adding to `EXPECTED_TOOLS`**, and a new *verb* prefix needs
  adding to `test_mutating_tools_use_a_mutating_http_method`. Both lists are
  hand-maintained and both have gone stale: `update_` (RUMI-411) and `apply_`
  (RUMI-412) each arrived with the guard blind to them.
- ⚠️ **ADML has no `key` attribute on a field — it is `isKey`.** The SDK passes
  `FieldDef` attributes through verbatim, so a wrong name reaches the model and
  fails at codegen.
- **Reserved field names live in `ReservedNames` and are PINNED to the milestone**
  (RUMI-387). A generated type extends `RogNode` → `MessageViewImpl`, which
  between them declare ~70 `final public` no-arg getters; a field named
  `message`, `messageKey`, `createTs`, `parent` or `metadata` generates a getter
  that cannot override one. **Re-derive the list on a milestone bump** — the
  grep is in the class javadoc. Only **no-arg** getters count: `getTag(int)` and
  `setTag(int, Object)` take arguments, so a field named `tag` produces legal
  overloads and is NOT reserved, despite folklore to the contrary.
- **An api.xml operation is validated against the model the api.xml NAMES**
  (RUMI-393), read from its `<messages modelFile>` — never assumed to be ROE,
  since the file is the user's and can point anywhere. It resolves that path
  against *every* module's `src/main/models`, because the shared ROE model lives
  in a different Maven module (at build time the generator finds it on the
  classpath, where module boundaries are already flattened). Imports are
  deliberately NOT followed: `AsmModel.resolveMessage` does a local lookup, so
  a validator that accepted more than the generator resolves would let the bad
  edit through. **Mirror the generator** — broader lets a bad edit through,
  narrower rejects a legitimate one.
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
- ⚠️ **Generated `Main.java` imports single types, not packages** (RUMI-389), so
  a sample-only type's import sits inside the `@sample-begin` region with the
  code that uses it. The wildcard imports it replaced made a name defined in two
  models ambiguous. The load-bearing consequence: `JavaSourceEditor.addHandler`
  now has to **add the import for the message it handles**, resolving the
  namespace from the model that declares it (service model, then ROE) — the
  wildcards used to cover that for free. A type no model declares gets no
  import rather than a guessed one.
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
- ⚠️ **The same goes for any grep over Maven's output** (RUMI-464). The RUMI-457
  check that `RestEndpointTest` ran read a coloured log, where the result line is
  `Tests run: <ESC>[0;1;32m1<ESC>[m, …` and the class name is wrapped in bold, so a
  **passing** test failed the gate and blocked the 1.0.26 release. It runs only in
  `release.sh`, never in the snapshot build, so nothing caught it before a release.
  The script now exports `MAVEN_ARGS="-B -Dstyle.color=never"` once, which covers
  every `mvn` it starts, including the generated `run-in-process.sh` (a per-call
  flag is what RUMI-457 forgot after RUMI-384). Every reader of Maven output also
  goes through the one `strip_ansi` helper; a new grep over a Maven log must too,
  and one that passes on *no* match (the Jersey check) needs it most, since an
  escape inside the text it looks for makes it pass silently. Two traps when you check this:
  TeamCity's *downloadable* build log replaces ESC with a space, so it is not the
  bytes the gate saw; and under `set -o pipefail`, `sed … | grep -q` can fail on a
  match (grep exits early, sed takes SIGPIPE), so feed grep by process substitution.

## REST endpoints and service clients (RUMI-457)

Two separate operations, deliberately:

- **`add_rest_endpoint` / `update_rest_endpoint`** (`RestEndpointEditor`) take the endpoint's **body as
  Java code**, the way `add_handler` does: the editor writes the JAX-RS method around it (path params as
  `@PathParam String`, `UriInfo uri`, `String body` for POST/PUT/PATCH, returns `Response`) and the caller
  decides what it does. `get_rest_endpoint` hands the body back verbatim.
- **`add_service_client`** (`ServiceClientEditor`) is the **explicit prerequisite** for a body that does
  request-reply with ANOTHER service: client class, Maven dependency, client bus and client config. Calling
  the webservice's own engine (`injectRequestAndWaitForReply`) needs no client, and a one-way message needs
  only the shared model. A body naming `<Service>Client` before its client exists is refused, naming the
  step: client names are predictable, so it is a text check, and RUMI-416's compile catches the rest.

The quickstart (RUMI-459) uses both: a webservice starts with `/health` only, and a **sample**
processor carries an `echo` api.xml operation whose `<Service>EchoRequest/Response` live in the
**shared ROE model**. Because that model is shared, `ServiceBuilder` refuses a sample processor whose
echo names are already taken (adding a message is a noop on an existing name), and `ServiceRemover`
removes the two messages only while no file outside the processor's module uses them.

`list_service_clients` takes `webservice`, `service`, or both. `service` alone is the reverse lookup
(`listClientsOf`, `GET /v1/service-clients?service=`): who calls it. Its own root resource,
`ServiceCallers`, because the webservices are what it finds, not what it is given. With neither, the
REST service refuses it, not the MCP layer: `test_tool_coverage` requires every tool to reach REST.

Rules that fail silently if broken:

- ⚠️ **The client is keyed with the target's ENGINE name, `<service>-<partition>` (`demo-orders-1`),
  read from the `<app template="...-template">` instance in config.xml** (`ServiceClientEditor.engineName`).
  The client sends on the `requests` channel keyed with the name it is given; the engine subscribes
  with its own. The bare service name times out with **nothing logged on either side**. This was a
  customer's failure, reproduced on a generated app (RUMI-458). The client bus is
  `<engine>_client_bus` for the same reason.
- **A partitioned target is refused**, not called on partition 1: one client calls one engine.
- **A client bus the developer already declared is used AS DECLARED** (verified on a hand-wired
  customer app): its own descriptor expression and, if the bus pins a `requests` `<key>`, a
  `requestsChannelKey` of that key. Such a bus is never removed.
- **What removal takes is only what the editor added**: the dependency carries a marker comment, the
  client config sits under its own client name `<web>-rest`, and the generated descriptor variable is
  `REST_CLIENT_BUS_DESCRIPTOR`, so a developer's own `CLIENT_BUS_DESCRIPTOR` is never taken for ours.
- **A client in use is not removed** unless forced, and `ServiceRemover` refuses to remove a service
  some webservice holds a client for.
- **Generated support (`RestJson`) and client classes live in `<web>.rest`**, and a file there without
  the `// @rest-endpoint-client` marker is refused rather than overwritten.
- ⚠️ **Snippets must compile at Java 8.** Older scaffolds pin `<release>8</release>`;
  `String.isBlank()` in a snippet broke a real app's build. Unit tests never compile the snippets.
- **The client bus gets its own descriptor.** On loopback an engine may bind a namespace once, so the
  test profile uses `loopback://<app>-client` (else `<engine> already connected` at startup). Declared
  in `<buses>` only: the engine joins it by itself, so it is NOT added to `<messaging>`.
- **One resource class per path**, with the path at class level. JAX-RS picks a root resource by its
  longest literal prefix and does not backtrack, so a shared `@Path("/")` class would be shadowed by
  `WebMain`'s `/<svc>/v1`. Equivalent routes (`/orders/{id}` vs `/orders/{orderId}`) are refused.
- Endpoints are found by the `// @rest-endpoint` marker comments, so hand-written resources are never
  listed or removed.
- ⚠️ **In tests, a service restarted in the same JVM on loopback stops receiving client requests**
  (RUMI-460, cause unproven). The release gate runs `RestEndpointTest` in a JVM of its own for that
  reason; do not fold it back into the shared `mvn test`.

## ⚠️ Two release gates, neither with a skip flag

`ci/release.sh` runs both before publishing anything.

- `ci/verify-generated-app.sh` — proves the **templates** produce apps that build and run.
- `ci/verify-mcp-end-to-end.sh` — proves the **three modules agree with each other**. Every
  MCP test mocks the HTTP layer, so the suite asserts what the MCP *would have sent* and never
  that the service accepts it; the REST tests drive resources directly. Nothing put the two
  together until this existed, which is how RUMI-412 shipped a `scope` that was accepted and
  then ignored while 365 unit tests passed. It boots the REST layer on an ephemeral port,
  drives the real MCP tools, then builds a model entirely through `apply_model` and compiles
  the generated app.

Neither carries a `SKIP_` flag, deliberately: skipping either lets through exactly the defect
it exists to catch. The MCP gate is proven to gate — reintroducing the RUMI-412 scope bug
makes it fail.

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

`mvn -Pneeve versions:update-properties` makes that edit correctly — the root
POM configures the plugin so it updates this property and nothing else. Confirm
the line actually moved before committing; see the caveats below for why.

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
hand — `versions:update-properties` is now configured so it *cannot* reach
them, for the reason below.

⚠️ **The webservice service template has its own `jetty.version`**, in
`nvx-rumi-appbuilder-sdk/src/main/resources/templates/maven/service/webservice/sr/{{ServiceArtifactId}}/pom.xml`.
That one is the POM of *generated apps*, so it ships to users and needs
bumping too — it had drifted nine patch releases behind the REST service's own
pin before anyone noticed. `ci/verify-generated-app.sh` is what proves such a
bump has not broken generated apps: it boots a scaffolded webservice and
exercises the HTTP round trip.

### Why the versions plugin is configured the way it is

The root POM's `pluginManagement` constrains `versions-maven-plugin` to a
single property. Both halves of that configuration are load-bearing.

⚠️ **Auto-linking cannot find `nvx.rumi.version` on its own.** The property is
declared in the root POM, but the dependency that uses it
(`com.neeve:nvx-rumi`) is declared in `nvx-rumi-appbuilder-rest`. The plugin
associates properties with dependencies *within a project*, so at the
aggregator it saw a property with nothing to link and skipped it — silently,
with `BUILD SUCCESS` and no mention of the property at all. The failure mode is
nasty precisely because it is quiet: on the 4.0.640 milestone the bump appeared
to succeed while leaving App Builder pinned to 4.0.637, and only a manual check
of the diff caught it. The explicit `<properties>` association is that missing
link. **A silent no-op looks exactly like a no-op that was correct** — after a
milestone bump, confirm the property actually moved rather than trusting the
exit code.

⚠️ **`versions-maven-plugin` is pinned at 2.1 by `nvx-os-parent`**, which
predates `ignoredVersions`, so it cannot be told to skip pre-releases and will
happily select milestone and alpha artifacts. On the 4.0.637 run it proposed
jersey `4.0.0-M2` (a **milestone**) and slf4j `2.1.0-alpha1` (an **alpha**),
alongside jetty `12.1.11`, jackson `2.22.1`, and swagger `2.2.52`; on 4.0.640 it
proposed the same again. `<includeProperties>nvx.rumi.version</includeProperties>`
is the fix available at 2.1 — rather than filtering out bad *versions*, it
narrows the plugin to the one *property* a milestone is allowed to move. The
locked pins are now unreachable by the goal, so they can no longer be swept into
a bump commit by accident.

The trade-off: `versions:display-property-updates` honours the same parameter,
so it will no longer report available jetty/jackson/etc. updates. Dependabot is
the mechanism for that visibility, and it is what drove RUMI-381.

**Review the full `git diff` before committing a version bump** — the bump
commit should touch exactly one line.

## Git Commits

Do not include `Co-Authored-By` trailers in commit messages.
