# Rumi App Builder

## What Is This?

Three sibling modules under this directory that collectively cover every
path into Rumi application scaffolding and modification:

- **`nvx-rumi-appbuilder-sdk`** — the Java library where all the
  scaffolder logic lives. `ApplicationBuilder`, `ServiceBuilder`,
  `ConfigInjector`, `ScriptInjector`, `TemplateProcessor`,
  `FactoryIdCollector`, `TokenUtils`, plus the templates for Rumi apps
  and the service types (driver, processor, connector, webservice), and
  `ConnectorEditor`/`ConnectorIntrospector` for snapping connectors in.
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
| `ServiceBuilder.createService` (processor) | `POST /v1/services` (type=processor) | `add_service` (type=processor) |
| `ServiceBuilder.createService` (driver) | `POST /v1/services` (type=driver) | `add_service` (type=driver) |
| `ServiceBuilder.createService` (connector) | `POST /v1/services` (type=connector) | `add_service` (type=connector) |
| `ServiceBuilder.createService` (webservice) | `POST /v1/services` (type=webservice) | `add_service` (type=webservice) |
| `ServiceRemover.removeService` (new, D5) | `DELETE /v1/apps/{app_root}/services/{name}` | `service_remove` |

> The four service types are: **processor** (stateful, clusterable), **driver**
> (stateless source), **connector** (a generic Rumi message-bus binding to an
> external system — replaces the old single-purpose `csvwriter`), and
> **webservice** (stateful + clusterable with an embedded HTTP server that talks
> to the engine via `injectRequestAndWaitForReply`, modelled on nvx-accounts).
> The REST/MCP layers take the type as a parameter to a single add operation.

### Message handler operations *(new — not in CLI today)*

| SDK | REST | MCP tool |
|---|---|---|
| `HandlerIntrospector.listHandlers` (new, C2) | `GET /v1/apps/{app_root}/services/{s}/handlers` | `handler_list` |
| `HandlerIntrospector.getHandler` (new, C2) | `GET /v1/apps/{app_root}/services/{s}/handlers/{m}` | `handler_get` |
| `JavaSourceEditor.addHandler` (new, C3) | `POST /v1/apps/{app_root}/services/{s}/handlers` | `handler_add` |
| `JavaSourceEditor.removeHandler` (new, C3) | `DELETE /v1/apps/{app_root}/services/{s}/handlers/{m}` | `handler_remove` |

### Connector operations *(new — snap custom connectors into any service)*

A connector is a user-authored Rumi message-bus binding (a class implementing
`com.neeve.sma.spi.connector.Connector`) wired via a `connector://...&classname=...`
bus binding plus a `<bus name>` reference in the owning app. Works on any
service type. The add creates the Java class + bus binding + app reference;
the remove reverts all three. Inbound message types are added separately via
the message operations (kept composable — connector add mints no factory IDs).

| SDK | REST | MCP tool |
|---|---|---|
| `ConnectorIntrospector.listConnectors` | `GET /v1/services/{s}/connectors` | `list_connectors` |
| `ConnectorIntrospector.getConnector` | `GET /v1/services/{s}/connectors/{name}` | `get_connector` |
| `ConnectorEditor.addConnector` | `POST /v1/services/{s}/connectors` | `add_connector` |
| `ConnectorEditor.removeConnector` | `DELETE /v1/services/{s}/connectors/{name}` | `remove_connector` |

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

### Templates aren't compiled by our build — scaffold-then-`mvn package`

The SDK's own `mvn install` compiles the *scaffolder*, never the *templates*.
A template that references a non-existent API, picks a colliding name, or emits
malformed XML sails through our build and only explodes in the user's generated
app. The webservice template surfaced two such bugs the first time we actually
scaffolded an app and ran `mvn package` on it:

1. **`message` is a reserved field name.** An X-ADML field named `message`
   generates `getMessage()`, which clashes with the `final getMessage()` in
   Rumi's `MessageViewImpl` base class — a hard compile error in the generated
   ADM code. We renamed the sample field to `text`. Lesson: ADM messages
   inherit from `MessageViewImpl`, so avoid field names that collide with its
   final accessors (`message`, and check before reusing common names).
