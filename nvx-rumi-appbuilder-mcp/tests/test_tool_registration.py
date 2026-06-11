"""Smoke test covering tool registration and MCP surface shape.

Confirms every REST endpoint has a matching tool, the names match the
CLI verb convention, and the tool schemas describe the required args.
Does not actually start a server — uses the FastMCP API to list what
got registered.
"""

from __future__ import annotations

import pytest

from rumi_appbuilder_mcp.server import build_server


EXPECTED_TOOLS: set[str] = {
    # Apps
    "list_apps",
    "get_app",
    "create_app",
    # Services
    "list_services",
    "get_service",
    "add_service",
    "remove_service",
    # Handlers
    "list_handlers",
    "get_handler",
    "add_handler",
    "remove_handler",
    # Connectors
    "list_connectors",
    "get_connector",
    "add_connector",
    "remove_connector",
    # Messages
    "list_messages",
    "get_message",
    "add_message",
    "remove_message",
    # Message-model embedded entities
    "list_message_entities",
    "get_message_entity",
    "add_message_entity",
    "remove_message_entity",
    # Fields
    "add_field",
    "delete_field",
    "deprecate_field",
    "rename_field",
    # API operations
    "list_operations",
    "get_operation",
    "add_operation",
    "remove_operation",
    "rename_operation",
    # State entities
    "list_state_entities",
    "get_state_entity",
    "add_state_entity",
    "remove_state_entity",
    # Config
    "get_config",
    "list_config_fragments",
    "add_config_fragment",
    "remove_config_fragment",
    "validate_config",
    # Factory IDs
    "list_factory_ids",
    "next_factory_id",
}


@pytest.mark.asyncio
async def test_server_registers_every_expected_tool() -> None:
    mcp = build_server("http://localhost:3200")
    tools = await mcp.list_tools()
    registered = {t.name for t in tools}
    missing = EXPECTED_TOOLS - registered
    extra = registered - EXPECTED_TOOLS
    assert not missing, f"expected tools missing: {sorted(missing)}"
    assert not extra, f"unexpected tools present: {sorted(extra)}"


@pytest.mark.asyncio
async def test_server_name_is_rumi_dev() -> None:
    # The server advertises itself as rumi-dev, which is what clients
    # see as the mcp__rumi-dev__<tool> prefix.
    mcp = build_server("http://localhost:3200")
    assert mcp.name == "rumi-dev"


@pytest.mark.asyncio
async def test_tool_schemas_describe_required_args() -> None:
    mcp = build_server("http://localhost:3200")
    tools = {t.name: t for t in await mcp.list_tools()}

    # list_apps requires 'under'.
    assert tools["list_apps"].inputSchema["required"] == ["under"]

    # add_service requires app_root, name, type.
    add_service_required = set(tools["add_service"].inputSchema["required"])
    assert {"app_root", "name", "type"} <= add_service_required

    # remove_service requires app_root + name; dry_run is optional.
    remove_service_required = set(tools["remove_service"].inputSchema["required"])
    assert {"app_root", "name"} <= remove_service_required
    assert "dry_run" not in remove_service_required
