# Rumi App Builder MCP Server

Python Model Context Protocol server that wraps
`nvx-rumi-appbuilder-rest`. Every MCP tool is a typed HTTP call to one
REST endpoint. Zero logic lives here that isn't in the REST service
(which in turn delegates to the SDK).

**See also**: `../PROJECT.md` for the umbrella architecture, the full
operation catalog (SDK method → REST endpoint → MCP tool), and the
phased upstream plan.

## What Is This?

A thin Python translation layer over the REST service. Its only job is
to present the REST surface as MCP tools — same names, same arguments,
same change-set responses, just wrapped so MCP-speaking clients can
call them natively.

Consumers that speak MCP go through this; consumers that prefer HTTP
go REST-direct. Both paths reach the same code underneath.

## Tech Stack

- **Python 3.11+**
- **Official MCP SDK** (`mcp` on PyPI)
- **HTTP transport** (SSE or streamable-HTTP — align with whatever the
  Rumi Agent already registers)
- **httpx** for talking to the REST service
- Packaged as a pip-installable project with a `pyproject.toml`;
  **not** in the Maven reactor

## Tool Catalog

1:1 with the REST endpoints — see `../PROJECT.md`. MCP tool names use
the `<verb>_<entity>` convention: `add_service` (with a `type` arg of
`processor|driver|connector|webservice`), `add_handler`, `remove_message`,
`add_connector`/`remove_connector`, etc. Every mutation tool takes
`dry_run: bool = False` and returns a structured change set.

## Runtime Namespace: `rumi-dev`

The MCP server registers itself under the short prefix `rumi-dev`, so
tools appear to clients as `mcp__rumi-dev__<tool_name>`. That short name
is deliberate — `mcp__rumi-mcp-dev__handler_add` or
`mcp__rumi-appbuilder-mcp__handler_add` would clutter every prompt. The
monorepo directory name (`nvx-rumi-appbuilder-mcp`) is engineering
convenience; the runtime namespace is tuned for agent prompt readability.

## Implementation: auto-generated or hand-written?

Two viable approaches — pick during implementation:

### (a) Auto-generate from OpenAPI

The REST service publishes `GET /openapi.json`. An OpenAPI-to-MCP
generator produces the tool definitions, input schemas, and client calls
automatically. Tools stay synchronised as the REST API evolves.

- **Pros**: zero drift between REST and MCP; free to add endpoints;
  small hand-maintained surface.
- **Cons**: depends on a tool chain (an off-the-shelf generator or one
  we write); tool descriptions are only as good as the OpenAPI
  annotations.

### (b) Hand-write

One small Python file per entity group (apps, services, handlers, etc.),
each tool is a ~15-line function that calls the REST endpoint.

- **Pros**: trivially readable; each tool's description is hand-tuned;
  no generator dependency.
- **Cons**: hand-maintained; new REST endpoints need a matching MCP
  tool; easier to drift.

Lean toward **(a) auto-generation**: cheapest at steady state and
forces clean OpenAPI annotations on the REST service (which is a good
discipline regardless). If the generator ecosystem is unready, fall back
to (b) and revisit once the ecosystem catches up.

## Project Structure (planned)

```
nvx-rumi-appbuilder-mcp/
├── pyproject.toml
├── README.md
└── src/rumi_appbuilder_mcp/
    ├── __init__.py
    ├── __main__.py            # entry point: `python -m rumi_appbuilder_mcp`
    ├── server.py              # MCP server setup, transport, health
    ├── config.py              # env var plumbing (REST URL, port, etc.)
    ├── client.py              # httpx client wrapping REST calls
    └── tools/                 # one module per entity group (if hand-written)
        ├── apps.py
        ├── services.py
        ├── handlers.py
        ├── messages.py
        ├── state_entities.py
        ├── config_fragments.py
        └── factory_ids.py
```

If auto-generation lands, `tools/` is replaced by a single
`generated_tools.py` with a build step that regenerates it from the
REST service's OpenAPI spec.

## Deployment

- **Install location**: `/opt/rumi/appbuilder/mcp/` (Python venv +
  entry-point wrapper script)
- **Systemd unit**: `rumi-appbuilder-mcp.service` with
  `After=rumi-appbuilder-rest.service` and
  `Requires=rumi-appbuilder-rest.service` so the MCP waits for the REST
  service to be up
- **Port**: 3201 (default) — `RUMI_APPBUILDER_MCP_PORT` to override
- **REST URL**: `RUMI_APPBUILDER_REST_URL` (default `http://127.0.0.1:3200`)
- **Installed by**: the Rumi Agent installer, as part of the same bundle
  that carries the REST service. See `../PROJECT.md` "Downstream Track 2"

## Running Locally

```bash
cd nvx-rumi-appbuilder-mcp
pip install -e .

# REST service needs to be running (see ../nvx-rumi-appbuilder-rest/)
export RUMI_APPBUILDER_REST_URL=http://127.0.0.1:3200
export RUMI_APPBUILDER_MCP_PORT=3201

python -m rumi_appbuilder_mcp
```

MCP endpoint at `http://127.0.0.1:3201/mcp`; health at
`http://127.0.0.1:3201/health`.

## Open Questions

- **MCP transport choice** — SSE or streamable-HTTP. Match whatever the
  Rumi Agent's MCP client registers.
- **OpenAPI generator** — which one? `openapi-python-client`-style plus
  a small MCP adapter, or something more tightly integrated?
- **Error surfacing** — REST errors map to MCP tool errors with what
  payload shape? Lean: REST error JSON body passes through as the MCP
  error message, plus a short human description.
- **Versioning** — MCP tool input-schema versioning vs. REST endpoint
  versioning. If we auto-generate, both move together; if hand-written,
  we need a convention.

Each of these lands as a ticket under RUMI-282 with a checkpoint.
