# Rumi App Builder

## What Is This?

Three sibling modules under this directory that collectively cover every
path into Rumi application scaffolding and modification:

- **`nvx-rumi-appbuilder-sdk`** — the Java library where all the
  scaffolder logic lives. `ApplicationBuilder`, `ServiceBuilder`,
  `ConfigInjector`, `ScriptInjector`, `TemplateProcessor`,
  `FactoryIdCollector`, `TokenUtils`, plus the templates for Rumi apps
  and the three service types (driver, processor, csvwriter).
- **`nvx-rumi-appbuilder-rest`** — a canonical Rumi REST service that
  wraps the SDK and exposes every operation as an HTTP endpoint. Runs
  as a long-lived process on the sandbox. Zero logic of its own — every
  endpoint maps 1:1 to an SDK call.
- **`nvx-rumi-appbuilder-mcp`** — a Python MCP server that wraps the
  REST service. Every MCP tool maps 1:1 to a REST endpoint. Runs
  alongside the REST service as a sibling process.

The SDK exists today (it was `nvx-rumi-appbuilder` before the rename and
restructure). The REST service and MCP wrapper are both new work,
tracked under RUMI-282.

## Why Three Modules?

Because there are three different consumer shapes, and each wants a
different interface:

- The **`rumi` CLI** pulls the SDK as a Maven dependency. It must work
  offline, with no running service, no network, nothing but a JVM. SDK
  as a library is the only sensible path.
- **Python-native callers** — Sutra, the Rumi Support Agent, various
  scripts and CI jobs — want an HTTP interface. Giving them a REST
  service is one `httpx.post` away. Making them shell out to `rumi` for
  every operation would cost a JVM startup per call and still couldn't
  reach operations the CLI doesn't expose.
- **MCP-native callers** — Claude Code, Cursor, the Rumi Agent when it
  wants tool-call visibility through the Claude Agent SDK — want MCP
  tools. The Python MCP server gives them that shape, and by wrapping
  the REST service rather than re-embedding the SDK, it keeps itself
  tiny (optionally auto-generated from OpenAPI).

Bundling these would force the CLI to pull Python dependencies, or the
MCP to pull a JVM, or both. Splitting keeps each consumer's deploy
footprint minimal.

## How It Fits Together

```
                ┌───────── nvx-rumi-appbuilder-sdk ─────────┐
                │  ApplicationBuilder, ServiceBuilder,       │
                │  ConfigInjector, ScriptInjector,           │
                │  TemplateProcessor, FactoryIdCollector,    │
                │  TokenUtils, + templates                   │
                └───────────────┬────────────────────────────┘
                                │
            ┌───────────────────┴───────────────────┐
            │                                       │
            ▼                                       ▼
     nvx-rumi-cli                    nvx-rumi-appbuilder-rest
     (Maven dep, direct)             (canonical Rumi REST stack,
                                      thin wrapper over SDK)
                                                  │
                                                  ▼
                                 nvx-rumi-appbuilder-mcp
                                 (Python, MCP wrapper, thin
                                  wrapper over REST)
                                                  │
                                                  ▼
                               MCP clients: Rumi Agent, Sutra,
                               Claude Code, Cursor, etc.
```

Every scaffolder operation exists in exactly one place — the SDK. The
REST service is configuration (endpoint → SDK method). The MCP server
is configuration (tool → REST endpoint). No logic forks.

## Operation Catalog

The same catalog of operations is exposed at all three levels, named
consistently. Each row is: SDK method (or gap, if new), REST endpoint
path, MCP tool name. Every mutation takes an optional `dry_run: bool`
and returns a structured change set.

### App operations

| SDK | REST | MCP tool |
|---|---|---|
| `ApplicationBuilder.createApplication` | `POST /v1/apps` | `app_create` |
| `ApplicationBuilder.AppParams.read` | `GET /v1/apps/{app_root}` | `app_get` |
| `AppIntrospector.listRumiApps` (new, A2) | `GET /v1/apps?under={parent_dir}` | `app_list` |

### Service operations

