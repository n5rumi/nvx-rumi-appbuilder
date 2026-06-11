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


def build_server(base_url: str | None = None) -> FastMCP:
    """Create and configure the MCP server.

    Exposed as a function so tests and alternate entry points can build
    a server wired to a different REST base URL without touching env
    vars.
    """
    rest = AppBuilderClient(base_url=base_url)
    mcp = FastMCP("rumi-dev")

    # ---- Apps --------------------------------------------------------

    @mcp.tool()
    def list_apps(under: str) -> list[str]:
        """List every Rumi app scaffolded under the given parent directory.

        Args:
            under: Absolute path to a directory to scan.
        """
        return rest.get("/v1/apps", {"under": under})

    @mcp.tool()
    def get_app(app_root: str) -> dict[str, Any]:
        """Return metadata for a scaffolded Rumi app (package, groupId, Rumi versions, encoding, messaging provider)."""
        return rest.get("/v1/apps/info", {"app_root": app_root})

    @mcp.tool()
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
    ) -> dict[str, Any]:
        """Scaffold a new Rumi app. Returns the resolved AppParams so the caller knows where the app landed on disk."""
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
            },
        )

    # ---- Services ----------------------------------------------------

    @mcp.tool()
    def list_services(app_root: str) -> list[dict[str, Any]]:
        """List every service (processor, driver, connector, webservice) in an app."""
        return rest.get("/v1/services", {"app_root": app_root})

    @mcp.tool()
    def get_service(app_root: str, name: str) -> dict[str, Any]:
        """Return a single service with rolled-up handlers, messages, state entities, and collections."""
        return rest.get(f"/v1/services/{name}", {"app_root": app_root})

    @mcp.tool()
    def add_service(
        app_root: str,
        name: str,
        type: str,
        ha_model: str | None = None,
        clustered: bool = False,
        partitions: int = 1,
    ) -> dict[str, Any]:
        """Scaffold a new service. Type is processor|driver|connector|webservice. ha_model and clustered/partitions apply to the clusterable types (processor, webservice)."""
        return rest.post(
            "/v1/services",
            params={"app_root": app_root},
            json={
                "name": name,
                "type": type,
                "haModel": ha_model,
                "clustered": clustered,
                "partitions": partitions,
            },
        )

    @mcp.tool()
    def remove_service(app_root: str, name: str, dry_run: bool = False) -> dict[str, Any]:
        """Orchestrated service removal: module dir, parent POM, system POM dep, config fragments, factory IDs."""
        return rest.delete(
            f"/v1/services/{name}",
            params={"app_root": app_root, "dry_run": str(dry_run).lower()},
        )

    # ---- Handlers ----------------------------------------------------

    @mcp.tool()
    def list_handlers(app_root: str, service: str) -> list[dict[str, Any]]:
        """List @EventHandler methods on a service's Main.java."""
        return rest.get(f"/v1/services/{service}/handlers", {"app_root": app_root})

    @mcp.tool()
    def get_handler(app_root: str, service: str, method: str) -> dict[str, Any]:
        """Return a single handler's definition (method name, message type, return type, line)."""
        return rest.get(
            f"/v1/services/{service}/handlers/{method}", {"app_root": app_root}
        )

    @mcp.tool()
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

    @mcp.tool()
    def remove_handler(
        app_root: str, service: str, method: str, dry_run: bool = False
    ) -> dict[str, Any]:
        """Remove an @EventHandler method from a service's Main.java."""
        return rest.delete(
            f"/v1/services/{service}/handlers/{method}",
            params={"app_root": app_root, "dry_run": str(dry_run).lower()},
        )

    # ---- Connectors --------------------------------------------------

    @mcp.tool()
    def list_connectors(app_root: str, service: str) -> list[dict[str, Any]]:
        """List the custom connectors (Rumi message-bus bindings) snapped into a service."""
        return rest.get(f"/v1/services/{service}/connectors", {"app_root": app_root})

    @mcp.tool()
    def get_connector(app_root: str, service: str, name: str) -> dict[str, Any]:
        """Return a single connector with its class, connector bus, and inbound channel."""
        return rest.get(
            f"/v1/services/{service}/connectors/{name}", {"app_root": app_root}
        )

    @mcp.tool()
    def add_connector(
        app_root: str, service: str, name: str, dry_run: bool = False
    ) -> dict[str, Any]:
        """Snap a custom connector into a service: a Connector class, a connector:// bus binding, and the app messaging reference. Idempotent."""
        return rest.post(
            f"/v1/services/{service}/connectors",
            params={"app_root": app_root, "dry_run": str(dry_run).lower()},
            json={"name": name},
        )

    @mcp.tool()
    def remove_connector(
        app_root: str, service: str, name: str, dry_run: bool = False
    ) -> dict[str, Any]:
        """Remove a connector: delete its Connector class, the bus binding, and the app messaging reference."""
        return rest.delete(
            f"/v1/services/{service}/connectors/{name}",
            params={"app_root": app_root, "dry_run": str(dry_run).lower()},
        )

    # ---- Messages ----------------------------------------------------

    @mcp.tool()
    def list_messages(app_root: str, service: str, scope: str = "messages") -> list[dict[str, Any]]:
        """List X-ADML message types. scope is messages (the service's own model, default) or roe (the shared app-wide model)."""
        return rest.get(f"/v1/services/{service}/messages", {"app_root": app_root, "scope": scope})

    @mcp.tool()
    def get_message(app_root: str, service: str, name: str, scope: str = "messages") -> dict[str, Any]:
        """Return a single message type with its fields and local ID. scope is messages|roe."""
        return rest.get(
            f"/v1/services/{service}/messages/{name}", {"app_root": app_root, "scope": scope}
        )

    @mcp.tool()
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

    @mcp.tool()
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

    @mcp.tool()
    def list_message_entities(app_root: str, service: str, scope: str = "messages") -> list[dict[str, Any]]:
        """List embedded X-ADML entities in a message model (entities used as message field types). scope is messages|roe. (State entities are separate — see list_state_entities.)"""
        return rest.get(f"/v1/services/{service}/message-entities", {"app_root": app_root, "scope": scope})

    @mcp.tool()
    def get_message_entity(app_root: str, service: str, name: str, scope: str = "messages") -> dict[str, Any]:
        """Return a single embedded entity from a message model with its fields and local id. scope is messages|roe."""
        return rest.get(
            f"/v1/services/{service}/message-entities/{name}", {"app_root": app_root, "scope": scope}
        )

    @mcp.tool()
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

    @mcp.tool()
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

    @mcp.tool()
    def add_field(
        app_root: str,
        service: str,
        scope: str,
        type: str,
        name: str,
        field_type: str,
        attributes: dict[str, str] | None = None,
        dry_run: bool = False,
    ) -> dict[str, Any]:
        """Add a field to a message or entity. scope is messages|state|roe; type is the message/entity name. The field gets a stable, never-reused id."""
        return rest.post(
            f"/v1/services/{service}/fields",
            params={"app_root": app_root, "dry_run": str(dry_run).lower()},
            json={"scope": scope, "type": type, "name": name, "fieldType": field_type, "attributes": attributes or {}},
        )

    @mcp.tool()
    def delete_field(
        app_root: str, service: str, scope: str, type: str, name: str, dry_run: bool = False
    ) -> dict[str, Any]:
        """Delete a field. Its id is reserved forever (tombstone) so it is never reused. (A field's type can't change on the wire, so to 'retype' delete then add.)"""
        return rest.delete(
            f"/v1/services/{service}/fields",
            params={"app_root": app_root, "scope": scope, "type": type, "name": name, "dry_run": str(dry_run).lower()},
        )

    @mcp.tool()
    def deprecate_field(
        app_root: str, service: str, scope: str, type: str, name: str, dry_run: bool = False
    ) -> dict[str, Any]:
        """Deprecate a field: keep it, mark its accessors @Deprecated. Distinct from delete."""
        return rest.post(
            f"/v1/services/{service}/fields/deprecate",
            params={"app_root": app_root, "scope": scope, "type": type, "name": name, "dry_run": str(dry_run).lower()},
        )

    @mcp.tool()
    def rename_field(
        app_root: str, service: str, scope: str, type: str, name: str, to: str, dry_run: bool = False
    ) -> dict[str, Any]:
        """Rename a field (id unchanged; wire-safe). Hand-written Java referencing the old accessor still needs fixing."""
        return rest.post(
            f"/v1/services/{service}/fields/rename",
            params={"app_root": app_root, "scope": scope, "type": type, "name": name, "to": to, "dry_run": str(dry_run).lower()},
        )

    # ---- API operations ---------------------------------------------

    @mcp.tool()
    def list_operations(app_root: str, service: str) -> list[dict[str, Any]]:
        """List the request-reply API operations in a service's api.xml."""
        return rest.get(f"/v1/services/{service}/operations", {"app_root": app_root})

    @mcp.tool()
    def get_operation(app_root: str, service: str, name: str) -> dict[str, Any]:
        """Return a single API operation (its request and response messages)."""
        return rest.get(f"/v1/services/{service}/operations/{name}", {"app_root": app_root})

    @mcp.tool()
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

    @mcp.tool()
    def remove_operation(
        app_root: str, service: str, name: str, dry_run: bool = False
    ) -> dict[str, Any]:
        """Remove an API operation (drops its generated client method)."""
        return rest.delete(
            f"/v1/services/{service}/operations/{name}",
            params={"app_root": app_root, "dry_run": str(dry_run).lower()},
        )

    @mcp.tool()
    def rename_operation(
        app_root: str, service: str, name: str, to: str, dry_run: bool = False
    ) -> dict[str, Any]:
        """Rename an API operation."""
        return rest.post(
            f"/v1/services/{service}/operations/{name}/rename",
            params={"app_root": app_root, "to": to, "dry_run": str(dry_run).lower()},
        )

    # ---- State entities ---------------------------------------------

    @mcp.tool()
    def list_state_entities(app_root: str, service: str) -> list[dict[str, Any]]:
        """List X-ADML state entities defined in the service's state.xml."""
        return rest.get(
            f"/v1/services/{service}/state-entities", {"app_root": app_root}
        )

    @mcp.tool()
    def get_state_entity(app_root: str, service: str, name: str) -> dict[str, Any]:
        """Return a single state entity with its fields, keys, and attributes."""
        return rest.get(
            f"/v1/services/{service}/state-entities/{name}",
            {"app_root": app_root},
        )

    @mcp.tool()
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

    @mcp.tool()
    def remove_state_entity(
        app_root: str, service: str, name: str, force: bool = False, dry_run: bool = False
    ) -> dict[str, Any]:
        """Remove a state entity from a service's state.xml. Blocked if a field/collection in the model still references it — pass force=true to remove anyway."""
        return rest.delete(
            f"/v1/services/{service}/state-entities/{name}",
            params={"app_root": app_root, "force": str(force).lower(), "dry_run": str(dry_run).lower()},
        )

    # ---- Collections -------------------------------------------------

    @mcp.tool()
    def list_collections(app_root: str, service: str) -> list[dict[str, Any]]:
        """List X-ADML collections (maps/queues) in the service's state model."""
        return rest.get(f"/v1/services/{service}/collections", {"app_root": app_root})

    @mcp.tool()
    def get_collection(app_root: str, service: str, name: str) -> dict[str, Any]:
        """Return a single state collection with its kind, element type, and local id."""
        return rest.get(
            f"/v1/services/{service}/collections/{name}", {"app_root": app_root}
        )

    @mcp.tool()
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

    @mcp.tool()
    def remove_collection(
        app_root: str, service: str, name: str, dry_run: bool = False
    ) -> dict[str, Any]:
        """Remove a collection from the service's state model. Its id is reserved (tombstone) so it is never reused."""
        return rest.delete(
            f"/v1/services/{service}/collections/{name}",
            params={"app_root": app_root, "dry_run": str(dry_run).lower()},
        )

    # ---- Config ------------------------------------------------------

    @mcp.tool()
    def get_config(app_root: str) -> str:
        """Return the rendered config.xml as text."""
        return rest.get_text("/v1/config", {"app_root": app_root})

    @mcp.tool()
    def list_config_fragments(
        app_root: str, profile: str | None = None
    ) -> list[dict[str, Any]]:
        """List every config fragment, optionally filtered by profile name. Fragments carry scope path, tag, name, and the rendered XML."""
        return rest.get(
            "/v1/config/fragments",
            {"app_root": app_root, "profile": profile},
        )

    @mcp.tool()
    def add_config_fragment(
        app_root: str, scope_path: list[str], xml: str, dry_run: bool = False
    ) -> dict[str, Any]:
        """Add an X-DDL fragment under the given scope path (e.g. ["apps","templates"], ["buses"]). Idempotent — a structurally identical fragment is a no-op."""
        return rest.post(
            "/v1/config/fragments",
            params={"app_root": app_root, "dry_run": str(dry_run).lower()},
            json={"scopePath": scope_path, "xml": xml},
        )

    @mcp.tool()
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

    @mcp.tool()
    def validate_config(app_root: str) -> dict[str, Any]:
        """Run X-DDL schema validation. Returns a ValidationResult envelope with ok flag plus an errors list (each has severity, line, column, message)."""
        return rest.post("/v1/config/validate", params={"app_root": app_root})

    # ---- Factory IDs ------------------------------------------------

    @mcp.tool()
    def list_factory_ids(app_root: str) -> dict[str, str]:
        """Return a map of every used factory ID to its owner description."""
        return rest.get("/v1/factory-ids", {"app_root": app_root})

    @mcp.tool()
    def next_factory_id(app_root: str) -> dict[str, int]:
        """Return the lowest unused factory ID as {"nextAvailableId": N}. Not reserved — callers should allocate through the SDK's add endpoints."""
        return rest.get("/v1/factory-ids/next", {"app_root": app_root})

    return mcp
