"""Integration-style tests: each tool routes to the expected REST endpoint.

Uses respx to mock the REST service and verifies the tool sends the
right method + path + params/body. Doesn't spin up the full MCP
transport — calls the tool functions directly via the FastMCP tool
manager so we keep the test scope at the HTTP boundary.
"""

from __future__ import annotations

import json

import httpx
import pytest
import respx

from rumi_appbuilder_mcp.server import build_server


BASE = "http://test"


async def _call(mcp, _tool: str, **kwargs):
    """Invoke a tool by name through the FastMCP tool manager.

    The first arg is named with a leading underscore so it doesn't
    collide with tool parameters that happen to be called ``name``
    (e.g. ``add_service(name=...)``).
    """
    result = await mcp.call_tool(_tool, kwargs)
    # FastMCP returns a CallToolResult; strip to the underlying payload.
    return result[0] if isinstance(result, tuple) else result


def _query(request) -> str:
    """Return the request's decoded query string so assertions work uniformly across URL encoders."""
    from urllib.parse import unquote
    return unquote(request.url.query.decode())


@pytest.fixture
def mcp():
    return build_server(BASE)


@respx.mock
async def test_list_apps_routes_to_query_param(mcp) -> None:
    route = respx.get(f"{BASE}/v1/apps").mock(
        return_value=httpx.Response(200, json=["/ws/a"])
    )
    await _call(mcp, "list_apps", under="/ws")
    assert route.called
    assert _query(route.calls.last.request) == "under=/ws"


@respx.mock
async def test_get_app_hits_info_endpoint(mcp) -> None:
    respx.get(f"{BASE}/v1/apps/info").mock(
        return_value=httpx.Response(200, json={"appName": "trading"})
    )
    await _call(mcp, "get_app", app_root="/ws/trading")


@respx.mock
async def test_add_service_posts_typed_body(mcp) -> None:
    route = respx.post(f"{BASE}/v1/services").mock(
        return_value=httpx.Response(200, json={"name": "order-processor"})
    )
    await _call(
        mcp,
        "add_service",
        app_root="/ws/t",
        name="order-processor",
        type="processor",
        clustered=False,
        partitions=1,
    )
    req = route.calls.last.request
    assert b"order-processor" in req.content
    assert b"processor" in req.content


@respx.mock
async def test_create_app_is_sample_free_by_default(mcp) -> None:
    """RUMI-382: the MCP is the agent-facing surface, so bare is its default.

    Agents were scaffolding an app and then burning tokens deleting the worked
    example code before they could add their own. The SDK and REST layers still
    default to samples-on for the CLI's sake, which is precisely why this
    default has to be asserted here rather than assumed to follow from them.
    """
    route = respx.post(f"{BASE}/v1/apps").mock(
        return_value=httpx.Response(200, json={"appName": "trading"})
    )
    await _call(
        mcp,
        "create_app",
        app_name="trading",
        app_dir="/ws",
        package_name="com.example.trading",
        group_id="com.example",
        artifact_prefix="acme",
        rumi_version="4.0.637",
    )
    assert json.loads(route.calls.last.request.content)["includeSamples"] is False


@respx.mock
async def test_create_app_can_opt_back_into_samples(mcp) -> None:
    route = respx.post(f"{BASE}/v1/apps").mock(
        return_value=httpx.Response(200, json={"appName": "trading"})
    )
    await _call(
        mcp,
        "create_app",
        app_name="trading",
        app_dir="/ws",
        package_name="com.example.trading",
        group_id="com.example",
        artifact_prefix="acme",
        rumi_version="4.0.637",
        include_samples=True,
    )
    assert json.loads(route.calls.last.request.content)["includeSamples"] is True


@respx.mock
async def test_add_service_is_sample_free_by_default(mcp) -> None:
    route = respx.post(f"{BASE}/v1/services").mock(
        return_value=httpx.Response(200, json={"name": "api"})
    )
    await _call(mcp, "add_service", app_root="/ws/t", name="api", type="webservice")
    assert json.loads(route.calls.last.request.content)["includeSamples"] is False


@respx.mock
async def test_remove_service_passes_dry_run_flag(mcp) -> None:
    route = respx.delete(f"{BASE}/v1/services/svc").mock(
        return_value=httpx.Response(200, json={"applied": True})
    )
    await _call(mcp, "remove_service", app_root="/ws/t", name="svc", dry_run=True)
    query = _query(route.calls.last.request)
    assert "app_root=/ws/t" in query
    assert "dry_run=true" in query


