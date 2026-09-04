"""MCP server registration for the Rumi App Builder.

Each tool is a typed wrapper around one REST endpoint. We deliberately
keep the surface flat — every tool maps 1:1 to a REST call so the
semantics stay identical across CLI, REST, and MCP.

Tool naming convention: ``<verb>_<noun>`` for reads (``list_services``,
``get_app``) and ``<verb>_<noun>`` for writes
(``add_service``, ``remove_handler``), matching the CLI verb shape.
Runtime MCP namespace is ``rumi-dev`` (set via the FastMCP constructor),
so clients see tools as ``mcp__rumi-dev__list_services`` etc.
"""

from __future__ import annotations

from typing import Any

from mcp.server.fastmcp import FastMCP

from .client import AppBuilderClient
from .usage import ToolUsage


def build_server(base_url: str | None = None) -> FastMCP:
    """Create and configure the MCP server.

    Exposed as a function so tests and alternate entry points can build
    a server wired to a different REST base URL without touching env
    vars.
    """
    rest = AppBuilderClient(base_url=base_url)
    mcp = FastMCP("rumi-dev")
    usage = ToolUsage()

    def tool() -> Any:
        """Register a tool and count its calls (RUMI-415).

        Stands in for ``@mcp.tool()`` on every app-building tool below, so
        adoption is recorded centrally rather than at 49 call sites.
        """

        def decorate(fn: Any) -> Any:
            usage.register(fn.__name__)
            return mcp.tool()(usage.counted(fn))

        return decorate

    # ---- Apps --------------------------------------------------------

    @tool()
    def list_apps(under: str) -> list[str]:
        """List every Rumi app scaffolded under the given parent directory.

        Args:
            under: Absolute path to a directory to scan.
        """
        return rest.get("/v1/apps", {"under": under})

    @tool()
    def get_app(app_root: str) -> dict[str, Any]:
        """Return metadata for a scaffolded Rumi app (package, groupId, Rumi versions, encoding, messaging provider)."""
        return rest.get("/v1/apps/info", {"app_root": app_root})

    @tool()
    def create_app(
        app_name: str,
        app_dir: str,
        package_name: str,
        group_id: str,
        artifact_prefix: str,
        rumi_version: str,
        rumi_bindings_version: str | None = None,
        rumi_mgmt_version: str | None = None,
        encoding_type: str = "QUARK",
        messaging_provider: str = "ACTIVEMQ",
        build_tool: str = "MAVEN",
        include_samples: bool = False,
    ) -> dict[str, Any]:
        """Scaffold a new Rumi app. Returns the resolved AppParams so the caller knows where the app landed on disk.

        By default the scaffold contains no worked example code, so you can start
        writing the real application immediately rather than deleting demo
        messages and handlers first. The choice is recorded in the app and
        inherited by every service you add later. Pass include_samples=True only
        if you want the illustrative Echo/alarm/message-sending examples.
        """
        return rest.post(
            "/v1/apps",
            json={
                "appName": app_name,
                "appDir": app_dir,
                "packageName": package_name,
                "groupId": group_id,
                "artifactPrefix": artifact_prefix,
                "rumiVersion": rumi_version,
                "rumiBindingsVersion": rumi_bindings_version,
                "rumiMgmtVersion": rumi_mgmt_version,
                "encodingType": encoding_type,
                "messagingProvider": messaging_provider,
                "buildTool": build_tool,
                "includeSamples": include_samples,
            },
        )

    # ---- Services ----------------------------------------------------

    @tool()
    def list_services(app_root: str) -> list[dict[str, Any]]:
        """List every service (processor, driver, connector, webservice) in an app."""
        return rest.get("/v1/services", {"app_root": app_root})

    @tool()
    def get_service(app_root: str, name: str) -> dict[str, Any]:
        """Return a single service with rolled-up handlers, messages, state entities, and collections."""
        return rest.get(f"/v1/services/{name}", {"app_root": app_root})

    @tool()
    def add_service(
        app_root: str,
        name: str,
        type: str,
        ha_model: str | None = None,
        clustered: bool = False,
        partitions: int = 1,
        include_samples: bool = False,
    ) -> dict[str, Any]:
        """Scaffold a new service. Type is processor|driver|connector|webservice. ha_model is STATE_REPLICATION or EVENT_SOURCING. ha_model and clustered/partitions apply to the clusterable types (processor, webservice).

        As with create_app, the scaffold carries no worked example code by
        default -- just the wiring (injection points, state factory, HTTP server
        lifecycle) and the javadoc explaining each contract. Pass
        include_samples=True for the illustrative version.
        """
        return rest.post(
            "/v1/services",
            params={"app_root": app_root},
            json={
                "name": name,
                "type": type,
                "haModel": ha_model,
                "clustered": clustered,
                "partitions": partitions,
                "includeSamples": include_samples,
            },
        )

    @tool()
    def remove_service(app_root: str, name: str, dry_run: bool = False) -> dict[str, Any]:
        """Orchestrated service removal: module dir, parent POM, system POM dep, config fragments, factory IDs."""
        return rest.delete(
            f"/v1/services/{name}",
            params={"app_root": app_root, "dry_run": str(dry_run).lower()},
        )

    # ---- Handlers ----------------------------------------------------

    @tool()
    def list_handlers(app_root: str, service: str, include_body: bool = False) -> list[dict[str, Any]]:
        """List a service's @EventHandler methods. Bodies are omitted unless include_body is true - a listing answers what handlers exist; ask get_handler for one body rather than dumping every one."""
        return rest.get(
            f"/v1/services/{service}/handlers",
            {"app_root": app_root, "include_body": str(include_body).lower()},
        )

    @tool()
    def get_handler(app_root: str, service: str, method: str) -> dict[str, Any]:
        """Return a single handler's definition AND its body, verbatim and without the enclosing braces. Hand the body back to update_handler unchanged and the file is byte-identical."""
        return rest.get(
            f"/v1/services/{service}/handlers/{method}", {"app_root": app_root}
        )

    @tool()
    def add_handler(
        app_root: str,
        service: str,
        method: str,
        message_type: str,
        body: str | None = None,
        dry_run: bool = False,
    ) -> dict[str, Any]:
        """Add an @EventHandler method. body is the Java method body (without braces); None inserts an empty TODO body."""
        return rest.post(
            f"/v1/services/{service}/handlers",
            params={"app_root": app_root, "dry_run": str(dry_run).lower()},
            json={"method": method, "messageType": message_type, "body": body},
        )

    @tool()
    def update_handler(
        app_root: str,
        service: str,
        method: str,
        body: str,
        dry_run: bool = False,
    ) -> dict[str, Any]:
        """Replace an existing @EventHandler method's body, leaving its signature and the rest of the file untouched. This is how you change what a handler does - do not edit Main.java by hand. Idempotent; an unchanged body is a no-op. Rejects a body that does not parse and leaves the file as it was. body is the Java method body without braces; "" empties the handler."""
        return rest.put(
            f"/v1/services/{service}/handlers/{method}",
            params={"app_root": app_root, "dry_run": str(dry_run).lower()},
            json={"body": body},
        )

    @tool()
    def remove_handler(
        app_root: str, service: str, method: str, dry_run: bool = False
    ) -> dict[str, Any]:
        """Remove an @EventHandler method from a service's Main.java."""
        return rest.delete(
            f"/v1/services/{service}/handlers/{method}",
            params={"app_root": app_root, "dry_run": str(dry_run).lower()},
        )

    # ---- Connectors --------------------------------------------------

    @tool()
    def list_connectors(app_root: str, service: str) -> list[dict[str, Any]]:
        """List the custom connectors (Rumi message-bus bindings) snapped into a service."""
        return rest.get(f"/v1/services/{service}/connectors", {"app_root": app_root})

    @tool()
    def get_connector(app_root: str, service: str, name: str) -> dict[str, Any]:
        """Return a single connector with its class, connector bus, and inbound channel."""
        return rest.get(
            f"/v1/services/{service}/connectors/{name}", {"app_root": app_root}
        )

    @tool()
    def add_connector(
        app_root: str, service: str, name: str, dry_run: bool = False
    ) -> dict[str, Any]:
        """Snap a custom connector into a service: a Connector class, a connector:// bus binding, and the app messaging reference. Idempotent."""
        return rest.post(
            f"/v1/services/{service}/connectors",
            params={"app_root": app_root, "dry_run": str(dry_run).lower()},
            json={"name": name},
        )

    @tool()
    def remove_connector(
        app_root: str, service: str, name: str, dry_run: bool = False
    ) -> dict[str, Any]:
        """Remove a connector: delete its Connector class, the bus binding, and the app messaging reference."""
        return rest.delete(
            f"/v1/services/{service}/connectors/{name}",
            params={"app_root": app_root, "dry_run": str(dry_run).lower()},
        )

    # ---- Messages ----------------------------------------------------

    @tool()
    def list_messages(app_root: str, service: str, scope: str = "messages") -> list[dict[str, Any]]:
        """List X-ADML message types. scope is messages (the service's own model, default) or roe (the shared app-wide model)."""
        return rest.get(f"/v1/services/{service}/messages", {"app_root": app_root, "scope": scope})

    @tool()
    def get_message(app_root: str, service: str, name: str, scope: str = "messages") -> dict[str, Any]:
        """Return a single message type with its fields and local ID. scope is messages|roe."""
        return rest.get(
            f"/v1/services/{service}/messages/{name}", {"app_root": app_root, "scope": scope}
        )

    @tool()
    def add_message(
        app_root: str,
        service: str,
        name: str,
        fields: list[dict[str, Any]] | None = None,
        scope: str = "messages",
        dry_run: bool = False,
    ) -> dict[str, Any]:
        """Add a message type. scope is messages (service model, default) or roe (shared app-wide model). fields is a list of {name, type, attributes?} objects; attributes is a string->string map (e.g. {"key": "true"}). The local id is never reused."""
        return rest.post(
            f"/v1/services/{service}/messages",
            params={"app_root": app_root, "scope": scope, "dry_run": str(dry_run).lower()},
            json={"name": name, "fields": fields or []},
        )

    @tool()
    def remove_message(
        app_root: str, service: str, name: str, scope: str = "messages",
        force: bool = False, dry_run: bool = False
    ) -> dict[str, Any]:
        """Remove a message type. scope is messages|roe. Blocked if an api operation or @EventHandler in the service still references it — pass force=true to remove anyway. Its id is reserved (tombstone) so it is never reused."""
        return rest.delete(
            f"/v1/services/{service}/messages/{name}",
            params={"app_root": app_root, "scope": scope, "force": str(force).lower(), "dry_run": str(dry_run).lower()},
        )

    # ---- Message-model embedded entities -----------------------------

    @tool()
    def list_message_entities(app_root: str, service: str, scope: str = "messages") -> list[dict[str, Any]]:
        """List embedded X-ADML entities in a message model (entities used as message field types). scope is messages|roe. (State entities are separate — see list_state_entities.)"""
        return rest.get(f"/v1/services/{service}/message-entities", {"app_root": app_root, "scope": scope})

    @tool()
    def get_message_entity(app_root: str, service: str, name: str, scope: str = "messages") -> dict[str, Any]:
        """Return a single embedded entity from a message model with its fields and local id. scope is messages|roe."""
        return rest.get(
            f"/v1/services/{service}/message-entities/{name}", {"app_root": app_root, "scope": scope}
        )

    @tool()
    def add_message_entity(
        app_root: str,
        service: str,
        name: str,
        fields: list[dict[str, Any]] | None = None,
        scope: str = "messages",
        as_embedded: bool = True,
        attributes: dict[str, str] | None = None,
        dry_run: bool = False,
    ) -> dict[str, Any]:
        """Add an embedded entity to a message model (a reusable type for message fields). scope is messages (service model, default) or roe (shared app-wide model). fields follow the same shape as add_message. as_embedded defaults true — an entity used as a message field type MUST be embedded (set false only for a standalone type). attributes is extra entity-level attributes. Its local id is never reused."""
        attrs = dict(attributes or {})
        attrs.setdefault("asEmbedded", "true" if as_embedded else "false")
        return rest.post(
            f"/v1/services/{service}/message-entities",
            params={"app_root": app_root, "scope": scope, "dry_run": str(dry_run).lower()},
            json={"name": name, "attributes": attrs, "fields": fields or []},
        )

    @tool()
    def remove_message_entity(
        app_root: str, service: str, name: str, scope: str = "messages",
        force: bool = False, dry_run: bool = False
    ) -> dict[str, Any]:
        """Remove an embedded entity from a message model. scope is messages|roe. Blocked if a field/collection in the model still references it — pass force=true to remove anyway. Its id is reserved (tombstone) so it is never reused."""
        return rest.delete(
            f"/v1/services/{service}/message-entities/{name}",
            params={"app_root": app_root, "scope": scope, "force": str(force).lower(), "dry_run": str(dry_run).lower()},
        )

    # ---- Fields ------------------------------------------------------

    @tool()
    def apply_model(
        app_root: str,
        edits: list[dict[str, Any]],
        dry_run: bool = False,
    ) -> dict[str, Any]:
        """Apply a whole model in ONE call. edits is an ordered list of {"kind","service","name","scope","fields","is","contains","attributes"} where kind is message|message_entity|state_entity|collection|fields. scope is messages|state|roe and is REQUIRED for kind:"fields" - a message and a state entity can share a name, so it is refused rather than guessed - and optional elsewhere, defaulting to the kind's own model. Reach for this FIRST when building or extending a model - one call instead of one per message, entity, collection and field. Use attributes for entity-level ADM attributes on a state_entity or message_entity, notably {"asEmbedded":"true"} to declare an embedded entity; field-level attributes go on the field itself. Order is preserved, so an edit can add a message and a later edit can add fields to it. All-or-nothing: a rejected edit rolls the whole batch back and the app is left exactly as it was. The result reports each item, including which were no-ops because they already existed, so re-applying a model is safe."""
        return rest.post(
            "/v1/model/batch",
            params={"app_root": app_root, "dry_run": str(dry_run).lower()},
            json={"edits": edits},
        )

    @tool()
    def add_field(
        app_root: str,
        service: str,
        scope: str,
        type: str,
        name: str | None = None,
        field_type: str | None = None,
        attributes: dict[str, str] | None = None,
        fields: list[dict[str, Any]] | None = None,
        dry_run: bool = False,
    ) -> dict[str, Any]:
        """Add one field, or several at once. scope is messages|state|roe; type is the message/entity name. PREFER the fields[] form - [{"name","type","attributes"}] - when adding more than one: it is one call and one write instead of a round trip per field, and a rejected field means none are added. Send either name or fields, not both. Each field gets a stable, never-reused id."""
        return rest.post(
            f"/v1/services/{service}/fields",
            params={"app_root": app_root, "dry_run": str(dry_run).lower()},
            json={"scope": scope, "type": type, "name": name, "fieldType": field_type,
                  "attributes": attributes or {}, "fields": fields},
        )

    @tool()
    def delete_field(
        app_root: str, service: str, scope: str, type: str, name: str, dry_run: bool = False
    ) -> dict[str, Any]:
        """Delete a field. Its id is reserved forever (tombstone) so it is never reused. (A field's type can't change on the wire, so to 'retype' delete then add.)"""
        return rest.delete(
            f"/v1/services/{service}/fields",
            params={"app_root": app_root, "scope": scope, "type": type, "name": name, "dry_run": str(dry_run).lower()},
        )

    @tool()
    def deprecate_field(
        app_root: str, service: str, scope: str, type: str, name: str, dry_run: bool = False
    ) -> dict[str, Any]:
        """Deprecate a field: keep it, mark its accessors @Deprecated. Distinct from delete."""
        return rest.post(
            f"/v1/services/{service}/fields/deprecate",
            params={"app_root": app_root, "scope": scope, "type": type, "name": name, "dry_run": str(dry_run).lower()},
        )

    @tool()
    def rename_field(
        app_root: str, service: str, scope: str, type: str, name: str, to: str, dry_run: bool = False
    ) -> dict[str, Any]:
        """Rename a field (id unchanged; wire-safe). Hand-written Java referencing the old accessor still needs fixing."""
        return rest.post(
            f"/v1/services/{service}/fields/rename",
            params={"app_root": app_root, "scope": scope, "type": type, "name": name, "to": to, "dry_run": str(dry_run).lower()},
        )

    # ---- API operations ---------------------------------------------

    @tool()
    def list_operations(app_root: str, service: str) -> list[dict[str, Any]]:
        """List the request-reply API operations in a service's api.xml."""
        return rest.get(f"/v1/services/{service}/operations", {"app_root": app_root})

    @tool()
    def get_operation(app_root: str, service: str, name: str) -> dict[str, Any]:
        """Return a single API operation (its request and response messages)."""
        return rest.get(f"/v1/services/{service}/operations/{name}", {"app_root": app_root})

    @tool()
    def add_operation(
        app_root: str,
        service: str,
        name: str,
        in_message: str,
        out_message: str,
        rest_path: str | None = None,
        rest_method: str | None = None,
        dry_run: bool = False,
    ) -> dict[str, Any]:
        """Add a request-reply API operation. in_message/out_message must be known messages (service or ROE). Idempotent on name."""
        return rest.post(
            f"/v1/services/{service}/operations",
            params={"app_root": app_root, "dry_run": str(dry_run).lower()},
            json={"name": name, "inMessage": in_message, "outMessage": out_message,
                  "restPath": rest_path, "restMethod": rest_method},
        )

    @tool()
    def remove_operation(
        app_root: str, service: str, name: str, dry_run: bool = False
    ) -> dict[str, Any]:
        """Remove an API operation (drops its generated client method)."""
        return rest.delete(
            f"/v1/services/{service}/operations/{name}",
            params={"app_root": app_root, "dry_run": str(dry_run).lower()},
        )

    @tool()
    def rename_operation(
        app_root: str, service: str, name: str, to: str, dry_run: bool = False
    ) -> dict[str, Any]:
        """Rename an API operation."""
        return rest.post(
            f"/v1/services/{service}/operations/{name}/rename",
            params={"app_root": app_root, "to": to, "dry_run": str(dry_run).lower()},
        )

    # ---- State entities ---------------------------------------------

    @tool()
    def list_state_entities(app_root: str, service: str) -> list[dict[str, Any]]:
        """List X-ADML state entities defined in the service's state.xml."""
        return rest.get(
            f"/v1/services/{service}/state-entities", {"app_root": app_root}
        )

    @tool()
    def get_state_entity(app_root: str, service: str, name: str) -> dict[str, Any]:
        """Return a single state entity with its fields, keys, and attributes."""
        return rest.get(
            f"/v1/services/{service}/state-entities/{name}",
            {"app_root": app_root},
        )

    @tool()
    def add_state_entity(
        app_root: str,
        service: str,
        name: str,
        fields: list[dict[str, Any]] | None = None,
        attributes: dict[str, str] | None = None,
        dry_run: bool = False,
    ) -> dict[str, Any]:
        """Add a state entity. fields follow the same shape as add_message; mark a key field with its attributes={"isKey": "true"}. attributes here is entity-level (e.g. {"asEmbedded": "true"} for an entity nested as a field of another state entity)."""
        return rest.post(
            f"/v1/services/{service}/state-entities",
            params={"app_root": app_root, "dry_run": str(dry_run).lower()},
            json={"name": name, "attributes": attributes or {}, "fields": fields or []},
        )

    @tool()
    def remove_state_entity(
        app_root: str, service: str, name: str, force: bool = False, dry_run: bool = False
    ) -> dict[str, Any]:
        """Remove a state entity from a service's state.xml. Blocked if a field/collection in the model still references it — pass force=true to remove anyway."""
        return rest.delete(
            f"/v1/services/{service}/state-entities/{name}",
            params={"app_root": app_root, "force": str(force).lower(), "dry_run": str(dry_run).lower()},
        )

    # ---- Collections -------------------------------------------------

    @tool()
    def list_collections(app_root: str, service: str) -> list[dict[str, Any]]:
        """List X-ADML collections (maps/queues) in the service's state model."""
        return rest.get(f"/v1/services/{service}/collections", {"app_root": app_root})

    @tool()
    def get_collection(app_root: str, service: str, name: str) -> dict[str, Any]:
        """Return a single state collection with its kind, element type, and local id."""
        return rest.get(
            f"/v1/services/{service}/collections/{name}", {"app_root": app_root}
        )

    @tool()
    def add_collection(
        app_root: str,
        service: str,
        name: str,
        is_: str,
        contains: str,
        attributes: dict[str, str] | None = None,
        dry_run: bool = False,
    ) -> dict[str, Any]:
        """Add a collection to the service's state model. is_ is the kind (StringMap|IntMap|LongMap|…|Queue); contains is the element type (an entity/message name or scalar). Its local id is never reused."""
        return rest.post(
            f"/v1/services/{service}/collections",
            params={"app_root": app_root, "dry_run": str(dry_run).lower()},
            json={"name": name, "is": is_, "contains": contains, "attributes": attributes or {}},
        )

    @tool()
    def remove_collection(
        app_root: str, service: str, name: str, dry_run: bool = False
    ) -> dict[str, Any]:
        """Remove a collection from the service's state model. Its id is reserved (tombstone) so it is never reused."""
        return rest.delete(
            f"/v1/services/{service}/collections/{name}",
            params={"app_root": app_root, "dry_run": str(dry_run).lower()},
        )

    # ---- Config ------------------------------------------------------

    @tool()
    def get_config(app_root: str) -> str:
        """Return the WHOLE rendered config.xml as text. Prefer list_config_fragments with a scope_path and selector when you want one part of it - this returns the entire file however narrow the question."""
        return rest.get_text("/v1/config", {"app_root": app_root})

    @tool()
    def list_config_fragments(
        app_root: str,
        profile: str | None = None,
        scope_path: list[str] | None = None,
        tag: str | None = None,
        name: str | None = None,
    ) -> list[dict[str, Any]]:
        """List config fragments, each carrying its scope path, tag, name and rendered XML. Narrow with profile, an exact scope_path (e.g. ["xvms","templates"]) and a tag/name selector - the same selector remove_config_fragment takes. Use this rather than get_config: a narrowed read answers a specific question, get_config returns the whole file whatever you asked. A selected xvm or app fragment carries its own <env> block in the returned XML."""
        params: dict[str, Any] = {"app_root": app_root, "profile": profile, "tag": tag, "name": name}
        if scope_path:
            params["scope_path"] = scope_path
        return rest.get("/v1/config/fragments", params)

    @tool()
    def add_config_fragment(
        app_root: str, scope_path: list[str], xml: str, dry_run: bool = False
    ) -> dict[str, Any]:
        """Add an X-DDL fragment under the given scope path (e.g. ["apps","templates"], ["buses"]). Idempotent — a structurally identical fragment is a no-op."""
        return rest.post(
            "/v1/config/fragments",
            params={"app_root": app_root, "dry_run": str(dry_run).lower()},
            json={"scopePath": scope_path, "xml": xml},
        )

    @tool()
    def remove_config_fragment(
        app_root: str,
        scope_path: list[str],
        tag: str | None = None,
        name: str | None = None,
        dry_run: bool = False,
    ) -> dict[str, Any]:
        """Remove fragment(s) matching a selector (tag, name, or both) under a scope path. At least one of tag/name must be set."""
        return rest.delete(
            "/v1/config/fragments",
            params={"app_root": app_root, "dry_run": str(dry_run).lower()},
            json={"scopePath": scope_path, "tag": tag, "name": name},
        )

    @tool()
    def validate_config(app_root: str) -> dict[str, Any]:
        """Run X-DDL schema validation. Returns a ValidationResult envelope with ok flag plus an errors list (each has severity, line, column, message)."""
        return rest.post("/v1/config/validate", params={"app_root": app_root})

    # ---- Factory IDs ------------------------------------------------

    @tool()
    def list_factory_ids(app_root: str) -> dict[str, str]:
        """Return a map of every used factory ID to its owner description."""
        return rest.get("/v1/factory-ids", {"app_root": app_root})

    @tool()
    def next_factory_id(app_root: str) -> dict[str, int]:
        """Return the lowest unused factory ID as {"nextAvailableId": N}. Not reserved — callers should allocate through the SDK's add endpoints."""
        return rest.get("/v1/factory-ids/next", {"app_root": app_root})

    # ---- Instrumentation ---------------------------------------------

    # Registered directly rather than through `tool()`: this reports on the
    # app-building surface and is not part of it, so it stays out of both
    # halves of the ratio it exists to show.
    @mcp.tool()
    def tool_usage_report() -> dict[str, Any]:
        """Report which Dev MCP tools have been called in this server process, how often, and which have never been called.

        Use it to check that structural questions are going to the tools
        rather than to shell inspection. `never_called` is the interesting
        half. Counts run from `started_at` (this process's start) and outlive
        any one conversation, so to scope a single build, call this at the
        start and end and diff `by_tool`.
        """
        return usage.report()

    return mcp
