"""Build a model entirely through apply_model, then COMPILE the generated app.

The SDK tests prove the XML is schema-valid; verify-generated-app.sh proves the
templates build. Neither proves that a model written by the new batch path
code-generates and compiles — which is the only thing an agent actually cares
about, and the exact class of failure the verifier's own header warns about:
"the builder reports success, the app does not build, and the agent concludes
its own code is wrong".
"""
import asyncio, os, tempfile, sys
from rumi_appbuilder_mcp.server import build_server

async def main():
    ws = tempfile.mkdtemp(prefix="e2e-compile-")
    d = os.path.join(ws, "app"); os.makedirs(d)
    mcp = build_server(os.environ.get("BASE", "http://127.0.0.1:13200"))

    async def call(t, **kw):
        r = await mcp.call_tool(t, kw)
        return r[1] if isinstance(r, tuple) else r

    await call("create_app", app_name="demo", app_dir=d, package_name="com.demo",
               group_id="com.demo", artifact_prefix="demo", rumi_version=os.environ.get("RUMI_VERSION", "4.0.660"))
    root = os.path.join(d, "demo-demo")
    await call("add_service", app_root=root, name="proc", type="processor",
               clustered=False, partitions=1)

    # The whole model, in ONE call, including every kind the batch supports.
    await call("apply_model", app_root=root, edits=[
        {"kind": "message", "service": "proc", "name": "PlaceOrder", "scope": "messages",
         "fields": [{"name": "qty", "type": "Long"}, {"name": "symbol", "type": "String"}]},
        {"kind": "fields", "service": "proc", "name": "PlaceOrder", "scope": "messages",
         "fields": [{"name": "price", "type": "Double"}, {"name": "side", "type": "String"}]},
        {"kind": "message", "service": "proc", "name": "SharedEvent", "scope": "roe",
         "fields": [{"name": "id", "type": "String"}]},
        {"kind": "state_entity", "service": "proc", "name": "Order",
         "fields": [{"name": "orderId", "type": "String", "attributes": {"isKey": "true"}},
                    {"name": "filled", "type": "Long"}]},
        {"kind": "collection", "service": "proc", "name": "orders",
         "is": "StringMap", "contains": "Order"},
    ])
    # And a handler whose body is written through update_handler (RUMI-411),
    # referencing a field the batch created (RUMI-412).
    await call("add_handler", app_root=root, service="proc", method="onPlaceOrder",
               message_type="PlaceOrder", body="")
    await call("update_handler", app_root=root, service="proc", method="onPlaceOrder",
               body="final long q = message.getQty();\n        final String s = message.getSymbol();\n"
                    "        if (q > 0 && s != null) { }")
    print("MODEL_BUILT", root)
    print(root)
    return root

root = asyncio.run(main())
sys.exit(0)
