"""RUMI-426: what the scaffold produces must be in the description that offers it.

The tool description is the ONLY surface an agent reads before scaffolding. The
App Builder has no pages in `nvx-rumi-docs`, so there is no second place to look:
the descriptions, the generated README and PROJECT.md are the entire
documentation of this product.

RUMI-426 shipped a supported runner and did not touch the MCP. `create_app` then
told an agent where the app landed and nothing about how to run it -- so it would
keep inventing a launcher, which is the behaviour RUMI-426 was filed to stop.
Three separate build sessions invented one; a fourth would have too.

That is also the shape of RUMI-415: most of the tool surface was never reached
for, and its note says plainly that a prompt rule is not a substitute for a tool
that returns the answer. A capability the description omits does not exist.
"""

from __future__ import annotations

import pathlib
import re

from rumi_appbuilder_mcp.server import build_server


def _description(tool_name: str) -> str:
    server = build_server(base_url="http://test")
    for attr in ("_tool_manager", "_tools"):
        mgr = getattr(server, attr, None)
        if mgr is None:
            continue
        tools = getattr(mgr, "_tools", mgr)
        if isinstance(tools, dict) and tool_name in tools:
            t = tools[tool_name]
            return getattr(t, "description", None) or (t.fn.__doc__ or "")
    raise AssertionError(f"{tool_name} is not registered")


def test_create_app_names_the_runner_it_scaffolds():
    d = _description("create_app").lower()
    assert "run-in-process.sh" in d, (
        "create_app scaffolds a runner and does not mention it; an agent reads "
        "this description and nothing else before scaffolding"
    )
    assert "mvn install" in d, (
        "the reason a hand-rolled launcher fails is the classpath needing "
        "`mvn install`; without that, inventing one looks reasonable"
    )


def test_create_app_does_not_call_the_runner_local():
    """`local` already means the Docker deployment that proves the cloud profile.

    The agent reached for the word "local" unprompted in three separate builds,
    so the description has to spend a sentence taking it back.
    """
    # Whitespace-collapsed before matching. The previous form allowed a line
    # break at exactly one position, so re-wrapping the docstring -- same
    # sentence, same guarantee -- failed a test whose subject is the meaning.
    d = _description("create_app")
    flat = " ".join(_description("create_app").split())
    assert re.search(r'never call the in-process runner "local"', flat, re.I), (
        "the description must say the runner is not called 'local'"
    )
    assert "rumi cloud local" in d, (
        "it must say what `local` DOES mean, or the warning is just a prohibition"
    )


def test_the_generated_readme_and_the_description_agree():
    """The README names all three run mechanisms; the description names three too.

    Checked asymmetrically on purpose, and said out loud because the earlier
    docstring claimed all three were cross-checked on both sides while only two
    were. The README is the reference and spells out "cloud deployment"; the
    description is read on every turn and earns its brevity by saying "`cloud` is
    the real deployment" instead. So the third is matched on the word that
    actually distinguishes it.
    """
    tmpl = (pathlib.Path(__file__).resolve().parents[2]
            / "nvx-rumi-appbuilder-sdk/src/main/resources/templates/maven/app"
            / "{{ParentArtifactId}}" / "README.md")
    readme = tmpl.read_text().lower()
    d = _description("create_app").lower()
    for mechanism in ("in-process", "rumi cloud local", "cloud deployment"):
        assert mechanism in readme, f"README does not name {mechanism!r}"
    for mechanism in ("in-process", "rumi cloud local"):
        assert mechanism in d, f"create_app's description does not name {mechanism!r}"
    # The third, on its distinguishing word rather than the README's phrasing.
    assert "cloud" in d and "real deployment" in d, (
        "create_app's description does not name the third mechanism (cloud); a "
        "reader told about two of three will reach for the wrong one"
    )