| SDK | REST | MCP tool |
|---|---|---|
| `ServiceIntrospector.listServices` (new, B3) | `GET /v1/apps/{app_root}/services` | `service_list` |
| `ServiceIntrospector.getService` (new, B3) | `GET /v1/apps/{app_root}/services/{name}` | `service_get` |
| `ServiceBuilder.createService` (processor) | `POST /v1/apps/{app_root}/services/processor` | `service_add_processor` |
| `ServiceBuilder.createService` (driver) | `POST /v1/apps/{app_root}/services/driver` | `service_add_driver` |
| `ServiceBuilder.createService` (csvwriter) | `POST /v1/apps/{app_root}/services/csvwriter` | `service_add_csvwriter` |
| `ServiceRemover.removeService` (new, D5) | `DELETE /v1/apps/{app_root}/services/{name}` | `service_remove` |

### Message handler operations *(new — not in CLI today)*

| SDK | REST | MCP tool |
|---|---|---|
| `HandlerIntrospector.listHandlers` (new, C2) | `GET /v1/apps/{app_root}/services/{s}/handlers` | `handler_list` |
| `HandlerIntrospector.getHandler` (new, C2) | `GET /v1/apps/{app_root}/services/{s}/handlers/{m}` | `handler_get` |
| `JavaSourceEditor.addHandler` (new, C3) | `POST /v1/apps/{app_root}/services/{s}/handlers` | `handler_add` |
| `JavaSourceEditor.removeHandler` (new, C3) | `DELETE /v1/apps/{app_root}/services/{s}/handlers/{m}` | `handler_remove` |

### Message type operations *(new — not in CLI today)*

| SDK | REST | MCP tool |
|---|---|---|
| `MessageIntrospector.listMessages` (new, B1) | `GET /v1/apps/{app_root}/services/{s}/messages` | `message_list` |
| `MessageIntrospector.getMessage` (new, B1) | `GET /v1/apps/{app_root}/services/{s}/messages/{m}` | `message_get` |
| `MessageEditor.addMessage` (new, D1) | `POST /v1/apps/{app_root}/services/{s}/messages` | `message_add` |
| `MessageEditor.removeMessage` (new, D1) | `DELETE /v1/apps/{app_root}/services/{s}/messages/{m}` | `message_remove` |

### State entity operations *(new — not in CLI today)*

| SDK | REST | MCP tool |
|---|---|---|
| `StateIntrospector.listStateEntities` (new, B2) | `GET /v1/apps/{app_root}/services/{s}/state-entities` | `state_entity_list` |
| `StateIntrospector.getStateEntity` (new, B2) | `GET /v1/apps/{app_root}/services/{s}/state-entities/{e}` | `state_entity_get` |
| `StateEditor.addStateEntity` (new, D2) | `POST /v1/apps/{app_root}/services/{s}/state-entities` | `state_entity_add` |
| `StateEditor.removeStateEntity` (new, D2) | `DELETE /v1/apps/{app_root}/services/{s}/state-entities/{e}` | `state_entity_remove` |

### Config fragment operations *(new — not in CLI today)*

| SDK | REST | MCP tool |
|---|---|---|
| `ConfigIntrospector.listFragments` (new, B4) | `GET /v1/apps/{app_root}/config/fragments` | `config_list` |
| `ConfigIntrospector.getConfig` (new, B4) | `GET /v1/apps/{app_root}/config` | `config_get` |
| `ConfigFragmentEditor.addFragment` (new, D3) | `POST /v1/apps/{app_root}/config/fragments` | `config_fragment_add` |
| `ConfigFragmentEditor.removeFragment` (new, D3) | `DELETE /v1/apps/{app_root}/config/fragments` | `config_fragment_remove` |
| `ConfigValidator.validate` (new, D4) | `POST /v1/apps/{app_root}/config/validate` | `config_validate` |

### Factory ID operations

| SDK | REST | MCP tool |
|---|---|---|
| `FactoryIdCollector.listUsedIds` (extend, A3) | `GET /v1/apps/{app_root}/factory-ids` | `factory_id_list` |
| `FactoryIdCollector.nextAvailableId` (extend, A3) | `GET /v1/apps/{app_root}/factory-ids/next?kind={k}` | `factory_id_next_available` |

