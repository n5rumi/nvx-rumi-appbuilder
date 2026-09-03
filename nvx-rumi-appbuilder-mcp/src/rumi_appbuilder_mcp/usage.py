"""Per-session tool-usage accounting for the Dev MCP (RUMI-415).

Adoption of the tool surface could previously only be reconstructed from a
transcript after the fact, which is how a ten-hour build finished before anyone
noticed that 9 of 47 tools had been called and every read tool zero times. This
records it as it happens, so the question is answerable during a session rather
than archaeologically after one.

The counters live in the MCP process, not in the REST service: the thing worth
counting is *which tool the agent reached for*, and REST sees only endpoints,
several of which back more than one tool. That makes this the one part of the
MCP layer that is deliberately not a pass-through to REST.
"""

from __future__ import annotations

import functools
import inspect
import time
from collections import Counter
from typing import Any, Callable, TypeVar

F = TypeVar("F", bound=Callable[..., Any])


def _now() -> str:
    return time.strftime("%Y-%m-%dT%H:%M:%SZ", time.gmtime())


class ToolUsage:
    """Counts calls per tool name for the life of this server process.

    A "session" here is the process, which outlives any one conversation, so
    the report carries `started_at` and callers scoping to a single build
    should take a report at each end and diff `by_tool`. Making the report
    itself resettable was rejected: a read that mutates shared state races
    every other consumer of the same server.
    """

    def __init__(self) -> None:
        self._counts: Counter[str] = Counter()
        self._registered: set[str] = set()
        self.started_at: str = _now()

    # -- registration ---------------------------------------------------

    def register(self, name: str) -> None:
        """Record that a tool exists, so it can be reported as never called."""
        self._registered.add(name)

    def counted(self, fn: F) -> F:
        """Wrap a tool function so each call is recorded.

        `functools.wraps` sets `__wrapped__`, which `inspect.signature`
        follows, so FastMCP still derives the tool's schema from the original
        signature and docstring. Drop the `wraps` and every tool registers as
        untyped `*args/**kwargs`: verified by removing it, which turns 32 tests
        red across routing, coverage and usage. That blast radius is the point
        — the failure is loud, so this does not need a guard of its own.

        The async branch is NOT speculative tidiness. `inspect.signature`
        follows `__wrapped__`, but `inspect.iscoroutinefunction` does not — so
        a sync wrapper around an `async def` tool registers as `is_async=False`,
        and FastMCP calls it and hands pydantic an un-awaited coroutine. The
        REST call never happens and the tool "succeeds" with a coroutine object.
        Every tool here is sync today, so the trap only springs for whoever
        adds the first async one, which is precisely why it is closed now.
        """

        if inspect.iscoroutinefunction(fn):

            @functools.wraps(fn)
            async def async_wrapper(*args: Any, **kwargs: Any) -> Any:
                self._counts[fn.__name__] += 1
                return await fn(*args, **kwargs)

            return async_wrapper  # type: ignore[return-value]

        @functools.wraps(fn)
        def wrapper(*args: Any, **kwargs: Any) -> Any:
            self._counts[fn.__name__] += 1
            return fn(*args, **kwargs)

        return wrapper  # type: ignore[return-value]

    # -- reporting ------------------------------------------------------

    def record(self, name: str) -> None:
        """Record a call directly. Only needed by tests and by callers that
        register a tool without going through `counted`."""
        self._counts[name] += 1

    def report(self) -> dict[str, Any]:
        """Which tools were called, how often, and which never were.

        `never_called` is the actionable half — the finding that started this
        was not that some tool was slow, it was that whole families of read
        tools were never reached for at all.
        """
        called = {
            name: count
            for name, count in sorted(
                self._counts.items(), key=lambda kv: (-kv[1], kv[0])
            )
            if count
        }
        return {
            "started_at": self.started_at,
            "generated_at": _now(),
            "tools_registered": len(self._registered),
            "tools_called": len(called),
            "total_calls": sum(called.values()),
            "by_tool": called,
            "never_called": sorted(self._registered - set(called)),
        }
