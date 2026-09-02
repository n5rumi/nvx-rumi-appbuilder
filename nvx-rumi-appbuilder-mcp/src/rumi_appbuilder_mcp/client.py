"""HTTP client for the Rumi App Builder REST service.

Every MCP tool goes through this one class. Handles the error envelope
convention — on any non-2xx status the REST service returns
``{"error": {"code": "...", "description": "..."}}``; we unwrap that
into :class:`RestError` so MCP callers see a real exception instead of
a generic HTTP-status failure.
"""

from __future__ import annotations

import os
from typing import Any

import httpx


class RestError(RuntimeError):
    """Raised when the REST service returns an error envelope."""

    def __init__(self, status: int, code: str, description: str):
        super().__init__(f"{code}: {description} (HTTP {status})")
        self.status = status
        self.code = code
        self.description = description


class AppBuilderClient:
    """Thin wrapper over ``httpx.Client`` for the App Builder REST service."""

    def __init__(self, base_url: str | None = None, *, timeout: float = 30.0):
        self.base_url = (
            base_url
            or os.environ.get("RUMI_APPBUILDER_REST_URL")
            or "http://127.0.0.1:3200"
        ).rstrip("/")
        self._http = httpx.Client(base_url=self.base_url, timeout=timeout)

    def close(self) -> None:
        self._http.close()

    def __enter__(self) -> "AppBuilderClient":  # pragma: no cover — convenience only
        return self

    def __exit__(self, *args: object) -> None:  # pragma: no cover
        self.close()

    # ---- HTTP verbs --------------------------------------------------

    def get(self, path: str, params: dict[str, Any] | None = None) -> Any:
        return self._decode(self._http.get(path, params=_clean(params)))

    def get_text(self, path: str, params: dict[str, Any] | None = None) -> str:
        resp = self._http.get(path, params=_clean(params))
        if resp.status_code >= 400:
            raise _from_response(resp)
        return resp.text

    def post(
        self,
        path: str,
        *,
        params: dict[str, Any] | None = None,
        json: Any | None = None,
    ) -> Any:
        return self._decode(self._http.post(path, params=_clean(params), json=json))

    def put(
        self,
        path: str,
        *,
        params: dict[str, Any] | None = None,
        json: Any | None = None,
    ) -> Any:
        return self._decode(self._http.put(path, params=_clean(params), json=json))

    def delete(
        self,
        path: str,
        *,
        params: dict[str, Any] | None = None,
        json: Any | None = None,
    ) -> Any:
        return self._decode(
            self._http.request("DELETE", path, params=_clean(params), json=json)
        )

    # ---- helpers -----------------------------------------------------

    @staticmethod
    def _decode(resp: httpx.Response) -> Any:
        if resp.status_code >= 400:
            raise _from_response(resp)
        if resp.status_code == 204 or not resp.content:
            return None
        return resp.json()


def _clean(params: dict[str, Any] | None) -> dict[str, Any] | None:
    """Drop keys whose value is ``None`` so httpx doesn't serialise them."""
    if not params:
        return params
    return {k: v for k, v in params.items() if v is not None}


def _from_response(resp: httpx.Response) -> RestError:
    # Try the structured envelope; fall back to a generic one if the
    # service sent something else (e.g. nginx before the app is up).
    try:
        body = resp.json()
        err = body.get("error") if isinstance(body, dict) else None
        if isinstance(err, dict):
            return RestError(
                resp.status_code,
                str(err.get("code", "UnknownError")),
                str(err.get("description", resp.text)),
            )
    except Exception:
        pass
    return RestError(resp.status_code, "UnknownError", resp.text or "request failed")
