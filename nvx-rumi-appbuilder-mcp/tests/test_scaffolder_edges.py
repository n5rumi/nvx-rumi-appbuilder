"""RUMI-431: the rough edges, each one an avoidable round trip.

None of these produced a wrong app. They cost turns, and two of them cost a
delete-and-start-again, which is the expensive kind of avoidable.
"""

from __future__ import annotations

import inspect
import re

from rumi_appbuilder_mcp.server import build_server


def _tool(name):
    server = build_server(base_url="http://test")
    for attr in ("_tool_manager", "_tools"):
        mgr = getattr(server, attr, None)
        if mgr is None:
            continue
        tools = getattr(mgr, "_tools", mgr)
        if isinstance(tools, dict) and name in tools:
            return tools[name]
    raise AssertionError(f"{name} is not registered")


def _description(name) -> str:
    t = _tool(name)
    return getattr(t, "description", None) or (t.fn.__doc__ or "")


def test_create_app_accepts_an_explicit_artifact_id():
    """I-02: the derived id stutters, and was recoverable only by starting again.

    app_name="Paywhere Prospect Portal" with artifact_prefix="paywhere" derived
    `paywhere-paywhereprospectportal`.
    """
    fn = _tool("create_app").fn
    params = inspect.signature(fn).parameters
    assert "artifact_id" in params, (
        "create_app cannot be told the artifact id, so a stuttering derivation "
        "is only fixable by deleting the app"
    )
    assert params["artifact_id"].default is None, (
        "it must be optional -- deriving is the right default"
    )
    d = _description("create_app")
    assert "artifact_id" in d and "stutter" in d.lower(), (
        "the description must say what goes wrong when it is left unset, or "
        "nobody sets it"
    )
    assert "READ IT" in d or "read it" in d.lower(), (
        "the derived value is in the result; the description has to say so, "
        "because the agent that hit this had the value and did not look"
    )


def test_field_writing_tools_warn_about_reserved_names():
    """I-04: ~70 reserved names, discoverable only by being rejected.

    Too many to list in a description read on every turn, so the RULE plus the
    names a person naming a business field would actually reach for. The batch
    that hit `message` lost all 15 of its edits.
    """
    for tool in ("apply_model", "add_field"):
        d = _description(tool)
        assert "RESERVED FIELD NAMES" in d, f"{tool} does not mention reserved names"
        assert "RogNode" in d, f"{tool} does not say WHY they are reserved"
        for name in ("message", "metadata", "attachment"):
            assert f"`{name}`" in d, f"{tool} does not name {name!r}, which people pick"
        assert "whole call" in d.lower() or "WHOLE call" in d, (
            f"{tool} does not say one reserved name costs every edit in the batch"
        )


def test_the_reserved_rule_matches_the_sdk():
    """The names the description claims are reserved must really be reserved.

    A description listing a name the validator allows is worse than silence: it
    sends people renaming fields that were fine.
    """
    import pathlib
    sdk = (pathlib.Path(__file__).resolve().parents[2]
           / "nvx-rumi-appbuilder-sdk/src/main/java/com/neeve/appbuilder/ReservedNames.java")
    actual = set(re.findall(r'"([A-Za-z][A-Za-z0-9]*)"', sdk.read_text()))
    claimed = set(re.findall(r'`([a-zA-Z][a-zA-Z0-9]*)`', _description("apply_model")))
    # only the names claimed inside the reserved paragraph
    para = _description("apply_model").split("RESERVED FIELD NAMES", 1)[1]
    claimed = set(re.findall(r'`([a-z][a-zA-Z0-9]*)`', para))
    bogus = {c for c in claimed if c not in actual and c not in {"payloadMessage", "requestMetadata"}}
    assert not bogus, f"the description calls these reserved and the SDK does not: {sorted(bogus)}"


def test_create_app_names_the_class_finder_it_scaffolds():
    """I-06: the scaffold carries find-class.sh, so the description must say so.

    An affordance the agent does not know about is one it does not use -- it goes
    back to scanning every jar, which is the thing the script exists to stop.
    Same reasoning as the run-in-process.sh sentence next to it.
    """
    flat = " ".join(_description("create_app").split())
    assert "find-class.sh" in flat, (
        "create_app's description does not mention find-class.sh, so an agent "
        "hitting an unresolved Rumi type will scan the jars by hand instead"
    )
    assert "do not scan the jars yourself" in flat.lower(), (
        "naming the script is not enough -- the description has to take back the "
        "jar scan, which is what the reporting session actually did"
    )
