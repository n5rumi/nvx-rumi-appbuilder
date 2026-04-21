# nvx-rumi-development

## What Is This Repo?

A home for the projects that support **developing applications on
Rumi**. Not the platform itself — that lives in `nvx-rumi`,
`nvx-rumi-messaging`, etc. Not the docs — those live in
`nvx-rumi-docs`. Just the tools that sit in a developer's hands when
they build a Rumi app: scaffolders, code-gen utilities, IDE plugins,
and whatever else grows up around that need.

Today the repo has one peer project:

- **`nvx-rumi-appbuilder/`** — the Rumi App Builder. A Java SDK with a
  REST service and a Python MCP server layered on top. Scaffolds Rumi
  apps end-to-end, lets agents and other tools extend them
  incrementally (add services, handlers, config fragments, etc.). See
  `nvx-rumi-appbuilder/PROJECT.md` for the full design.

Tomorrow's peers are likely to include an IDE plugin, a standalone
code-gen utility, and whatever else turns out to need a home.

## Why a Monorepo?

The alternative — a separate git repo per dev tool — would scatter
related projects across ten repositories that version together, share
templates, and often land changes in lockstep. Monorepo keeps those
changes in one commit range and gives cross-project refactors a single
place to land.

We pay for that with the usual monorepo costs: one big git history,
and a bit more scrolling to find a specific project. Both are bounded
since each peer project lives cleanly in its own subdirectory with its
own build.

## Structural Rules

- **No top-level `pom.xml`.** Each peer project owns its own build.
  This lets Java, Python, Node, and whatever-comes-next peers
  co-exist without pretending they're one reactor.
- **Each peer is self-contained.** Documentation, build files,
  installers, release notes — all live inside the peer's directory.
- **Shared conventions only at the repo level.** Naming conventions,
  Maven group ID, the rule "no `Co-Authored-By`" — those are
  documented in `CLAUDE.md` and apply across peers.

## What's In `nvx-rumi-appbuilder/`

Three modules:

- **SDK** (`nvx-rumi-appbuilder-sdk`) — Java library. The original
  appbuilder code; all scaffolder logic lives here. Consumed directly
  by `nvx-rumi-cli` as a Maven dependency.
- **REST service** (`nvx-rumi-appbuilder-rest`) — canonical Rumi
  service (Mgmt-Agent-style lifecycle, Datafye-API-REST-style resource
  classes) that wraps the SDK and exposes every operation as an HTTP
  endpoint. New work, in flight.
- **MCP server** (`nvx-rumi-appbuilder-mcp`) — Python MCP server that
  wraps the REST service. One MCP tool per REST endpoint. New work, in
  flight.

Detail in `nvx-rumi-appbuilder/PROJECT.md`, including the full
operation catalog (SDK method → REST endpoint → MCP tool) and the
phased gap-fill plan tracked under JIRA epic RUMI-282.