## Mutation Semantics

All three layers enforce the same semantics, because the SDK is the
single source of truth.

- **Idempotent adds.** If a handler/message/entity with the same identity
  already exists, the add is a noop (returns "already present" rather
  than failing). This rides on the existing
  `ConfigInjector`/`ScriptInjector` dedup behaviour, which extends
  naturally to the new editors.
- **Identity-matched removes.** Removes take the same logical identity
  the add uses, find the artifact, remove it. Noop if absent. Removing
  a service also reverts every downstream mutation — parent-POM
  reference, config fragments, script fragments, factory-ID
  reclamation.
- **Dry-run on every mutation.** Every mutation takes an optional
  `dry_run: bool`. When true, the SDK computes the change set without
  touching disk and returns it. The REST layer passes it through; the
  MCP layer passes it through. Agents are instructed in their system
  prompts to prefer dry-runs before destructive operations.
- **Structured change-set return.** Every mutation returns:
  ```
  {
    "applied": bool,         // false if dry_run
    "files_created": [...],
    "files_modified": [{"path": ..., "diff": ...}],
    "files_deleted": [...],
    "factory_ids_reserved": [...],
    "factory_ids_released": [...],
    "noop": bool,
    "reason": "..."          // optional explanation
  }
  ```

## Upstream SDK Gap-Fill (Phases A–E)

`nvx-rumi-appbuilder-sdk` today is write-only and forward-building: it
scaffolds new apps/services and injects config into them, but has no
read/introspection APIs, no Java-AST editing, no removal logic, and no
reusable XML DOM primitives. About 65% of the 28 operations above need
SDK-level work before REST or MCP can wrap them.

Five phases with dependencies between them. Phase A unblocks everything
else; Phases B and C can run in parallel; Phase D builds on A/B/C;
Phase E runs alongside B through D.

**Phase A — Foundation primitives** (RUMI-284, 285, 286)

A1. `XmlDomUtils` — extract DOM primitives from `ConfigInjector`'s
    currently-private helpers and expose them publicly.
A2. `AppIntrospector` — path/metadata resolver (list apps, resolve
    service module dir, messages.xml, state.xml, Main.java, service
    type).
A3. Extend `FactoryIdCollector` — reverse lookup (`listUsedIds`), public
    `nextAvailableId`, and `release`.

**Phase B — Read-side introspection** (RUMI-287, 288, 289, 290)

B1. `MessageIntrospector` — parse `messages.xml`, return message defs.
B2. `StateIntrospector` — symmetric over `state.xml`.
B3. `ServiceIntrospector` — list and get services; roll up handlers
    (after C2), messages (via B1), state entities (via B2).
B4. `ConfigIntrospector` — enumerate config fragments grouped by scope.

**Phase C — Java AST editing** (RUMI-291, 292, 293)

C1. Add JavaParser dependency.
C2. `HandlerIntrospector` — parse `Main.java`, extract `@EventHandler`
    methods.
C3. `JavaSourceEditor` — add and remove `@EventHandler` methods,
    preserving formatting.

**Phase D — Write / remove** (RUMI-294 through 298)

D1. `MessageEditor` — add and remove in `messages.xml` with factory-ID
    management.
D2. `StateEditor` — symmetric over `state.xml`.
D3. `ConfigFragmentEditor` — inject and remove X-DDL fragments in
    `config.xml`.
D4. `ConfigValidator` — X-DDL schema validation.
D5. `ServiceRemover` — orchestrate the inverse of
    `ServiceBuilder.createService`.

**Phase E — Test fixtures** (RUMI-299)

E1. `TestAppFactory` + assertion helpers. Appbuilder has no tests today;
    this unlocks unit and integration testing across the SDK and every
    consumer that builds on it.

See the individual JIRA tickets under epic **RUMI-282** for method-level
detail.

## Downstream Track 2 (REST service + MCP wrapper)

Tracked under RUMI-282 with additional tickets to be filed. High-level:

