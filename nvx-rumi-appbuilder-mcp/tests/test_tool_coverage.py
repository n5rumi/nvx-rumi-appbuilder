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


# The one tool that is deliberately local. It reports on the app-building
# surface rather than being part of it, and what is worth counting is which
# TOOL the agent reached for — REST sees only endpoints, several of which back
# more than one tool. Named explicitly so the guard below keeps its teeth for
# everything else; a second entry here needs the same justification.
LOCAL_ONLY_TOOLS: set[str] = {"tool_usage_report"}


@respx.mock
async def test_every_registered_tool_reaches_the_rest_service(mcp) -> None:
    """No registered tool is a dead end.

    Any tool that never issues a request is either unwired or quietly doing
    its own thing — both of which defeat the point of the MCP layer being a
    thin, uniform pass-through to REST. The sole exception is listed in
    LOCAL_ONLY_TOOLS above.
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
        if tool.name in LOCAL_ONLY_TOOLS:
            continue
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

    # "update_" and "apply_" belong here for the same reason as the rest: a
    # tool that says it changes something and issues a GET is a bug. Each was
    # missing when its first tool arrived - update_handler (RUMI-411) and
    # apply_model (RUMI-412) - so each would have been skipped by this guard.
    # A new verb needs adding here; the list cannot derive itself.
    prefixes = ("add_", "apply_", "create_", "update_", "remove_", "delete_", "rename_", "deprecate_")
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


# Tools that change nothing and POST anyway, each because it SENDS a document
# rather than because it writes one. Deliberately a short, named list: the
# moment it grows without a reason beside each entry, readOnlyHint on this
# surface stops meaning anything.
_READ_ONLY_POSTS = {
    # Validates a config and returns findings; writes nothing. POSTs because
    # the app root and options go in a body rather than a query string.
    "validate_config",
}


async def test_every_tool_is_annotated(mcp) -> None:
    """A client sees name, description and schema -- and nothing about safety.

    That is the gap behind RUMI-415 ("most of the tool surface is never reached
    for"): an agent with 59 undifferentiated tools cannot tell which are safe to
    try from which delete a service, so it falls back to whatever the prompt
    happened to name. ``readOnlyHint`` is what makes the surface explorable
    without being told about it.
    """
    missing = [t.name for t in await _registered_tools(mcp) if t.annotations is None]
    assert not missing, (
        "these tools carry no annotations, so a client cannot tell a read from "
        "a delete:\n  " + "\n  ".join(missing)
    )


@respx.mock
async def test_read_only_annotations_match_the_wire(mcp) -> None:
    """The annotation is verified against BEHAVIOUR, not against the name.

    Annotations are derived from the naming convention, which makes them cheap
    and keeps 59 of them from drifting -- but it also means a misnamed tool
    would ship a LIE, and a wrong ``readOnlyHint`` is worse than none: it
    invites a client to call something freely that writes to the user's app.

    ⚠️ ``readOnlyHint`` is SEMANTIC -- "does not modify its environment" -- and
    is not the same as "issues a GET". The first version of this test asserted
    read-only implies GET and immediately failed on ``validate_config``, which
    changes nothing and POSTs because it sends a document. So the rule is the
    one that actually holds: a read-only tool must never issue a method that is
    unambiguously mutating, and a read-only POST has to be named here with a
    reason. That is weaker than the wire check I first wrote, and it is true,
    which the stronger version was not.
    """
    route = respx.route(host="test").mock(return_value=httpx.Response(200, json={}))

    lying: list[str] = []
    observed = 0
    for tool in await _registered_tools(mcp):
        ann = tool.annotations
        if ann is None or not ann.readOnlyHint:
            continue
        before = route.call_count
        try:
            await mcp.call_tool(tool.name, _arguments_for(tool))
        except Exception:  # noqa: BLE001 - reachability is another test's job
            pass
        if route.call_count == before:
            continue  # never reached HTTP; nothing observed, nothing to judge
        observed += 1
        method = route.calls.last.request.method
        if method in {"PUT", "PATCH", "DELETE"}:
            lying.append(f"{tool.name} is annotated readOnlyHint but issued {method}")
        elif method == "POST" and tool.name not in _READ_ONLY_POSTS:
            lying.append(
                f"{tool.name} is annotated readOnlyHint and POSTs. If it really "
                f"changes nothing, add it to _READ_ONLY_POSTS with the reason; "
                f"otherwise the annotation is wrong"
            )

    assert not lying, (
        "these annotations contradict what the tool does on the wire:\n  "
        + "\n  ".join(lying)
    )
    # NOT vacuous. Without @respx.mock no call reaches the wire, every tool is
    # skipped, and a deliberately wrong annotation passes -- which is how this
    # test first shipped. The floor is deliberately well under the real count
    # (26 of 27 at the time of writing) so it fails on a broken harness without
    # failing on a legitimately added tool.
    assert observed >= 20, (
        f"only {observed} read-only tools reached the HTTP layer, so this test "
        f"verified almost nothing -- the mock is probably not active"
    )


async def test_destructive_tools_are_annotated_destructive(mcp) -> None:
    """The other direction: a remove_ tool must not look harmless.

    Checked separately from the read-only case because the failure is the
    opposite shape -- not a client calling something dangerous freely, but a
    client with no way to know it should confirm first.
    """
    wrong = [
        t.name for t in await _registered_tools(mcp)
        if t.name.startswith(("remove_", "delete_"))
        and not (t.annotations and t.annotations.destructiveHint)
    ]
    assert not wrong, (
        "these tools delete things and are not annotated destructive:\n  "
        + "\n  ".join(wrong)
    )


async def test_no_tool_claims_an_open_world(mcp) -> None:
    """Every tool here acts on one app directory on this machine.

    ``openWorldHint`` true would tell a client the tool reaches an open-ended
    external system, which would be false for all 59 and would discourage a
    client from retrying something that is in fact local and deterministic.
    """
    wrong = [
        t.name for t in await _registered_tools(mcp)
        if t.annotations and t.annotations.openWorldHint
    ]
    assert not wrong, "these claim an open world and do not have one: " + ", ".join(wrong)