@respx.mock
async def test_add_handler_nested_path(mcp) -> None:
    route = respx.post(f"{BASE}/v1/services/svc/handlers").mock(
        return_value=httpx.Response(200, json={"applied": True})
    )
    await _call(
        mcp,
        "add_handler",
        app_root="/ws/t",
        service="svc",
        method="onOrder",
        message_type="OrderRequest",
    )
    assert b"onOrder" in route.calls.last.request.content
    assert b"OrderRequest" in route.calls.last.request.content


@respx.mock
async def test_add_message_sends_fields_list(mcp) -> None:
    route = respx.post(f"{BASE}/v1/services/svc/messages").mock(
        return_value=httpx.Response(200, json={"applied": True})
    )
    await mcp.call_tool(
        "add_message",
        {
            "app_root": "/ws/t",
            "service": "svc",
            "name": "PlaceOrder",
            "fields": [{"name": "qty", "type": "int"}],
        },
    )
    body = route.calls.last.request.content
    assert b"PlaceOrder" in body
    assert b"qty" in body


@respx.mock
async def test_add_message_forwards_roe_scope(mcp) -> None:
    route = respx.post(f"{BASE}/v1/services/svc/messages").mock(
        return_value=httpx.Response(200, json={"applied": True})
    )
    await mcp.call_tool(
        "add_message",
        {"app_root": "/ws/t", "service": "svc", "name": "SharedEvent", "scope": "roe"},
    )
    assert "scope=roe" in _query(route.calls.last.request)


@respx.mock
async def test_add_message_entity_routes_to_message_entities_path(mcp) -> None:
    route = respx.post(f"{BASE}/v1/services/svc/message-entities").mock(
        return_value=httpx.Response(200, json={"applied": True})
    )
    await mcp.call_tool(
        "add_message_entity",
        {
            "app_root": "/ws/t",
            "service": "svc",
            "name": "Money",
            "fields": [{"name": "amount", "type": "Long"}],
            "scope": "messages",
        },
    )
    body = route.calls.last.request.content
    assert b"Money" in body
    assert b"amount" in body
    assert "scope=messages" in _query(route.calls.last.request)


@respx.mock
async def test_add_collection_sends_is_and_contains(mcp) -> None:
    route = respx.post(f"{BASE}/v1/services/svc/collections").mock(
        return_value=httpx.Response(200, json={"applied": True})
    )
    await mcp.call_tool(
        "add_collection",
        {"app_root": "/ws/t", "service": "svc", "name": "byId",
         "is_": "StringMap", "contains": "Account"},
    )
    body = route.calls.last.request.content
    assert b'"is": "StringMap"' in body or b'"is":"StringMap"' in body
    assert b"Account" in body


@respx.mock
async def test_remove_state_entity_passes_force_flag(mcp) -> None:
    route = respx.delete(f"{BASE}/v1/services/svc/state-entities/Account").mock(
        return_value=httpx.Response(200, json={"applied": True})
    )
    await _call(mcp, "remove_state_entity", app_root="/ws/t", service="svc", name="Account", force=True)
    assert "force=true" in _query(route.calls.last.request)


@respx.mock
async def test_list_config_fragments_omits_profile_when_none(mcp) -> None:
    route = respx.get(f"{BASE}/v1/config/fragments").mock(
        return_value=httpx.Response(200, json=[])
    )
    await _call(mcp, "list_config_fragments", app_root="/ws/t")
    assert _query(route.calls.last.request) == "app_root=/ws/t"


@respx.mock
async def test_add_config_fragment_posts_scope_and_xml(mcp) -> None:
    route = respx.post(f"{BASE}/v1/config/fragments").mock(
        return_value=httpx.Response(200, json={"applied": True})
    )
    await _call(
        mcp,
        "add_config_fragment",
        app_root="/ws/t",
        scope_path=["buses"],
        xml="<bus name='aux'/>",
    )
    body = route.calls.last.request.content
    assert b"buses" in body
    assert b"<bus name='aux'/>" in body


@respx.mock
async def test_validate_config_hits_validate_endpoint(mcp) -> None:
    respx.post(f"{BASE}/v1/config/validate").mock(
        return_value=httpx.Response(200, json={"ok": True, "errors": []})
    )
    await _call(mcp, "validate_config", app_root="/ws/t")


@respx.mock
async def test_factory_ids_next_returns_int_wrapper(mcp) -> None:
    respx.get(f"{BASE}/v1/factory-ids/next").mock(
        return_value=httpx.Response(200, json={"nextAvailableId": 5})
    )
    await _call(mcp, "next_factory_id", app_root="/ws/t")


@respx.mock
async def test_get_config_returns_raw_xml(mcp) -> None:
    respx.get(f"{BASE}/v1/config").mock(
        return_value=httpx.Response(200, text="<config><buses/></config>")
    )
    await _call(mcp, "get_config", app_root="/ws/t")
