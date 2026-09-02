"""End-to-end: the REAL MCP tool implementations against the REAL REST service.

Every existing MCP test mocks the HTTP layer, so a field-name mismatch between
what the MCP sends and what the REST DTO expects passes the whole suite. This
is the first thing that would catch it.
"""
import asyncio, json, shutil, sys, tempfile, os
from rumi_appbuilder_mcp.server import build_server

BASE = os.environ.get("BASE", "http://127.0.0.1:13200")
ok = fail = 0

def check(label, cond, detail=""):
    global ok, fail
    if cond:
        ok += 1; print(f"  PASS  {label}")
    else:
        fail += 1; print(f"  FAIL  {label}  {detail}")

async def call(mcp, _tool, **kw):
    r = await mcp.call_tool(_tool, kw)
    payload = r[1] if isinstance(r, tuple) else r
    if isinstance(payload, dict) and "result" in payload:
        payload = payload["result"]
    return payload

async def main():
    workspace = tempfile.mkdtemp(prefix="e2e-ws-")
    mcp = build_server(BASE)
    try:
        root = os.path.join(workspace, "e2e")
        os.makedirs(root, exist_ok=True)
        app = await call(mcp, "create_app", app_name="e2e", app_dir=root,
                         package_name="com.e2e", group_id="com.e2e",
                         artifact_prefix="e2e", rumi_version=os.environ.get("RUMI_VERSION", "4.0.660"))
        # create_app puts the app in <app_dir>/<artifact_prefix>-<app_name>.
        root = os.path.join(root, "e2e-e2e")
        check("create_app", os.path.isfile(os.path.join(root, ".rumi")), str(app)[:120])

        await call(mcp, "add_service", app_root=root, name="proc", type="processor",
                   clustered=False, partitions=1)
        check("add_service", True)

        # --- RUMI-412: apply_model, the whole point of this ticket ---
        res = await call(mcp, "apply_model", app_root=root, edits=[
            {"kind": "message", "service": "proc", "name": "PlaceOrder",
             "fields": [{"name": "qty", "type": "Long"}]},
            {"kind": "fields", "service": "proc", "name": "PlaceOrder", "scope": "messages",
             "fields": [{"name": "symbol", "type": "String"}, {"name": "price", "type": "Double"}]},
            {"kind": "state_entity", "service": "proc", "name": "Order",
             "fields": [{"name": "id", "type": "String", "attributes": {"isKey": "true"}}]},
        ])
        check("apply_model applied", res.get("applied") is True, json.dumps(res)[:200])
        check("apply_model reports 3 items", len(res.get("items", [])) == 3, json.dumps(res)[:200])

        msg = await call(mcp, "get_message", app_root=root, service="proc", name="PlaceOrder")
        names = [f.get("name") for f in msg.get("fields", [])]
        check("batch fields really landed", {"qty","symbol","price"} <= set(names), str(names))

        # --- RUMI-412: batch add_field ---
        await call(mcp, "add_message", app_root=root, service="proc", name="Tick",
                   fields=[{"name": "seq", "type": "Long"}])
        await call(mcp, "add_field", app_root=root, service="proc", scope="messages",
                   type="Tick", fields=[{"name": "bid", "type": "Double"},
                                        {"name": "ask", "type": "Double"}])
        tick = await call(mcp, "get_message", app_root=root, service="proc", name="Tick")
        tnames = [f.get("name") for f in tick.get("fields", [])]
        check("add_field batch really landed", {"seq","bid","ask"} <= set(tnames), str(tnames))

        # --- RUMI-411: handler body read/update ---
        await call(mcp, "add_handler", app_root=root, service="proc", method="onOrder",
                   message_type="PlaceOrder", body="long q = message.getQty();")
        h = await call(mcp, "get_handler", app_root=root, service="proc", method="onOrder")
        check("get_handler returns a body", "getQty" in (h.get("body") or ""), str(h)[:160])

        await call(mcp, "update_handler", app_root=root, service="proc", method="onOrder",
                   body="long q = message.getQty();\n        if (q > 0) { }")
        h2 = await call(mcp, "get_handler", app_root=root, service="proc", method="onOrder")
        check("update_handler changed the body", "q > 0" in (h2.get("body") or ""), str(h2)[:160])

        lst = await call(mcp, "list_handlers", app_root=root, service="proc")
        check("list omits bodies by default", all(h.get("body") is None for h in lst), str(lst)[:160])

        # --- RUMI-413: narrowed config read ---
        frags = await call(mcp, "list_config_fragments", app_root=root,
                           scope_path=["xvms", "templates"])
        check("narrowed config read returns only that scope",
              frags and all(f.get("scopePath") == ["xvms","templates"] for f in frags),
              str(frags)[:200])

        # --- rollback, over the real wire ---
        before = await call(mcp, "list_messages", app_root=root, service="proc")
        try:
            await call(mcp, "apply_model", app_root=root, edits=[
                {"kind": "message", "service": "proc", "name": "WouldHaveApplied"},
                {"kind": "fields", "service": "proc", "name": "NoSuchMessage", "scope": "messages",
                 "fields": [{"name": "x", "type": "Long"}]},
            ])
            rolled = False
        except Exception:
            rolled = True
        after = await call(mcp, "list_messages", app_root=root, service="proc")
        check("a failing batch is an error", rolled)
        check("and rolls back over the wire",
              [m.get("name") for m in before] == [m.get("name") for m in after],
              f"{[m.get('name') for m in before]} != {[m.get('name') for m in after]}")
        print(f"\n{ok} passed, {fail} failed")
        return 1 if fail else 0
    finally:
        shutil.rmtree(workspace, ignore_errors=True)

sys.exit(asyncio.run(main()))
