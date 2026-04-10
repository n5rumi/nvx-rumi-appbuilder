# PROJECT.md -- nvx-rumi-development (Rumi App Builder)

## What Is This Project?

Think of this project as a **blueprint factory** for the Neeve Rumi platform. When you want to build a new distributed application on Rumi, you don't start from scratch -- you run the App Builder, and it stamps out a complete, ready-to-build Maven project with services, configuration, deployment scripts, and data models already wired together. It is the `create-react-app` of the Rumi world, but for high-performance event-driven Java services.

The single deliverable module is `nvx-rumi-appbuilder`.

## Technical Architecture

### The Pipeline: From Parameters to a Running Project

The builder follows a straightforward pipeline:

1. **ApplicationBuilder** receives high-level parameters (app name, Java package, Rumi version, encoding type, messaging provider) and creates the skeleton Maven multi-module project. It extracts embedded template files, performs token substitution, and writes a `.rumi` JSON config that remembers what was generated.

2. **ServiceBuilder** adds services to an existing app. There are three flavors:
   - **DRIVER** -- data ingestion, cannot be clustered.
   - **PROCESSOR** -- business logic, supports high availability via state replication or event sourcing.
   - **CSVWRITER** -- data output, cannot be clustered.

   For each service, the builder updates POMs, injects XML config, and injects deployment scripts.

3. **TemplateProcessor** uses ClassGraph to scan the classpath for template files. Every template file -- including its *filename* -- can contain `{{TokenName}}` placeholders that get recursively substituted. So a file literally named `{{ServiceArtifactId}}/pom.xml` becomes `order-processor/pom.xml` after processing.

4. **ConfigInjector** does DOM-based XML surgery. It merges per-service config fragments into the app's master `config.xml`, navigating profile hierarchies (cloud vs. standalone) and deduplicating entries so re-running the builder is safe (idempotent).

5. **ScriptInjector** splices service-specific shell script snippets into deployment scripts, handling multi-instance (partitioned) services.

6. **FactoryIdCollector** scans ROE model XML files to find which factory IDs (0-32767) are already taken, then hands out the next available one. It fills gaps before incrementing, so IDs stay compact.

7. **TokenUtils** provides the string transformations that glue everything together: camelCase to kebab-case, package names to directory paths, PascalCase conversions, and display name generation.

### Template Layout

All templates live under `src/main/resources/templates/maven/`:

```
templates/maven/
  app/          -- Base app skeleton (parent POM, system module, ROE module, config, assembly)
  service/      -- Per-service-type templates (driver, processor, csvwriter)
  config/       -- Config XML fragments for each service type and deployment profile
  scripts/      -- Deployment script templates with injection points
```

### Generated Project Structure

The output is a Maven multi-module project:

```
my-app/
  pom.xml               -- Parent POM
  my-app-system/         -- Runtime module (services, config.xml, deployment scripts)
  my-app-roe/            -- Data model module (ROE message definitions)
```

## Technologies Used

| Technology | Why |
|---|---|
| **Java 11+** (requires JDK 17 to build) | Platform language; matches Rumi runtime |
| **ClassGraph** | Fast classpath scanning to discover and extract embedded templates at runtime |
| **Gson** | Lightweight JSON for the `.rumi` config file |
| **Maven** | Both the build tool for this project and the project type it generates |
| **DOM XML API** | Full control over XML manipulation for config injection (SAX would be read-only) |

## Lessons Learned

### The Files.walk Ordering Bug (April 2025)

**The symptom:** Generated `config.xml` files would sometimes have individual XVM or app instance declarations appear *before* the `<templates>` section in a profile. Rumi's config parser expects `<templates>` to come first -- if it doesn't, template references from those instances can't resolve, and the app fails to start. The maddening part: it worked on some machines and not others, and even on the same machine it would sometimes pass and sometimes fail.

**The root cause:** `ConfigInjector` processes config fragment files discovered via `java.nio.file.Files.walk()`. The Java documentation buries an important detail: `Files.walk` returns entries in **no guaranteed order**. The order depends on the filesystem implementation, OS, and even the phase of the moon (okay, not literally, but it might as well). So when config fragments for both template definitions and instance declarations existed, the instance declaration could be processed first, causing its XML element to be `appendChild`-ed before the `<templates>` element was created.

**The fix:** In the `getOrCreateChild` method, when creating a `<templates>` element, instead of blindly calling `parent.appendChild(child)`, we now scan the parent's existing children. If any non-template element siblings already exist, we use `parent.insertBefore(child, refNode)` to place `<templates>` ahead of them. This guarantees correct ordering regardless of `Files.walk` traversal order.

**The takeaway:** Never assume filesystem traversal order. `Files.walk`, `File.listFiles`, and `Path.list` are all non-deterministic. If your logic depends on processing order, either sort the results explicitly or make your insertion logic order-independent (as we did here). This class of bug is especially nasty because it's a *heisenbug* -- it may pass every test on your machine and then fail in CI or on a colleague's laptop. If you ever see "works on my machine" combined with XML or file processing, check traversal order first.

### Factory ID Collisions

Factory IDs in Rumi must be unique across the entire system (0-32767 range). The `FactoryIdCollector` solves this by scanning all existing model files before assigning new IDs, and it fills gaps in the sequence rather than always incrementing. This is worth remembering: whenever you have a global numeric namespace, scan-then-assign beats increment-and-hope.

### Idempotent Code Generation

Both `ConfigInjector` and `ScriptInjector` detect duplicates before inserting. This means running the builder twice with the same parameters won't corrupt the output. Idempotency is a design choice worth making early -- it's much harder to retrofit than to build in from the start.
