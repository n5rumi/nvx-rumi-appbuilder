"""Per-session tool-usage accounting (RUMI-415).

Two things are worth pinning: the counting itself, and that wrapping every
tool to count it did not damage the schemas FastMCP derives from the
undecorated functions. Removing the `functools.wraps` turns 32 tests red
across routing, coverage and usage, so the failure is loud rather than silent;
these two just name the cause directly instead of leaving it to be inferred
from thirty routing failures.
"""

from __future__ import annotations

import httpx
import pytest
import respx

from rumi_appbuilder_mcp.server import build_server
from rumi_appbuilder_mcp.usage import ToolUsage


@pytest.fixture
def mcp():
    return build_server("http://test")


# ---- the counter on its own -----------------------------------------


def test_report_is_empty_before_anything_is_called() -> None:
    u = ToolUsage()
    u.register("list_apps")
    u.register("get_handler")
    r = u.report()
    assert r["tools_registered"] == 2
    assert r["tools_called"] == 0
    assert r["total_calls"] == 0
    assert r["by_tool"] == {}
    assert r["never_called"] == ["get_handler", "list_apps"]


def test_counts_accumulate_and_never_called_shrinks() -> None:
    u = ToolUsage()
    for name in ("list_apps", "get_handler", "add_service"):
        u.register(name)
    u.record("list_apps")
    u.record("list_apps")
    u.record("add_service")
    r = u.report()
    assert r["by_tool"] == {"list_apps": 2, "add_service": 1}
    assert r["tools_called"] == 2
    assert r["total_calls"] == 3
    assert r["never_called"] == ["get_handler"]


def test_by_tool_is_ordered_by_count_then_name() -> None:
    u = ToolUsage()
    for name in ("b_tool", "a_tool", "c_tool"):
        u.register(name)
    u.record("c_tool")
    for _ in range(3):
        u.record("a_tool")
    u.record("b_tool")
    u.record("b_tool")
    assert list(u.report()["by_tool"]) == ["a_tool", "b_tool", "c_tool"]


def test_counted_preserves_the_wrapped_signature_and_doc() -> None:
    import inspect

    u = ToolUsage()

    def add_service(app_root: str, name: str, type: str = "processor") -> str:
        """Add a service."""
        return "ok"

    wrapped = u.counted(add_service)
    assert wrapped.__name__ == "add_service"
    assert wrapped.__doc__ == "Add a service."
    assert inspect.signature(wrapped) == inspect.signature(add_service)
    assert wrapped("a", "b") == "ok"
    assert u.report()["by_tool"] == {"add_service": 1}


# ---- wired into the real server --------------------------------------


@pytest.mark.asyncio
async def test_report_tool_is_registered(mcp) -> None:
    names = {t.name for t in await mcp.list_tools()}
    assert "tool_usage_report" in names


@pytest.mark.asyncio
async def test_wrapping_did_not_flatten_the_tool_schemas(mcp) -> None:
    """Schemas still come from the real signatures, not the wrapper.

    Swept across EVERY registered tool rather than sampled. An earlier version
    of this test made its "no args/kwargs leaked" assertion against
    `tool_usage_report` -- the one tool that never goes through `counted()` --
    so it stayed green with `functools.wraps` removed, which is the single
    regression it existed to catch.
    """
    tools = {t.name: t for t in await mcp.list_tools()}

    add_service = tools["add_service"].inputSchema
    assert {"app_root", "name", "type"} <= set(add_service["properties"])

    flattened = sorted(
        name
        for name, tool in tools.items()
        if {"args", "kwargs"} & set((tool.inputSchema or {}).get("properties", {}))
    )
    assert not flattened, f"these tools registered an untyped wrapper: {flattened}"


@respx.mock
@pytest.mark.asyncio
async def test_calling_a_tool_shows_up_in_the_report(mcp) -> None:
    respx.route(host="test").mock(return_value=httpx.Response(200, json=[]))

    before = (await mcp.call_tool("tool_usage_report", {}))[1]
    assert before["by_tool"].get("list_apps") is None

    await mcp.call_tool("list_apps", {"under": "/tmp"})
    await mcp.call_tool("list_apps", {"under": "/tmp"})

    after = (await mcp.call_tool("tool_usage_report", {}))[1]
    assert after["by_tool"]["list_apps"] == 2
    assert after["total_calls"] >= 2


@pytest.mark.asyncio
async def test_the_report_tool_is_not_in_the_surface_it_reports_on(mcp) -> None:
    """It is an instrument, so it stays out of both halves of the ratio."""
    report = (await mcp.call_tool("tool_usage_report", {}))[1]
    registered = {t.name for t in await mcp.list_tools()}

    assert "tool_usage_report" not in report["never_called"]
    assert "tool_usage_report" not in report["by_tool"]
    # Counted surface is every registered tool except the instrument itself.
    assert report["tools_registered"] == len(registered) - 1


@pytest.mark.asyncio
async def test_an_async_tool_is_awaited_and_counted() -> None:
    """A sync wrapper around an async tool is a silent wrong answer.

    `inspect.signature` follows `__wrapped__`; `inspect.iscoroutinefunction`
    does not. Wrapped with a plain sync wrapper, an async tool registers as
    is_async=False, FastMCP calls it without awaiting, and pydantic is handed a
    coroutine object as the result -- so the tool "succeeds" having done
    nothing. No async tool exists yet, which is exactly why this is pinned now.
    """
    import inspect

    u = ToolUsage()

    async def get_thing(app_root: str) -> str:
        return "done"

    wrapped = u.counted(get_thing)
    assert inspect.iscoroutinefunction(wrapped), "async tool wrapped as sync"
    assert inspect.signature(wrapped) == inspect.signature(get_thing)
    assert await wrapped("/tmp") == "done"
    assert u.report()["by_tool"] == {"get_thing": 1}