1. REST service scaffold — canonical Rumi REST stack, Mgmt-Agent-style
   packaging and lifecycle, Datafye-API-REST-style resource classes.
   See `nvx-rumi-appbuilder-rest/PROJECT.md` for the design in detail.
2. Python MCP scaffold — Python project, MCP SDK, tool-per-REST-endpoint
   mapping. See `nvx-rumi-appbuilder-mcp/PROJECT.md` for design.
3. Packaging — each service publishes its own self-contained installer
   (`appbuilder-rest`, `appbuilder-mcp`) plus a combined-bundle installer
   (`appbuilder`) that fetches and installs both. REST ships as a native
   per-arch tarball driven by `bin/xvm.sh` (not a JAR-only drop); MCP
   ships as a wheel-in-tarball with its own venv.
4. CI publish — `ci/release.sh <downloads_root>` builds all three and
   copies them into the build agent's local downloads tree under
   `rumi/appbuilder{,-rest,-mcp}/<version>/`, flipping a `latest`
   symlink (no `aws s3 cp`). The release runs inside a build-toolchain
   container (`ci/Dockerfile`), so a TeamCity agent needs only Docker.
   See `ci/README.md`.

## Consumers

| Consumer | Goes through | Notes |
|---|---|---|
| `rumi` CLI | SDK directly | Maven dep. No runtime requirement on REST/MCP. |
| Rumi Support Agent | MCP (preferred) | Tool-call visibility via Claude Agent SDK. REST available as escape hatch. |
| Sutra | MCP (preferred) | Same reasoning as Support Agent. |
| External coding assistants | MCP | Native shape for them. |
| CI jobs, scripts, IDE plugins | REST | No MCP machinery needed; `curl` or equivalent. |

## Open Work Summary

- RUMI-282 epic, RUMI-284..299 tickets — SDK gap-fill (Phases A–E).
- New tickets (to file): REST service scaffold, Python MCP scaffold,
  packaging bundle, CI publish job. All under the same epic.
- Rumi Agent installer update — step 10 fetches the bundle and installs
  both sibling systemd services.
- Rumi Agent prompt update — reference the concrete MCP tool names
  (`mcp__rumi-dev__service_list`, etc.) and prefer-MCP rules.

## Lessons Learned (SDK)

SDK-internals lessons worth keeping close to the code that embodies
them.

### The Files.walk Ordering Bug (April 2025)

**The symptom.** Generated `config.xml` files would sometimes have
individual XVM or app instance declarations appear *before* the
`<templates>` section in a profile. Rumi's config parser expects
`<templates>` to come first — if it doesn't, template references from
those instances can't resolve, and the app fails to start. The
maddening part: it worked on some machines and not others, and even on
the same machine it would sometimes pass and sometimes fail.

**The root cause.** `ConfigInjector` processes config fragment files
discovered via `java.nio.file.Files.walk()`. The Java documentation
buries an important detail: `Files.walk` returns entries in **no
guaranteed order**. The order depends on the filesystem implementation,
OS, and even the phase of the moon (okay, not literally, but it might
as well). So when config fragments for both template definitions and
instance declarations existed, the instance declaration could be
processed first, causing its XML element to be `appendChild`-ed before
the `<templates>` element was created.

**The fix.** In `getOrCreateChild`, when creating a `<templates>`
element, instead of blindly calling `parent.appendChild(child)`, we
scan the parent's existing children. If any non-template element
siblings already exist, we use `parent.insertBefore(child, refNode)`
to place `<templates>` ahead of them. This guarantees correct ordering
regardless of traversal order.

**The takeaway.** Never assume filesystem traversal order. `Files.walk`,
`File.listFiles`, and `Path.list` are all non-deterministic. If your
logic depends on processing order, either sort the results explicitly
or make your insertion logic order-independent. This class of bug is
especially nasty because it's a *heisenbug* — passes on one machine,
fails in CI, driven by filesystem implementation details you have no
visibility into.

### Factory ID Collisions

