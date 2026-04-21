# Rumi App Builder REST Service

Canonical Rumi REST service that wraps `nvx-rumi-appbuilder-sdk` and
exposes every scaffolder operation as an HTTP endpoint. Runs as a
long-lived, Rumi-managed process on the sandbox.

**See also**: `../PROJECT.md` for the umbrella architecture, the full
operation catalog (SDK method → REST endpoint → MCP tool), and the
phased upstream plan.

## What Is This?

A thin HTTP front-end over the scaffolder SDK. Every endpoint maps 1:1
to a method on `nvx-rumi-appbuilder-sdk`. Zero logic lives here that
isn't in the SDK — this module is endpoint wiring, request/response
marshalling, and lifecycle management.

The service runs on every Rumi Agent sandbox as a sibling of the agent
itself. Consumers that prefer HTTP over MCP go here; consumers that
prefer MCP go through `nvx-rumi-appbuilder-mcp`, which itself goes
through this service.

## Tech Stack — Canonical Rumi REST

**Packaging and lifecycle**: modelled on the **Rumi Management Agent**
(`github/nvx-rumi-management/rumi-agent`). That service already solves
the AMI-bake, systemd, auto-upgrade, provision/deploy/configure/launch
story for Rumi-managed services. Reusing its shape is the shortest path
to "this runs in production like any other Rumi service."

**REST resource architecture**: modelled on the **Datafye API REST
service** (`github/datafye-platform/datafye-api/datafye-api-rest`). That
codebase has a clean resource-class structure — one resource class per
API surface, HK2 `@Inject`, standard exception mapping, OpenAPI
generation. Reusing its shape gives us a familiar pattern that other
N5 Java devs will recognise.

**DI**: HK2, with a Guice-HK2 bridge if needed (the Management Agent
uses one for reasons TBD during implementation — investigate and decide).

**AepEngine access**: REST resources get `@Inject AepEngine` via HK2 so
they can communicate with the service engine for stateful operations or
outbound message publishing. Matches the Management Agent pattern.

**OpenAPI**: exposed at `GET /openapi.json` for consumers and for MCP
auto-generation (see `nvx-rumi-appbuilder-mcp/PROJECT.md`).

## Endpoint Catalog

1:1 with the SDK methods and MCP tools — see `../PROJECT.md` for the
full table. Summary:

- `POST /v1/apps` — create app
- `GET /v1/apps` — list apps under a parent dir (query: `under`)
- `GET /v1/apps/{app_root}` — get app metadata
- `GET /v1/apps/{app_root}/services` — list services
- `GET /v1/apps/{app_root}/services/{name}` — get service detail
- `POST /v1/apps/{app_root}/services/{type}` — add service (type ∈ `processor`, `driver`, `csvwriter`)
- `DELETE /v1/apps/{app_root}/services/{name}` — remove service
- `{GET,POST,DELETE} /v1/apps/{app_root}/services/{s}/handlers[/{m}]` — handler CRUD
- `{GET,POST,DELETE} /v1/apps/{app_root}/services/{s}/messages[/{m}]` — message type CRUD
- `{GET,POST,DELETE} /v1/apps/{app_root}/services/{s}/state-entities[/{e}]` — state entity CRUD
- `{GET,POST,DELETE} /v1/apps/{app_root}/config/fragments` — config fragment CRUD
- `GET /v1/apps/{app_root}/config` — rendered config
- `POST /v1/apps/{app_root}/config/validate` — X-DDL schema validation
- `GET /v1/apps/{app_root}/factory-ids` — list used IDs
- `GET /v1/apps/{app_root}/factory-ids/next?kind={k}` — next available

Every mutation endpoint accepts `dry_run=true` as a query param or JSON
body field; returns the structured change set either way.

## Class Structure (planned)

Following `datafye-api-rest`:

```
nvx-rumi-appbuilder-rest/
├── pom.xml
└── src/main/java/com/neeve/rumi/appbuilder/rest/
    ├── AppBuilderRestApplication.java      # JAX-RS Application + HK2 binder
    ├── resources/
    │   ├── AppsResource.java               # /v1/apps, /v1/apps/{root}
    │   ├── ServicesResource.java           # /v1/apps/{root}/services/*
    │   ├── HandlersResource.java           # /.../handlers/*
    │   ├── MessagesResource.java           # /.../messages/*
    │   ├── StateEntitiesResource.java      # /.../state-entities/*
    │   ├── ConfigResource.java             # /.../config/*
    │   └── FactoryIdsResource.java         # /.../factory-ids/*
    ├── dto/
    │   ├── requests/                       # request DTOs
    │   ├── responses/                      # response DTOs incl. ChangeSet
    │   └── ChangeSet.java                  # shared change-set envelope
    ├── mappers/
    │   └── ExceptionMappers.java           # SDK exceptions -> HTTP status
    └── binders/
        └── SdkBinder.java                  # HK2 binding for SDK entry points
```

**Resource class pattern** (per Datafye):

```java
@Path("/v1/apps/{appRoot}/services")
public class ServicesResource {
    @Inject private ApplicationBuilder appBuilder;
    @Inject private ServiceBuilder serviceBuilder;
    @Inject private ServiceIntrospector serviceIntrospector;
    @Inject private AepEngine engine;          // for outbound events

    @GET
    public List<ServiceInfoDto> list(@PathParam("appRoot") String appRoot) {
        return serviceIntrospector.listServices(Path.of(appRoot))
            .stream().map(ServiceInfoDto::from).toList();
    }

    @POST @Path("/processor")
    public ChangeSet addProcessor(@PathParam("appRoot") String appRoot,
                                  AddProcessorRequest req,
                                  @QueryParam("dry_run") boolean dryRun) {
        return serviceBuilder.addProcessor(Path.of(appRoot), req.toParams(dryRun));
    }
    // etc.
}
```

## Deployment

Packaged as a Rumi service — shaded JAR + wrapper scripts + X-DDL config
+ systemd integration, using the Management Agent's template.

- **Install location**: `/opt/rumi/appbuilder/rest/`
- **Systemd unit**: `rumi-appbuilder-rest.service`
- **Port**: 3200 (default) — `RUMI_APPBUILDER_REST_PORT` to override
- **Installed by**: the Rumi Agent installer, as a sibling of the agent
  and the MCP server. Single install bundle; see
  `../nvx-rumi-appbuilder-mcp/PROJECT.md` for the full packaging story.

## Running Locally

```bash
cd nvx-rumi-appbuilder-rest
mvn clean package

export RUMI_APPBUILDER_REST_PORT=3200
java -jar target/nvx-rumi-appbuilder-rest-*.jar
```

Health at `GET /health`; OpenAPI at `GET /openapi.json`; MCP endpoint
isn't here — that's the MCP sibling module's concern.

## Open Questions (course-correct as we go)

These need explicit input before implementation — flagging now so they
don't get glossed over:

1. **Guice-HK2 bridge** — does the Rumi Management Agent use it, and if
   so, why? If yes, replicate. If it's legacy, skip it here.
2. **Which specific version of the Rumi Mgmt Agent stack** do we anchor
   on? There's likely a shared Rumi-service-template POM or archetype —
   identify and inherit from it explicitly.
3. **AepEngine injection pattern** — does every resource get the engine,
   or only the ones that publish outbound? Lean toward injecting only
   where needed.
4. **Request/response DTOs vs. passing SDK types through** — safer to
   have DTO classes that mirror SDK types (decouples HTTP API from
   library refactors), but costs boilerplate. Pick a rule and stick to
   it.
5. **Exception → HTTP status mapping** — define once in a single
   `ExceptionMapper`, or sprinkle `@ExceptionMapper` annotations. Lean
   one-file for readability.
6. **OpenAPI generation** — Swagger Core annotations on resources
   (compile-time), or run-time introspection via Jersey integration?
   Lean compile-time so the spec is in Git and can drive MCP generation.

Each of these lands as a ticket under RUMI-282 with a checkpoint for
confirmation before code.
