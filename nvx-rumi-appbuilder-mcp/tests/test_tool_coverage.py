"""RUMI-378: every registered tool actually reaches the REST service.

The hand-written routing tests in ``test_tool_routing.py`` assert precise
paths and query strings, which is what you want for the tools whose wiring
is fiddly. But they only ever named a third of the surface, and nothing
noticed: ``add_field``, ``add_operation``, ``add_state_entity``,
``add_connector`` and ``create_app`` were among the tools with no routing
test at all.

That gap is structural, so the fix is structural. Rather than hand-write the
missing tests and let the list drift again, these tests derive the tool set
from the server's own registration and drive every one of them through the
mocked HTTP boundary. A newly added tool is covered the moment it is
registered, and a tool that stops reaching REST fails here.
"""

from __future__ import annotations

from typing import Any

import httpx
import pytest
import respx

from rumi_appbuilder_mcp.server import build_server


BASE = "http://test"


@pytest.fixture
def mcp():
    return build_server(BASE)


async def _registered_tools(mcp) -> list[Any]:
    return await mcp.list_tools()


def _sample_for(schema: dict[str, Any]) -> Any:
    """A plausible value for a parameter, chosen by its JSON-schema type.

    The values are arbitrary — nothing downstream inspects them, because the
    REST service is mocked. What matters is that they satisfy the tool's
    signature so the call reaches the HTTP layer.
    """
    if "anyOf" in schema:
        for option in schema["anyOf"]:
            if option.get("type") != "null":
                return _sample_for(option)
        return None
    kind = schema.get("type")
    if kind == "integer":
        return 1
    if kind == "number":
        return 1.0
    if kind == "boolean":
        return False
    if kind == "array":
        return []
    if kind == "object":
        return {}
    return "x"


def _arguments_for(tool: Any) -> dict[str, Any]:
    schema = tool.inputSchema or {}
    properties = schema.get("properties", {})
    required = schema.get("required", list(properties))
    return {name: _sample_for(properties.get(name, {})) for name in required}


@respx.mock
async def test_every_registered_tool_reaches_the_rest_service(mcp) -> None:
    """No registered tool is a dead end.

    Any tool that never issues a request is either unwired or quietly doing
    its own thing — both of which defeat the point of the MCP layer being a
    thin, uniform pass-through to REST.
    """
    # One catch-all route: this test is about whether a request happens at
    # all, not where it goes. The hand-written tests own path precision.
    route = respx.route(host="test").mock(
        return_value=httpx.Response(200, json={})
    )

    tools = await _registered_tools(mcp)
    assert tools, "no tools registered at all"

    silent: list[str] = []
    for tool in tools:
        before = route.call_count
        try:
            await mcp.call_tool(tool.name, _arguments_for(tool))
        except Exception as exc:  # noqa: BLE001
            # A tool that made its request and then choked deserializing our
            # stub response has done its job — the stub is a single shape for
            # 47 differently-typed tools, so a mismatch here says nothing
            # about the tool. Only a tool that never issued a request failed.
            if route.call_count == before:
                silent.append(f"{tool.name} ({type(exc).__name__}: {exc})")
            continue
        if route.call_count == before:
            silent.append(tool.name)

    assert not silent, (
        "these tools issued no HTTP request, so they never reach REST:\n  "
        + "\n  ".join(silent)
    )


@respx.mock
async def test_mutating_tools_use_a_mutating_http_method(mcp) -> None:
    """An add/remove/rename tool that quietly issues a GET is a bug.

    Cheap to assert and it pins the convention the whole surface follows:
    the verb in the tool name matches the verb on the wire.
    """
    route = respx.route(host="test").mock(
        return_value=httpx.Response(200, json={})
    )

    # "update_" belongs here for the same reason as the rest: a tool that
    # says it changes something and issues a GET is a bug. It was missing,
    # so update_handler (RUMI-411) would have been skipped by this guard.
    prefixes = ("add_", "create_", "update_", "remove_", "delete_", "rename_", "deprecate_")
    wrong: list[str] = []
    for tool in await _registered_tools(mcp):
        if not tool.name.startswith(prefixes):
            continue
        before = route.call_count
        try:
            await mcp.call_tool(tool.name, _arguments_for(tool))
        except Exception:  # noqa: BLE001 - reachability is the test above's job
            pass
        if route.call_count == before:
            continue
        method = route.calls.last.request.method
        if method not in {"POST", "PUT", "PATCH", "DELETE"}:
            wrong.append(f"{tool.name} used {method}")

    assert not wrong, (
        "these mutating tools did not use a mutating HTTP method:\n  "
        + "\n  ".join(wrong)
    )


async def test_registered_tool_set_matches_the_documented_expectation(mcp) -> None:
    """Guards ``EXPECTED_TOOLS`` in test_tool_registration.py against drift.

    That set is hand-maintained and has gone stale before — it did not pick
    up the slice-1 connector, field and operation tools until somebody
    noticed by chance. Comparing it against live registration here means a
    tool added without updating it fails the build instead.
    """
    from test_tool_registration import EXPECTED_TOOLS

    registered = {tool.name for tool in await _registered_tools(mcp)}
    missing = EXPECTED_TOOLS - registered
    unlisted = registered - EXPECTED_TOOLS

    assert not missing, f"EXPECTED_TOOLS names tools that are not registered: {sorted(missing)}"
    assert not unlisted, (
        "these tools are registered but absent from EXPECTED_TOOLS in "
        f"test_tool_registration.py: {sorted(unlisted)}"
    )