Factory IDs in Rumi must be unique across the entire system (0–32767
range). `FactoryIdCollector` solves this by scanning all existing model
files before assigning new IDs, and filling gaps in the sequence rather
than always incrementing. Whenever you have a global numeric namespace,
scan-then-assign beats increment-and-hope.

### Idempotent Code Generation

Both `ConfigInjector` and `ScriptInjector` detect duplicates before
inserting. Running the builder twice with the same parameters won't
corrupt the output. Idempotency is a design choice worth making early
— it's much harder to retrofit than to build in from the start.

## Lessons Learned (REST distribution & runtime)

Lessons from getting the REST service to build, publish, and stop
cleanly across machines and a containerized release.

### Kill the wrapper, orphan the JVM

The first stop logic killed the launcher pid. But `bin/xvm.sh` is a
wrapper that *forks* the actual JVM, so killing the wrapper left the
JVM running and holding the port — the next start would fail. The fix:
stop via `xvm.sh appbuilder-rest --action stop`, which has
`com.neeve.server.Main` discover the running XVM through its discovery
descriptor, connect to its admin port, and tell the XVM to shut its own
JVM down gracefully. The takeaway: when a process supervises a forked
child, never reach for the parent's pid — ask the child to stop itself
through whatever control channel it exposes. `scripts/start.sh` and
`scripts/stop.sh` are now the single source of truth, and the installer
delegates to them. (Note: the `scripts/launch` / `scripts/shutdown`
XVM-DSL files are a *different* path — they drive the controller/xar
Rumi Management deployment, not the direct `xvm.sh` launch.)

### Discovery must be deterministic, and `loopback://` isn't it

Stop-by-discovery only works if the stopper finds the same XVM the
launcher started. Left unset, Rumi's discovery defaults to multicast on
an auto-selected NIC — non-deterministic across machines and flaky in
CI. The obvious-looking fix, `loopback://`, is wrong: that's an
*intra-JVM* bus and can't span the separate REST, MCP, and admin-client
JVMs. The right answer is multicast *bound to loopback*:
`mcast://224.0.1.200:4090&localIfAddr=127.0.0.1` in the `standalone`
profile, plus `-Djava.net.preferIPv4Stack=true` in the wrapper JVM
params. **Caveat worth remembering:** loopback-bound multicast needs the
`lo` interface to carry the MULTICAST flag. macOS `lo0` does by default;
Amazon Linux 2023 `lo` usually does **not** — fix with
`ip link set lo multicast on`.

### macOS is always x86-64 (for now)

There's no arm macOS build of the sandbox bases yet, and Apple Silicon
runs the x86-64 build fine under Rosetta. So `detect_arch()` in the REST
installer forces `cpu=x86-64` whenever the OS is macOS, and
`RELEASE_ARCHES` defaults to the x86 pair (`linux-x86-64 osx-x86-64`).
The lesson: don't publish arches you can't actually build — the arm
sandbox bases (`nvx-rumi:sandbox-{linux,osx}-arm-64`) aren't published,
so claiming arm support would just hand users a broken install.

### Containerize the release toolchain, not the product

The build agents are Amazon Linux 2 (Python 3.7, old OpenSSL, no Java
17 / matching Maven). Rather than fight the host, the release runs
inside `ci/Dockerfile` (`python:3.11-bookworm` + OpenJDK 17 + Maven +
PEP 517 `build`); the agent needs only Docker. Two gotchas this surfaced:
(1) Debian's Maven 3.9 carries the maven-default-http-blocker, so the
pom's `repositories` *and* a freshly-added `pluginRepositories` had to
move to **https** `nexus.n5corp.com` (Maven 3.6.3 was pinned in earlier
iterations for the same reason). (2) Each per-arch `mvn clean` wipes the
module `target/`, so the per-arch REST tarballs are staged in
`${REPO_DIR}/.release-dist` *outside* `target/` — otherwise the next
arch's clean would delete the previous arch's tarball. This is also why
build hosts need `python3.11` explicitly: AL2023/RHEL9 ship
`python3` = 3.9, below the MCP's `>=3.11` floor, so the MCP build and
installer probe for `python3.11` first.