2. **`--` is illegal inside XML comments.** A template comment containing a
   double-dash made the ADM model parser reject the whole `messages.xml`.

Takeaway: any change to a service template must be validated by scaffolding a
real app and running `mvn package` on it (see the Verification section / smoke
test), not just by the SDK build going green.

### Webservice HTTP port defaults per-service, collides per-instance

The webservice template gives every service the same default HTTP port (8080),
exposed as an overridable env property. That's fine for the common
single-instance case but two webservices (or a clustered/partitioned one) will
collide unless the operator overrides the port. We document the override rather
than trying to auto-assign, because the scaffolder can't know the deployment
topology — a token-substitution template can't do arithmetic on the instance
index.

## Runtime test harness (generated apps) — and what "builds ≠ runs" taught us

Generated apps now ship an in-process test harness so a scaffolded system can be
**run**, not just compiled: an `AbstractTest` (in the system module's
`src/test`, using Rumi's `EmbeddedXVM` to boot services), a `test` config
profile (loopback bus + `loopback://.&initWaitTime=0` discovery), and a
`junit` + `maven-surefire` (Java-17 `--add-opens`) wiring in the system POM.
Modelled on the Rumi sample apps (`nvx-apps`) and Paywhere. The
`/test-the-builder` skill drives the whole loop: scaffold every service type +
a custom connector, then build and run it through this harness.

Standing the harness up exposed several bugs that `mvn package` had hidden,
because **no generated app had ever actually been run** — the production
launcher is lenient where the in-process path is strict:

### X-DDL `xmlns=""` failed schema validation under EmbeddedXVM

`ConfigInjector` parsed config fragments as namespace-less XML and appended them
under the x-ddl document, so every injected element serialized with `xmlns=""`.
The production launcher tolerated it; `EmbeddedXVM` schema-validates the DDL and
rejected it (`cvc-complex-type.2.4.a: element 'bus' … x-ddl:bus expected`). Fix:
inject/create config elements in the x-ddl namespace — wrap fragments in an
x-ddl-namespaced root before parsing, and have `XmlDomUtils.getOrCreateChild`
create children in the parent's namespace. Lesson: machine-generated XML must be
namespace-correct, not just well-formed; a lenient consumer hides the defect
until a strict one (a validating parser) loads it.

### Rumi 4.0 needs BOTH javax and jakarta JAXB

The engine never started: Rumi 4.0's config layer (`VMConfigurer`) uses
`javax.xml.bind`, while its engine (`AepEngine`) wires Jackson's *jakarta*-xmlbind
introspector (`jakarta.xml.bind.annotation.*`). The generated parent POM pinned
only the javax-mapped `jakarta.xml.bind-api:2.3.2`, so the jakarta side was
missing and `onMessagingStarted` died — for **every** generated app, webservice
or not. Fix: ship both namespaces side by side — javax via the legacy
`javax.xml.bind:jaxb-api` coordinate (so it doesn't collide with the
`jakarta.xml.bind:jakarta.xml.bind-api:4.0` the engine needs). Lesson: the
javax→jakarta JAXB split is per-Rumi-version; a runtime test is the only way to
catch a dependency that compiles fine but isn't on the runtime classpath.

### Smaller catches

`--` is illegal inside an XML comment (broke a generated POM); the webservice
default port 8080 collides with common local services (tests override it via the
`...http.port` system property).

### ADML type-reference rules learned at runtime

Editing models by hand (or by code generator) means re-discovering rules the ADM
parser enforces but rarely documents. Three came out of running edited models
through ADM+ASM codegen in `/test-the-builder`, and they're worth memorizing
because two of them are *exact opposites*:

1. **Field scalar type names are capitalized** — `Long`, `Integer`, `String`,
   etc. The parser aliases a few lowercase Java primitives (`int`, `char`) to
   their canonical names, but **`long` is NOT aliased** and silently mis-parses.
   So `AdmTypes.normalizeFieldType` now lowercases-then-canonicalizes every field
   type (`long`→`Long`, `int`→`Integer`, …) in `ModelTypeWriter`/`FieldEditor`,
   removing the foot-gun entirely.
2. **An entity used as a message *field type* MUST have `asEmbedded="true"`** on
   its `<entity>`. An embedded entity is serialized inline into the owning
   message; without the flag the codegen won't treat it as embeddable.
3. **An entity used as a *collection element* must NOT be `asEmbedded`**, and a
   collection may only contain entity/message types — **never scalars**. This is
   the exact inverse of rule 2: the same entity is `asEmbedded` when it's a
   message field but plain when it's a collection element. Get the two confused
   and the model compiles in one shape and fails in the other.

The takeaway: model-editing operations can't be "just write the XML" — they have
to encode the ADML type system's quirks, because a malformed-but-well-formed
model sails past the SDK build and only explodes when real codegen runs over it.

## Model editing: ids are never reused

The model-editing epic is **complete** — all phases/slices shipped (now on `1.0`
and `main`). The operation catalog above is fully implemented: message
add/remove (scope-aware: service-messages or the shared ROE model), embedded
`<entity>` CRUD across ROE, service-state, and service-message models,
collections (`StringMap`/…/`Queue`), entity-level attributes (notably
`asEmbedded`), field-type normalization to canonical ADML scalar names,
referential-safety-on-remove (with a `force` override), and app-global
factory-id never-reuse via the `.rumi-factory-ids` ledger. No need to re-list
the operations — every row in the catalog is wired SDK → REST → MCP.

Field, message, entity and collection ids identify a type or field on the wire,
so a removed id must never be re-handed-out — recycling it lets an old peer
misinterpret a new one. `ModelIdAllocator` allocates
`max(present ids ∪ reserved-tombstone ids) + 1`, monotonic; a deleted field is
physically removed but leaves an `<!-- id=N reserved (removed name) -->`
tombstone (the convention the hand-written models already use) so its id stays
retired. The earlier `MessageEditor`/`StateEditor` allocators *gap-filled*
(recycled the lowest free id) and each scanned only one element kind — a latent
backward-compat hazard plus an entity↔collection id-collision risk, both fixed.

Corollaries the model editors encode:
- **No retype.** A field's type can't change on the wire; "change a field" is
  delete (id retired) + add (new id). Deprecate is a *separate* op (keeps the
  field, marks accessors `@Deprecated`). Rename is id-stable and wire-safe (but
  hand-written Java referencing the old accessor still needs a manual fix).
- **API operations reference ROE.** An `api.xml` `<operation>`'s
  `inMessage`/`outMessage` resolve against the model its `<messages modelFile>`
  points at — ROE by default. So operations pair with ROE messages; this only
  surfaces when ASM codegen runs (the model-edit regression in `/test-the-builder`
  runs ADM+ASM on the edited models to catch exactly this class of thing).

### The factory-id ledger: record on add, never on remove

The same "never reuse" discipline applies one level up, to **factory ids**,
which must be unique across the whole system. `FactoryIdCollector` allocates the
first gap in the used set — but a *removed* service frees its ids on disk, and a
naive scan of the live model files would happily re-hand-out that gap to the next
service. So the app keeps an append-only ledger, the `.rumi-factory-ids` sidecar
at the app root. `collectUsedIds` unions the live model-file scan **with** the
ledger, and `recordAllocatedIds(appRoot)` folds the present ids into the ledger
after each `ServiceBuilder.createService` / `ApplicationBuilder.createApplication`
write. The ledger is append-only — never pruned.

The subtle, deliberate choice is **recording on *add*, not on *remove***.
Recording on remove would be the obvious symmetric design, but it's fragile: if
a service module is deleted out-of-band (someone `rm -rf`s the directory, or
deletes it through plain git rather than `ServiceRemover`), the remove hook never
fires and the id silently becomes reusable again. By folding ids into the ledger
the moment they're *minted*, the id survives no matter how the module later
disappears — `ServiceRemover` doesn't even touch the ledger, because it doesn't
need to. The ledger is a high-water record of "ids this app has ever issued,"
which is exactly the invariant we want.

**Migration caveat:** services created *before* this feature shipped have no
ledger entry. Their ids are captured by the live-file scan only while the service
exists; if such a service is removed, its ids fall back to being reusable. The
ledger only protects ids minted after a `createService`/`createApplication` that
ran the recording step. (In practice the test-the-builder runtime check exercised
the new path, so any app scaffolded going forward is covered from birth.)

## Validation: what a schema can and cannot tell you (RUMI-376/377/378/379)

An August 2026 audit asked a blunt question — what actually proves a generated
app is valid? — and the answer was "nothing." The model files the editors spend
most of their time writing were never schema-checked, the one schema we did
bundle was a hand-maintained copy, the tests proved output was readable by the
component that had written it, and no CI step ever compiled a generated app.
Closing that produced four lessons worth more than the code.

### A hand-maintained copy of someone else's schema is a slow-motion bug

`ConfigValidator` validated against a checked-in `x-ddl.xsd` whose own javadoc
called the refresh mechanism a TODO. The interesting part is not that it was
stale — when measured it happened to be byte-identical to the pinned 4.0.637
artifact — but that *nobody could have known either way without checking*. A
validator trusted to be authoritative while quietly a version behind is worse
than no validator: it drifts in both directions, rejecting what the platform
accepts and accepting what it rejects, and both failures look like correctness.

The fix removes the category rather than the instance. All three schemas
(`x-ddl` from `nvx-rumi-ddl`, `x-adml` from `nvx-rumi-adm`, `x-asml` from
`nvx-rumi-client`) are unpacked at build time from the artifacts matching
`nvx.rumi.version`, and the checked-in copy is deleted. Staleness becomes
structurally impossible: there is no second copy to drift, and nothing to
hand-edit. The general shape — *derive it, don't copy it* — applies any time you
find yourself vendoring an artifact that someone else owns and versions.

### Ask what the schema actually checks before trusting it to check anything

The obvious plan was "turn on schema validation and the known ADML foot-guns
fall out." Measured against the real `x-adml.xsd`, they do not. `field/@type` is
declared `xs:string` and XSD 1.0 does no cross-referencing, so `type="long"`
(which the ADM parser does not alias and silently mis-parses), an entity used as
a message field type without `asEmbedded="true"`, and even
`type="NoSuchTypeAnywhere"` all validate perfectly cleanly.

That is why `ModelValidator` is two layers: schema validation for structural
damage, and a semantic layer for the reference rules the schema cannot express.
`ModelValidatorTest.schemaAloneAcceptsWhatTheAdmParserRejects` pins the finding
in a test, so the second layer can never be mistaken for redundant and quietly
deleted by someone who assumes what we assumed. When you add a validator, spend
the ten minutes to find out what it actually rejects; "we validate against the
schema" is a claim about coverage that is very easy to get wrong.

### Round-trip tests through your own parser prove almost nothing

The editor tests all had the same shape: write with the editor, read back with
the matching introspector, assert the values survived. That proves the output is
parseable *by the component that produced it* — a closed loop that cannot detect
a file which is well-formed but invalid.

The moment real schema validation was switched on, it caught a defect that had
been sitting in fixtures across both the SDK and REST suites: they wrote
`key="true"` on a field. ADML has no `key` attribute; the real one is `isKey`.
The SDK passes `FieldDef` attributes through verbatim, so a user who copied that
would have got a model that fails ADM codegen — and the tests would have gone on
being green, because the introspector reading it back did not care either. Some
tests used `isKey` and some used `key`, which is exactly how this kind of thing
hides. **Validate against an external standard, not against your own reader.**

### A check nobody has pointed at real data has not been tested

`ConfigValidator` had existed since Phase D4 and had never once been run against
a scaffolded app's `config.xml` — only against placeholder-free fixtures. The
first time it saw a real one, it failed on every X-DDL property placeholder:
values like `${SERVICE_PERSISTENCE_QUORUM::1}` sit in attributes the schema types
as `integer` and `boolean`, and the platform substitutes them *before* parsing.
So the validator was, in practice, unusable on the very files it was written for,
and the passing test suite said otherwise.

It now resolves `${prop::default}` to the default before validating — faithful
rather than a fudge, since that is the value the platform uses when nothing
overrides it, so the defaults get genuinely type-checked instead of skipped — and
declines to type-check a placeholder with no default, whose value cannot be known
statically. The same sweep found `ConfigFragmentEditor` injecting fragments
without the x-ddl namespace, producing the `xmlns=""` bug written up above as
already fixed. It *was* fixed — in `ConfigInjector`. The sibling editor added
later never got it. **When you fix a bug, grep for the pattern, not the file.**

### Coverage lists that must be remembered will eventually be wrong

The MCP suite's `EXPECTED_TOOLS` had already gone stale once, silently missing a
whole slice of tools. Only 14 of 47 tools were named in any routing test. The
answer is not to write the missing 33 by hand and re-open the same gap next
quarter: it is to *derive* the expected set. `MutatingOperationCoverageTest`
scans for public static `ChangeSet`-returning methods and fails the build if one
has no validity test; `test_tool_coverage.py` drives every registered tool
through the mocked REST boundary and pins `EXPECTED_TOOLS` against live
registration. A list that has to be maintained by memory is a list whose being
wrong looks precisely like its being right.

### The release now refuses to ship a builder that produces broken apps

`ci/release.sh` ran `versions:set` and `-Pdist` and nothing else, so nothing
stopped a release whose generated apps did not compile — the only proof was a
manual skill someone had to remember to run. `ci/verify-generated-app.sh` now
scaffolds every service type plus a custom connector, builds it, and *runs* it
in-process, as step 0 before any artifact reaches the downloads tree. It takes
about a minute. It has no skip flag, deliberately: every other step has one
because skipping it degrades a release, whereas skipping this one would let
through the exact defect it exists to catch.

## Two audiences, one template: the sample-free scaffold (RUMI-382)

A scaffold is a first impression, and for most of this project's life there was
only one audience to make it on: a human opening a generated app, who wants a
worked example to imitate. So the templates ship one — an `/echo` endpoint wired
through a real message to real replicated state, a driver with a rate-governed
sending thread, a connector with a scheduled alarm loop.

Then the audience changed. Sutra and the Rumi Support Agent build Rumi apps
through the Dev MCP, and the first thing they do after scaffolding is *delete all
of it*. The example is not merely useless to them, it is a bill: tokens to read
it, tokens to reason about which parts are load-bearing, tokens to remove it, and
a non-zero chance of leaving a fragment of somebody else's domain model in the
user's application. The scaffold was optimised for the reader who now accounts
for a minority of its use.

The obvious fix is a second, empty set of templates. It is also the wrong one,
for a reason this repository has already written down once: *a hand-maintained
copy of something is a slow-motion bug*. Two trees would agree on the day they
were created and quietly stop agreeing after that, and nothing would notice,
because nobody compiles a template.

So the two modes come out of one tree. Templates mark their demo regions inline
with balanced sentinel comments, in whatever comment syntax the file already
uses, and `SampleMarkers` drops whichever side the caller did not ask for:

```java
    // @sample-begin
    @EventHandler
    final public void onEchoRequest(final EchoRequest request) { ... }
    // @sample-end
    // @bare-begin
    // Request handlers go here...
    // @bare-end
```

There is exactly one definition of the processor template, and the modes cannot
diverge because there is nothing to diverge from.

### Where the default lives is the whole design

The temptation is to pick one default and apply it everywhere. That would be
wrong in one direction or the other, because the layers have different users.
The SDK and REST service keep samples **on** — the `rumi` CLI is theirs, and a
person running `rumi quickstart app` still wants the worked example. The MCP
tools default to **bare**, because their only callers are agents. The CLI gets an
explicit `--no-samples` for the human who wants what the agent gets.

That split is easy to state and easy to break, so `test_create_app_is_sample_free_by_default`
asserts the MCP default directly rather than inferring it from the layers below.
A default that everyone assumes follows from somewhere else is a default nobody
is actually testing.

### Make the mode a property of the app, not of every call

The first shape of this had `includeSamples` on every scaffolding call. It works
until someone creates an app bare and then adds a service without repeating
themselves, which is to say it works until the second call. So the mode is
recorded in the app's `.rumi` descriptor at creation and inherited by every
service added later; an explicit per-service value still wins. Nothing has to be
remembered twice, and `ServiceRemover` needed no changes at all.

That descriptor is also where the one genuinely nasty bug in this work was
waiting. `AppParams` is deserialized from `.rumi` by Gson, and a descriptor
written before this option existed has no `includeSamples` key. Had the field
been a primitive `boolean`, Gson would have left it `false` — silently converting
every app anyone had ever scaffolded to sample-free the moment they added a
service. The field is a nullable `Boolean`, null means "not recorded", and
`aDescriptorPredatingTheOptionStillMeansSamplesOn` pins it. The general shape:
**when you add a field to a serialized record, the interesting case is the
document that predates it**, and a primitive cannot express "absent".

### Deleting code is the direction that breaks things

Bare mode is not a smaller version of the same thing; it is a different artifact,
and removal is exactly the operation that produces well-formed-but-broken output.
An emptied model. An import pointing at a package with no types left in it. A
JAX-RS resource whose last endpoint just left, which compiles perfectly and may
or may not survive Jersey's model validation — a question nobody on this project
could answer from memory, which is precisely why guessing was not an option.

The answer was to keep a `/health` endpoint in the bare webservice and then go
find out empirically. `ci/verify-generated-app.sh` now scaffolds, builds and
*runs* both modes on every release, and `BareWebserviceTest` drives the health
probe over real HTTP against a booted engine. It roughly doubles the gate's
runtime, which is the correct trade: bare is the mode agents actually use, so
leaving it unproven would mean gating the rare path and shipping the common one
on faith.

One smaller lesson fell out of the resolver itself. The first version collapsed
the blank lines a removed region leaves behind with `(\n[ \t]*){3,}` → `\n\n`,
and the greedy final repetition happily ate the *next* line's indentation,
silently de-indenting generated Java. Matching only wholly-blank lines fixes it,
and `collapsingBlankLinesPreservesTheFollowingIndentation` exists so nobody
reintroduces the tidier-looking version.

### The gate's first real run failed on its own input (RUMI-384)

`ci/verify-generated-app.sh` was written for the 1.0.16 cycle but merged after
1.0.16 shipped, so **1.0.17 was the first release ever to run it**. It blocked
that release immediately, printing "this is a builder defect, not a test defect
— the generated app is the product." It was wrong. The generated app was fine.

The build log's error was that `nvx-rumi-bindings-bom:4.0.637` could not be
found, which looks like the Nexus proxy trap that strands the Bindings BOM after
most milestones. The tell that it was something else was in the URL Maven
actually requested:

```
.../nvx-rumi-bindings-bom/4.0.637%1B%5B0m/nvx-rumi-bindings-bom-4.0.637%1B%5B0m.pom
```

`%1B%5B0m` is `ESC [ 0 m`, an ANSI colour reset. The script derives the Rumi
version with `mvn -q -DforceStdout help:evaluate`, and Maven colourises its
output when it believes stdout is a terminal — which it is under TeamCity's
Docker wrapper, and is not when you run it locally through a pipe. So the
captured version was `4.0.637␛[0m`, it went straight into the generated app's
`<nvx.rumi.version>`, and every dependency lookup asked for a version with an
escape sequence in it. The BOM was simply the first to be asked.

Three things worth keeping from this.

**Read the URL, not the error.** "Could not find artifact X:4.0.637" and "could
not find artifact X:4.0.637␛[0m" render identically in a terminal, because the
escape sequence is invisible by construction. The only place the difference
survived was the percent-encoded URL in the log. When an artifact that plainly
exists reports as missing, compare the exact string being requested against the
exact string you expect, byte for byte, before concluding the repository is at
fault.

**A value read from a tool is input, and input gets validated.** The capture now
disables colour, strips escape sequences anyway, and rejects anything that is not
shaped like a version — three guards where one would do, because the failure they
prevent costs a release cycle and they cost three lines. The version check in
particular converts a confusing 404 three minutes downstream into an immediate
message naming the offending value.

**Anything that only ever runs in CI has not been tested.** This script had been
run many times on a developer machine and passed every time; a piped stdout is
not a TTY, so the bug could not appear there. The reproduction was one command —
`script -q /dev/null mvn ...` to attach a TTY — and it should have been part of
writing the script, not part of debugging it. The general form: when a script's
behaviour depends on its environment, exercise it in the environment that
differs, not only the one on your desk.

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

### An unpinned CI step is a landmine that fires on an unrelated commit

The snapshot build's third step validates the MCP sources with `python3 -m
compileall`. The build config had **no agent requirements**, and only two of the
four authorized TeamCity agents carry a `python3` (the two lab/perf agents are
python2-only). For five consecutive builds the scheduler happened to pick the
Default Agent and everything was green — then a **docs-only commit** (six added
lines of markdown, no code) landed on a lab agent and the build went red with
`python3: command not found`, exit 127, after 216 Java tests had already passed.

Two things worth carrying forward. First, the *diagnostic* one: when a build
fails on a commit that cannot possibly have caused it, stop reading the diff and
start diffing the *environment* — compare the agent, the toolchain, and the
parameters against the last green run. The revision is the most visible variable
but it isn't always the one that changed. Second, the *preventive* one: any CI
step that shells out to a host tool has an implicit host dependency, and an
implicit dependency with no agent requirement to enforce it isn't a working
build, it's a build that hasn't been unlucky yet. Both App Builder configs now
pin `teamcity.agent.name = Default Agent` — the release build for the downloads
tree, the snapshot build for `python3`. See `ci/README.md` for the agent
inventory.

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

### A build that watches a different branch fails by saying nothing

Cutting the 4.0.637 milestone (July 2026, App Builder 1.0.16), the version
bump went to `develop` and was pushed. Then: nothing. No build queued, no
red build, no email. The snapshot config's VCS root resolves
`build.appbuilder.branch = 1.0`, and `develop` simply isn't a branch it
watches. The fix is one line of git — fast-forward `1.0` and `main` to
`develop` and push them, which this repo does anyway since the three
branches are kept in lockstep.

The lesson is about the *failure mode*, not the branch name. A VCS trigger
that watches a branch you didn't push to doesn't fail — it declines to
happen. Nothing turns red, so there's no signal to chase; you find out
later, when someone asks why the artifact everyone assumed was published
isn't there. A build that fails loudly is a gift by comparison: it names
itself and hands you a log. Silence is the expensive failure, which is why
"did the push actually trigger a build?" belongs in the release checklist
right next to "did the build go green?" — the first question is the one you
forget to ask.

### `versions:update-properties` will happily hand you an alpha

The other trap on that same milestone bump. Running
`mvn versions:update-properties` to pick up the new `nvx.rumi.version`
also rewrote five unrelated properties in
`nvx-rumi-appbuilder-rest/pom.xml`: jetty `12.0.16` → `12.1.11`, jackson
`2.18.2` → `2.22.1`, swagger `2.2.36` → `2.2.52`, and — the two that
matter — jersey `3.1.11` → **`4.0.0-M2`**, a milestone build, and slf4j
`2.0.16` → **`2.1.0-alpha1`**, an alpha. A jersey major-version jump alone
would have moved the REST service onto a different Jakarta baseline.

The plugin isn't misbehaving; it's doing exactly what it was told, because
nothing told it otherwise. There's no `rulesUri` and no `ignoredVersions`
here, so "latest" means the newest thing in the repository, pre-release
artifacts included. Two habits fall out of that. First, treat this REST
pom's dependency versions as **locked** — a milestone bump changes
`nvx.rumi.version` and nothing else, so the commit is a one-line diff.
Second, and more general: never let a tool that rewrites your build files
commit unreviewed. Read the full `git diff` before staging, every time.
The five reverted lines cost thirty seconds to spot in a diff and would
have cost an afternoon to spot in a stack trace.

*(The plugin is now configured so it can't reach those five properties at
all — see the next entry, which arrived by the opposite door.)*

### The version bump that succeeded at doing nothing

Cutting 4.0.640 (August 2026, App Builder 1.0.20). Same command as last
time, `mvn versions:update-properties`, and this time the diff was clean.
Suspiciously clean. `nvx.rumi.version` in the root `pom.xml` still read
**4.0.637** — the *previous* milestone. The plugin had considered the
property and walked past it. `BUILD SUCCESS`, exit code 0, and — this is
the part worth sitting with — not one line anywhere in the log mentioning
the property it had declined to touch. Had nobody read the diff by hand,
App Builder 1.0.20 would have shipped pinned to the milestone before the
one it advertised.

The mechanism is mundane once you see it. `versions-maven-plugin` links a
property to a dependency by looking for a dependency that *uses* it —
within a single project. `nvx.rumi.version` is declared in the root POM,
but the only dependency that consumes it, `com.neeve:nvx-rumi`, lives down
in `nvx-rumi-appbuilder-rest`. Run at the aggregator, the plugin found a
property with nothing attached to it, shrugged, and moved on. It wasn't
confused and it didn't fail; the link it needed simply didn't exist inside
any one module's four walls. The fix (commit `18c6b1c`, PR #5) is to state
the link the plugin couldn't infer — an explicit
`nvx.rumi.version` → `com.neeve:nvx-rumi` association in the root POM's
`pluginManagement`, plus
`<includeProperties>nvx.rumi.version</includeProperties>` to confine the
goal to the one property a milestone is allowed to move.

That second half is a fix for the *previous* entry, arrived at sideways.
`nvx-os-parent` pins `versions-maven-plugin` at **2.1**, which predates the
`ignoredVersions` parameter — so there is no way to tell it "not
pre-releases", which is why it kept offering jersey `4.0.0-M2` and slf4j
`2.1.0-alpha1` on consecutive milestones. Filtering by *version* was never
available to us. Filtering by *property* was, and it turns out to be the
better instrument anyway: the locked jetty/jersey/jackson/swagger/slf4j
pins are now outside the goal's reach entirely, rather than inside it and
manually reverted by whoever happened to be cutting the milestone. Verified
by resetting the pin to 4.0.637 and re-running — the parent reported
`Updated ${nvx.rumi.version} from 4.0.637 to 4.0.640`, and the two child
modules proposed nothing at all. The known cost:
`versions:display-property-updates` honours the same parameter, so it stops
reporting available jetty/jackson updates. Dependabot is the mechanism for
that visibility now, and it is what drove the RUMI-381 security bumps.

The lesson is the one the previous entry has a mirror image of. That entry
warned about a tool **over-reaching** — changing five things it had no
business changing. Nobody had thought to worry about the opposite: the tool
**under-reaching** and changing nothing, which is much harder to notice,
because a diff that's missing a line doesn't jump out the way a diff with
five extra ones does. A tool that did its job and a tool that quietly did
nothing produce byte-identical exit codes. So: **when automation's job is
to change something, verify the change happened.** Don't infer success from
the absence of an error — check for the presence of the effect. "Did the
version actually move?" now sits in the release checklist right next to
"did the push trigger a build?", and both are there for the same reason:
the failures that cost you a release are the ones that never raise their
hand.
