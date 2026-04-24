"""MCP server wrapping the Rumi App Builder REST service.

Every tool here is a typed HTTP call to one REST endpoint — no logic
lives in this module that isn't already in the REST service (which in
turn delegates to the App Builder SDK).
"""

__all__ = ["__version__"]
__version__ = "1.0.0.dev0"
