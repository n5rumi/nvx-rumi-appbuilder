"""Entry point for the Rumi App Builder MCP server.

Supports two transports:

- ``stdio`` (default) — what external coding assistants (Claude Code,
  Cursor) use. The process reads MCP JSON-RPC on stdin and writes it
  on stdout.
- ``streamable-http`` — what the Rumi Agent consumes on its sandbox;
  binds to ``http://127.0.0.1:3201`` by default.

Environment:

- ``RUMI_APPBUILDER_REST_URL`` — base URL for the REST service
  (default ``http://127.0.0.1:3200``).
- ``RUMI_APPBUILDER_MCP_PORT`` — port for the HTTP transport
  (default ``3201``).
- ``RUMI_APPBUILDER_MCP_HOST`` — bind host for the HTTP transport
  (default ``127.0.0.1``).
"""

from __future__ import annotations

import argparse
import os
import sys

from .server import build_server


def main(argv: list[str] | None = None) -> int:
    parser = argparse.ArgumentParser(
        prog="rumi-appbuilder-mcp",
        description="MCP server wrapping the Rumi App Builder REST service.",
    )
    parser.add_argument(
        "--transport",
        choices=("stdio", "streamable-http", "sse"),
        default=os.environ.get("RUMI_APPBUILDER_MCP_TRANSPORT", "stdio"),
        help="Transport to use (default: stdio).",
    )
    parser.add_argument(
        "--host",
        default=os.environ.get("RUMI_APPBUILDER_MCP_HOST", "127.0.0.1"),
        help="Bind host for HTTP/SSE transports (default: 127.0.0.1).",
    )
    parser.add_argument(
        "--port",
        type=int,
        default=int(os.environ.get("RUMI_APPBUILDER_MCP_PORT", "3201")),
        help="Bind port for HTTP/SSE transports (default: 3201).",
    )
    parser.add_argument(
        "--rest-url",
        default=os.environ.get("RUMI_APPBUILDER_REST_URL"),
        help="REST service base URL (default: $RUMI_APPBUILDER_REST_URL or http://127.0.0.1:3200).",
    )

    args = parser.parse_args(argv)
    mcp = build_server(base_url=args.rest_url)

    if args.transport == "stdio":
        mcp.run(transport="stdio")
    else:
        # FastMCP's streamable-http / sse transports read host/port off
        # settings; expose them here for operators. Keeping the two
        # transport names the SDK supports so we don't have to re-map.
        mcp.settings.host = args.host
        mcp.settings.port = args.port
        mcp.run(transport=args.transport)

    return 0


if __name__ == "__main__":  # pragma: no cover
    sys.exit(main())
