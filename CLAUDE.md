# CLAUDE.md

This file provides guidance to Claude Code (claude.ai/code) when working with code in this repository.

## Project Overview

**nvx-rumi-appbuilder** is a Java code generation / scaffolding tool for creating distributed applications on the Neeve Rumi platform. It generates complete Maven multi-module projects with services, configuration, and deployment scripts via embedded templates with token substitution (`{{TokenName}}`).

## Build Commands

```bash
# Build the project
mvn clean install

# Build without tests
mvn clean install -DskipTests

# Check license headers
mvn license:check
```

Maven repositories are hosted at `nexus.rumidata.io` and `nexus.n5corp.com`. The parent POM is `com.neeve:nvx-os-parent:1.1.5`.

## Architecture

The project has a single module: `nvx-rumi-appbuilder`.

### Core Components (all in `com.neeve.appbuilder`)

- **ApplicationBuilder** — Entry point. Creates a new Rumi app from `AppParams` (app name, package, Rumi version, encoding type, messaging provider). Extracts Maven app templates, writes a `.rumi` JSON config file.
- **ServiceBuilder** — Adds services to an existing app. Three service types: DRIVER (data input, non-clusterable), PROCESSOR (business logic, clusterable with STATE_REPLICATION or EVENT_SOURCING HA), CSVWRITER (data output, non-clusterable). Updates POMs, injects config XML, injects deployment scripts.
- **TemplateProcessor** — Extracts embedded templates from the classpath using ClassGraph, performs recursive `{{Token}}` substitution on filenames and file contents.
- **ConfigInjector** — DOM-based XML manipulation to merge service config fragments into the app's `config.xml`, handling profile hierarchies (cloud/standalone) and deduplication.
- **ScriptInjector** — Injects service-specific script snippets into deployment scripts, handling partitioned (multi-instance) services.
- **FactoryIdCollector** — Scans model XML files to find used factory IDs (0–32767 range), returns available gaps and next IDs to prevent collisions.
- **TokenUtils** — String transformations: camelCase→kebab-case, package→path, PascalCase, display names.

### Template Structure (`src/main/resources/templates/maven/`)

- `app/` — Base app skeleton (parent POM, system module, ROE module, config, assembly)
- `service/` — Per-service-type templates (driver, processor, csvwriter) with Main.java and pom.xml
- `config/` — Service config fragments for each type and deployment profile (cloud/standalone)
- `scripts/` — Deployment script templates with injection points

### Key Design Patterns

- Templates use `{{TokenName}}` placeholders in both filenames and content (e.g., `{{ParentArtifactId}}`, `{{SystemArtifactId}}`)
- Config and script injection is idempotent — duplicates are detected and skipped
- Factory ID assignment fills gaps before incrementing to avoid collisions
- Generated apps follow Maven multi-module structure: parent → system (runtime) + ROE (data models)

## Dependencies

- **ClassGraph** (`io.github.classgraph:classgraph:4.8.162`) — Classpath scanning for template extraction
- **Gson** (`com.google.gson:gson:2.10.1`) — JSON serialization of app config (`.rumi` file)
- Target: Java 8 (release 8), builds with Maven Compiler Plugin 3.13.0

## Branch Strategy

- `develop` — Active development
- `main` — Stable releases
