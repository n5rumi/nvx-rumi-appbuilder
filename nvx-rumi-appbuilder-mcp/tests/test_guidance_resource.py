"""The server carries its own guidance, instead of two agent prompts doing it.

SUT-187 proposed stating the web-tier rules in the Sutra and Support Agent
prompts, because neither mentioned the REST endpoint tools and the agent
hand-wrote 154 KB of web tier instead of calling them. That fix works and has
two costs: the text lives in two other repos, and it is paid for on every turn
of every conversation whether or not the turn builds a web tier.

A resource is the alternative the current MCP spec affords: read on demand, by
the client, from the server that owns the surface it describes.
"""

from __future__ import annotations

import pathlib

import pytest

from rumi_appbuilder_mcp.server import build_server

pytestmark = pytest.mark.anyio

URI = "rumi-dev://guide/web-tier"
REPO = pathlib.Path(__file__).resolve().parents[2]
APP_README = (REPO / "nvx-rumi-appbuilder-sdk/src/main/resources/templates/maven/app"
              / "{{ParentArtifactId}}" / "README.md")


@pytest.fixture
def mcp():
    return build_server(base_url="http://test")


async def _body(mcp) -> str:
    chunks = list(await mcp.read_resource(URI))
    return "".join(getattr(c, "content", "") or "" for c in chunks)


async def test_the_guide_is_discoverable(mcp) -> None:
    """Listed, named and typed -- a client that never read a prompt can find it."""
    rs = {str(r.uri): r for r in await mcp.list_resources()}
    assert URI in rs, f"the web-tier guide is not listed: {sorted(rs)}"
    r = rs[URI]
    assert r.mimeType == "text/markdown"
    assert r.description and "by hand" in r.description, (
        "the description is what a client reads when deciding whether to open "
        "it; it has to say what problem the guide prevents"
    )


async def test_the_guide_names_the_tools_and_the_missed_step(mcp) -> None:
    body = await _body(mcp)
    for tool in ("add_rest_endpoint", "add_service_client", "add_operation"):
        assert tool in body, f"the guide does not name {tool}"
    assert "FIRST" in body, (
        "add_service_client being required FIRST is the step most often missed; "
        "listing it without the ordering does not prevent the failure"
    )


async def test_the_guide_states_what_is_NOT_covered(mcp) -> None:
    """The ~48 KB that has no tool and no template shape.

    An agent that does not know this category exists hunts for a command that
    is not there, which is its own kind of wasted turn.
    """
    body = (await _body(mcp)).lower()
    for absent in ("static assets", "streaming", "filters", "projections"):
        assert absent in body, f"the not-covered list omits {absent!r}"


async def test_the_guide_carries_the_extend_not_replace_rule(mcp) -> None:
    body = await _body(mcp)
    assert "never replace them" in body
    assert "127.0.0.1" in body and "502" in body, (
        "the consequence is what makes the rule stick: a replaced HttpServer "
        "reintroduces a loopback bind and the preview returns 502"
    )


async def test_the_guide_points_at_the_README_rather_than_copying_it(mcp) -> None:
    """⚠️ The anti-drift check, and the reason this test file exists.

    The full covered/not-covered table lives in the generated app's README
    (RUMI-417). Copying it here would recreate exactly the duplication this
    resource exists to avoid -- two copies of one fact, in two repos, free to
    diverge. So the guide is a summary that POINTS at the README, and the two
    must agree on the load-bearing claims.
    """
    body = await _body(mcp)
    assert "README" in body, "the guide must send the reader to the fuller source"

    assert APP_README.exists(), f"the README template moved: {APP_README}"
    readme = APP_README.read_text(encoding="utf-8")

    # The claims that must not diverge. Checked by substance, not by wording:
    # the two documents are written for different readers and should not be
    # expected to match verbatim.
    for claim in ("static assets", "auth filters", "in-memory projection"):
        in_guide = claim.split()[0].lower() in body.lower()
        in_readme = claim.split()[0].lower() in readme.lower()
        assert in_guide == in_readme, (
            f"the guide and the generated README disagree about {claim!r}: "
            f"guide={in_guide} readme={in_readme}. One of them has drifted, "
            f"which is the failure this resource was created to prevent."
        )
