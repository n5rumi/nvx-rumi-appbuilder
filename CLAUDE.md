# CLAUDE.md

This file provides guidance to Claude Code when working in this repository.

## Project Overview

**nvx-rumi-development** is a monorepo for projects that support
**developing applications on the Rumi platform** (Rumi is an N5 product).
It groups
related dev-tooling projects so they can share a git history, release
cadence, and code conventions without forcing a single build reactor
across unrelated technology stacks.

There is no top-level `pom.xml`. Each peer project owns its own build,
release, and deployment story.

## Peer Projects

| Directory | Purpose | Build |
|---|---|---|
| `nvx-rumi-appbuilder/` | The Rumi App Builder — SDK + REST service + MCP server for scaffolding and incrementally building Rumi apps | Maven (+ Python for the MCP module) |

Planned (not yet started, names indicative):

- `nvx-rumi-ide-plugin/` — IDE integration (IntelliJ, VS Code) using the
  App Builder REST API / MCP server.
- `nvx-rumi-codegen/` — ROE / ADM code generation utilities separable
  from the main Rumi build.

Each peer project carries its own `CLAUDE.md` and `PROJECT.md`.

## What Belongs Here

Anything that exists to make **app development on Rumi** easier. The
tests:

- Is it a tool a Rumi developer would install or use to build an app?
  (Yes → belongs here.)
- Is it a runtime component of the Rumi platform itself? (No → belongs
  in `nvx-rumi` / `nvx-rumi-messaging` / etc.)
- Is it customer-facing documentation? (No → belongs in `nvx-rumi-docs`.)

## Conventions

- **Directory naming**: `nvx-rumi-<project-name>` for each peer.
  Sub-modules under a peer use `<project-name>-<role>` (e.g.
  `nvx-rumi-appbuilder-sdk`, `-rest`, `-mcp`).
- **Maven group**: `com.neeve` throughout.
- **Parent POM** (for Java peers): `com.neeve:nvx-os-parent:1.1.5`
  directly, not an inherited top-level project POM.
- **Language-mixed peers**: a Java+Python peer (like
  `nvx-rumi-appbuilder`) can have Python sub-modules with `pyproject.toml`
  sitting alongside Maven sub-modules. The parent POM's `<modules>` lists
  only the Maven children.
- **Git commits**: no `Co-Authored-By` trailers.

## Branch Strategy

- `develop` — active development
- `main` — stable releases
