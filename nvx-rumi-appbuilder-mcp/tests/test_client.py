"""Unit tests for the REST client wrapper."""

from __future__ import annotations

import httpx
import pytest
import respx

from rumi_appbuilder_mcp.client import AppBuilderClient, RestError


@respx.mock
def test_get_decodes_json_body() -> None:
    respx.get("http://test/v1/apps").mock(
        return_value=httpx.Response(200, json=["/a", "/b"])
    )
    with AppBuilderClient("http://test") as c:
        assert c.get("/v1/apps") == ["/a", "/b"]


@respx.mock
def test_get_text_returns_raw_body() -> None:
    respx.get("http://test/v1/config").mock(
        return_value=httpx.Response(200, text="<config/>")
    )
    with AppBuilderClient("http://test") as c:
        assert c.get_text("/v1/config") == "<config/>"


@respx.mock
def test_error_envelope_unwraps_to_rest_error() -> None:
    respx.get("http://test/v1/services/nope").mock(
        return_value=httpx.Response(
            404, json={"error": {"code": "NotFound", "description": "service not found: nope"}}
        )
    )
    with AppBuilderClient("http://test") as c:
        with pytest.raises(RestError) as exc:
            c.get("/v1/services/nope")
    assert exc.value.status == 404
    assert exc.value.code == "NotFound"
    assert "nope" in exc.value.description


@respx.mock
def test_none_params_are_stripped() -> None:
    # URL with no query string — confirming profile=None doesn't become "profile=".
    route = respx.get("http://test/v1/config/fragments").mock(
        return_value=httpx.Response(200, json=[])
    )
    with AppBuilderClient("http://test") as c:
        c.get("/v1/config/fragments", {"app_root": "/a", "profile": None})
    from urllib.parse import unquote
    assert unquote(route.calls.last.request.url.query.decode()) == "app_root=/a"


@respx.mock
def test_204_returns_none() -> None:
    respx.delete("http://test/v1/resource").mock(
        return_value=httpx.Response(204)
    )
    with AppBuilderClient("http://test") as c:
        assert c.delete("/v1/resource") is None


@respx.mock
def test_non_envelope_error_still_raises() -> None:
    respx.get("http://test/v1/apps").mock(
        return_value=httpx.Response(503, text="Service Unavailable")
    )
    with AppBuilderClient("http://test") as c:
        with pytest.raises(RestError) as exc:
            c.get("/v1/apps")
    assert exc.value.status == 503
    assert exc.value.code == "UnknownError"
