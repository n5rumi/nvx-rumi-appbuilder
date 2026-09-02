/**
 * Copyright 2022 N5 Technologies, Inc
 *
 * This product includes software developed at N5 Technologies, Inc
 * (http://www.n5corp.com/) as well as software licenced to N5 Technologies,
 * Inc under one or more contributor license agreements. See the NOTICE
 * file distributed with this work for additional information regarding
 * copyright ownership.
 *
 * N5 Technologies licenses this file to you under the Apache License,
 * Version 2.0 (the "License"); you may not use this file except in compliance
 * with the License.  You may obtain a copy of the License at:
 *
 *     http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */
package com.neeve.appbuilder.rest;

import org.junit.After;
import org.junit.Before;
import org.junit.Test;

import java.io.IOException;
import java.net.URI;
import java.net.URLEncoder;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Comparator;
import java.util.stream.Stream;

import static org.junit.Assert.*;

/**
 * End-to-end integration of the full endpoint surface: boots the real
 * Jetty + Jersey stack, creates a Rumi app via {@code POST /v1/apps},
 * then exercises every other resource against that real scaffolded app.
 *
 * <p>Slower than unit tests (JavaParser + template extraction on every
 * run) but catches exactly the regressions unit tests can't: endpoint
 * wiring, DTO round-trip shape, error envelope mapping, and SDK
 * contract drift from the REST surface.
 */
public class EndpointIntegrationTest {
    private HttpServer server;
    private HttpClient http;
    private Path workspace;
    private String appRoot;
    private String base;

    @Before
    public void setUp() throws Exception {
        server = new HttpServer("127.0.0.1", 0, new Main.ResourceConfig());
        server.start();
        http = HttpClient.newHttpClient();
        workspace = Files.createTempDirectory("rest-integration-");
        base = "http://127.0.0.1:" + server.getPort();

        // Create a real app via POST /v1/apps
        String body = """
            {
              "appName":"trading",
              "appDir":"%s",
              "packageName":"com.example.trading",
              "groupId":"com.example",
              "artifactPrefix":"test",
              "rumiVersion":"4.0.0",
              "rumiBindingsVersion":"4.0.0",
              "rumiMgmtVersion":"2.0.0",
              "encodingType":"QUARK",
              "messagingProvider":"ACTIVEMQ",
              "buildTool":"MAVEN"
            }
            """.formatted(workspace.toString());
        HttpResponse<String> resp = post("/v1/apps", body);
        assertEquals("POST /v1/apps -> 200, got " + resp.statusCode() + ": " + resp.body(),
            200, resp.statusCode());
        // App root is workspace / test-trading
        appRoot = workspace.resolve("test-trading").toString();
    }

    @After
    public void tearDown() throws Exception {
        if (server != null) server.stop();
        if (workspace != null && Files.exists(workspace)) {
            try (Stream<Path> s = Files.walk(workspace)) {
                s.sorted(Comparator.reverseOrder()).forEach(p -> {
                    try { Files.deleteIfExists(p); } catch (IOException ignored) {}
                });
            }
        }
    }

    @Test
    public void apps_list_findsScaffoldedApp() throws Exception {
        HttpResponse<String> r = get("/v1/apps?under=" + enc(workspace.toString()));
        assertEquals(200, r.statusCode());
        assertTrue("list response contains app root, got: " + r.body(),
            r.body().contains("test-trading"));
    }

    @Test
    public void apps_info_returnsAppMetadata() throws Exception {
        HttpResponse<String> r = get("/v1/apps/info?app_root=" + enc(appRoot));
        assertEquals(200, r.statusCode());
        assertTrue(r.body().contains("\"appName\":\"trading\""));
        assertTrue(r.body().contains("\"packageName\":\"com.example.trading\""));
    }

    @Test
    public void services_addListGetRemove_roundTrip() throws Exception {
        // Add a processor.
        HttpResponse<String> added = post("/v1/services?app_root=" + enc(appRoot),
            "{\"name\":\"order-processor\",\"type\":\"processor\",\"clustered\":false,\"partitions\":1}");
        assertEquals("POST service: " + added.body(), 200, added.statusCode());

        // List — should contain it.
        HttpResponse<String> list = get("/v1/services?app_root=" + enc(appRoot));
        assertEquals(200, list.statusCode());
        assertTrue(list.body().contains("order-processor"));

        // Get — should return the service detail.
        HttpResponse<String> one = get("/v1/services/order-processor?app_root=" + enc(appRoot));
        assertEquals(200, one.statusCode());
        assertTrue(one.body().contains("order-processor"));

        // Remove (dry run first).
        HttpResponse<String> dry = delete("/v1/services/order-processor?app_root=" + enc(appRoot) + "&dry_run=true");
        assertEquals(200, dry.statusCode());

        HttpResponse<String> real = delete("/v1/services/order-processor?app_root=" + enc(appRoot));
        assertEquals(200, real.statusCode());

        // List again — gone.
        HttpResponse<String> list2 = get("/v1/services?app_root=" + enc(appRoot));
        assertFalse(list2.body().contains("order-processor"));
    }

    @Test
    public void modelBatch_appliesAWholeModelAndRollsBackOnFailure() throws Exception {
        post("/v1/services?app_root=" + enc(appRoot),
            "{\"name\":\"proc\",\"type\":\"processor\",\"clustered\":false,\"partitions\":1}");

        HttpResponse<String> ok = post("/v1/model/batch?app_root=" + enc(appRoot),
            "{\"edits\":["
          + "{\"kind\":\"message\",\"service\":\"proc\",\"name\":\"PlaceOrder\","
          + "\"fields\":[{\"name\":\"qty\",\"type\":\"Long\"}]},"
          + "{\"kind\":\"fields\",\"service\":\"proc\",\"name\":\"PlaceOrder\",\"scope\":\"messages\","
          + "\"fields\":[{\"name\":\"symbol\",\"type\":\"String\"}]}"
          + "]}");
        assertEquals("batch should apply: " + ok.body(), 200, ok.statusCode());
        String listed = get("/v1/services/proc/messages?app_root=" + enc(appRoot)).body();
        assertTrue(listed.contains("PlaceOrder"));

        // A batch whose second edit targets a message that does not exist must
        // roll the first one back rather than half-apply.
        String before = get("/v1/services/proc/messages?app_root=" + enc(appRoot)).body();
        HttpResponse<String> bad = post("/v1/model/batch?app_root=" + enc(appRoot),
            "{\"edits\":["
          + "{\"kind\":\"message\",\"service\":\"proc\",\"name\":\"WouldHaveApplied\"},"
          + "{\"kind\":\"fields\",\"service\":\"proc\",\"name\":\"NoSuchMessage\",\"scope\":\"messages\","
          + "\"fields\":[{\"name\":\"x\",\"type\":\"Long\"}]}"
          + "]}");
        assertTrue("a failing batch must not return 200: " + bad.statusCode(), bad.statusCode() >= 400);
        assertEquals("the batch must have rolled back completely",
            before, get("/v1/services/proc/messages?app_root=" + enc(appRoot)).body());

        // A malformed edit is rejected before anything is written.
        assertEquals(400, post("/v1/model/batch?app_root=" + enc(appRoot),
            "{\"edits\":[{\"kind\":\"nonsense\",\"name\":\"X\"}]}").statusCode());
    }

    @Test
    public void fields_canBeAddedAsABatch() throws Exception {
        post("/v1/services?app_root=" + enc(appRoot),
            "{\"name\":\"proc\",\"type\":\"processor\",\"clustered\":false,\"partitions\":1}");
        post("/v1/services/proc/messages?app_root=" + enc(appRoot),
            "{\"name\":\"Tick\",\"fields\":[{\"name\":\"seq\",\"type\":\"Long\"}]}");

        HttpResponse<String> r = post("/v1/services/proc/fields?app_root=" + enc(appRoot),
            "{\"scope\":\"messages\",\"type\":\"Tick\",\"fields\":["
          + "{\"name\":\"bid\",\"type\":\"Double\"},{\"name\":\"ask\",\"type\":\"Double\"}]}");
        assertEquals("batch fields should apply: " + r.body(), 200, r.statusCode());

        String msg = get("/v1/services/proc/messages/Tick?app_root=" + enc(appRoot)).body();
        assertTrue(msg.contains("bid"));
        assertTrue(msg.contains("ask"));

        // Both forms at once is ambiguous and must be refused, not half-honoured.
        assertEquals(400, post("/v1/services/proc/fields?app_root=" + enc(appRoot),
            "{\"scope\":\"messages\",\"type\":\"Tick\",\"name\":\"solo\",\"fieldType\":\"Long\","
          + "\"fields\":[{\"name\":\"other\",\"type\":\"Long\"}]}").statusCode());
    }

    @Test
    public void configFragments_canBeNarrowedByScopeAndSelector() throws Exception {
        // Two xvm templates, so a narrowed read has something to exclude.
        post("/v1/config/fragments?app_root=" + enc(appRoot),
            "{\"scopePath\":[\"xvms\",\"templates\"],"
          + "\"xml\":\"<xvm name=\\\"inference\\\"><env><HEAP>2g</HEAP></env></xvm>\"}");
        post("/v1/config/fragments?app_root=" + enc(appRoot),
            "{\"scopePath\":[\"xvms\",\"templates\"],"
          + "\"xml\":\"<xvm name=\\\"web\\\"><env><HEAP>512m</HEAP></env></xvm>\"}");

        String all = get("/v1/config/fragments?app_root=" + enc(appRoot)).body();
        assertTrue(all.contains("inference"));
        assertTrue(all.contains("web"));

        // Narrowed to one named fragment: the other must not come back, and the
        // one that does carries its own env block.
        String one = get("/v1/config/fragments?app_root=" + enc(appRoot)
            + "&scope_path=xvms&scope_path=templates&tag=xvm&name=inference").body();
        assertTrue("the selected fragment is returned", one.contains("inference"));
        assertFalse("the unselected one must not be", one.contains("512m"));
        assertTrue("and it carries its env block", one.contains("2g"));

        // Narrowing to a different scope returns that scope only - the scaffold
        // puts a bus there, so this proves exclusion rather than emptiness.
        String buses = get("/v1/config/fragments?app_root=" + enc(appRoot)
            + "&scope_path=buses").body();
        assertTrue(buses.contains("\"tagName\":\"bus\""));
        assertFalse("an xvm must not appear under the buses scope", buses.contains("inference"));

        // Blank selector params are unset, not a selector that matches nothing.
        // Only the Python MCP omits them; a hand-built client sends ?tag=&name=.
        String blank = get("/v1/config/fragments?app_root=" + enc(appRoot)
            + "&scope_path=xvms&scope_path=templates&tag=&name=").body();
        assertTrue("blank tag/name must not narrow to nothing", blank.contains("inference"));

        // A scope this read cannot enumerate is refused, never reported empty:
        // remove navigates paths the read cannot see, so "nothing there" would
        // tell a caller that checked first that deleting was safe.
        assertEquals(400, get("/v1/config/fragments?app_root=" + enc(appRoot)
            + "&scope_path=xvms").statusCode());

        // A scope path that genuinely matches nothing is an empty list, not
        // everything - the failure mode of a filter that silently no-ops.
        assertEquals("[]", get("/v1/config/fragments?app_root=" + enc(appRoot)
            + "&scope_path=profiles&scope_path=nosuchprofile&scope_path=env").body().trim());
    }

    @Test
    public void handlerBody_isReadableAndReplaceableOverHttp() throws Exception {
        post("/v1/services?app_root=" + enc(appRoot),
            "{\"name\":\"proc\",\"type\":\"processor\",\"clustered\":false,\"partitions\":1}");
        assertEquals(200, post("/v1/services/proc/handlers?app_root=" + enc(appRoot),
            "{\"method\":\"onOrder\",\"messageType\":\"OrderRequest\",\"body\":\"int a = 1;\"}").statusCode());

        // A listing stays cheap by default and carries bodies only on request.
        assertFalse("list must not carry bodies by default",
            get("/v1/services/proc/handlers?app_root=" + enc(appRoot)).body().contains("int a = 1;"));
        assertTrue("include_body must opt in",
            get("/v1/services/proc/handlers?include_body=true&app_root=" + enc(appRoot)).body().contains("int a = 1;"));

        // The single get is where the body is the point.
        assertTrue(get("/v1/services/proc/handlers/onOrder?app_root=" + enc(appRoot)).body().contains("int a = 1;"));

        // Replace it.
        HttpResponse<String> up = put("/v1/services/proc/handlers/onOrder?app_root=" + enc(appRoot),
            "{\"body\":\"int b = 2;\"}");
        assertEquals(200, up.statusCode());
        String after = get("/v1/services/proc/handlers/onOrder?app_root=" + enc(appRoot)).body();
        assertTrue(after.contains("int b = 2;"));
        assertFalse(after.contains("int a = 1;"));

        // An ABSENT body is not an empty one. Before this check, {} replaced the
        // handler with nothing and returned 200 - working code deleted silently.
        HttpResponse<String> absent = put("/v1/services/proc/handlers/onOrder?app_root=" + enc(appRoot), "{}");
        assertEquals("an absent body is a client error: " + absent.body(), 400, absent.statusCode());
        assertTrue("the handler must be untouched",
            get("/v1/services/proc/handlers/onOrder?app_root=" + enc(appRoot)).body().contains("int b = 2;"));

        // "" remains the explicit way to empty one.
        assertEquals(200, put("/v1/services/proc/handlers/onOrder?app_root=" + enc(appRoot),
            "{\"body\":\"\"}").statusCode());

        // Restore a body for the checks below.
        assertEquals(200, put("/v1/services/proc/handlers/onOrder?app_root=" + enc(appRoot),
            "{\"body\":\"int b = 2;\"}").statusCode());

        // A listing must not ship a null body key now that it is suppressed.
        assertFalse("a null body should not be serialized",
            get("/v1/services/proc/handlers?app_root=" + enc(appRoot)).body().contains("\"body\":null"));

        // A body that does not parse is a 400 and must leave the handler alone.
        HttpResponse<String> bad = put("/v1/services/proc/handlers/onOrder?app_root=" + enc(appRoot),
            "{\"body\":\"int a = ;\"}");
        assertEquals("an unparseable body is a client error: " + bad.body(), 400, bad.statusCode());
        assertTrue("the rejected update must not have damaged the handler",
            get("/v1/services/proc/handlers/onOrder?app_root=" + enc(appRoot)).body().contains("int b = 2;"));
    }

    @Test
    public void handlers_messages_stateEntities_roundTripOnAddedProcessor() throws Exception {
        post("/v1/services?app_root=" + enc(appRoot),
            "{\"name\":\"proc\",\"type\":\"processor\",\"clustered\":false,\"partitions\":1}");

        // Add handler.
        HttpResponse<String> h = post("/v1/services/proc/handlers?app_root=" + enc(appRoot),
            "{\"method\":\"onOrder\",\"messageType\":\"OrderRequest\"}");
        assertEquals(200, h.statusCode());

        // List handlers.
        assertTrue(get("/v1/services/proc/handlers?app_root=" + enc(appRoot)).body().contains("onOrder"));

        // Remove handler.
        HttpResponse<String> rh = delete("/v1/services/proc/handlers/onOrder?app_root=" + enc(appRoot));
        assertEquals(200, rh.statusCode());

        // Add message.
        HttpResponse<String> m = post("/v1/services/proc/messages?app_root=" + enc(appRoot),
            "{\"name\":\"PlaceOrder\",\"fields\":[{\"name\":\"qty\",\"type\":\"int\"}]}");
        assertEquals(200, m.statusCode());
        assertTrue(get("/v1/services/proc/messages?app_root=" + enc(appRoot)).body().contains("PlaceOrder"));
        assertEquals(200, delete("/v1/services/proc/messages/PlaceOrder?app_root=" + enc(appRoot)).statusCode());

        // Add state entity.
        HttpResponse<String> s = post("/v1/services/proc/state-entities?app_root=" + enc(appRoot),
            "{\"name\":\"Order\",\"fields\":[{\"name\":\"id\",\"type\":\"String\",\"attributes\":{\"isKey\":\"true\"}}]}");
        assertEquals(200, s.statusCode());
        assertTrue(get("/v1/services/proc/state-entities?app_root=" + enc(appRoot)).body().contains("Order"));
        assertEquals(200, delete("/v1/services/proc/state-entities/Order?app_root=" + enc(appRoot)).statusCode());
    }

    @Test
    public void messageEntities_and_roeMessageScope_roundTrip() throws Exception {
        post("/v1/services?app_root=" + enc(appRoot),
            "{\"name\":\"proc\",\"type\":\"processor\",\"clustered\":false,\"partitions\":1}");

        // Embedded entity in the service message model (scope defaults to messages).
        HttpResponse<String> e = post("/v1/services/proc/message-entities?app_root=" + enc(appRoot),
            "{\"name\":\"Money\",\"fields\":[{\"name\":\"amount\",\"type\":\"Long\"}]}");
        assertEquals("POST message-entity: " + e.body(), 200, e.statusCode());
        assertTrue(get("/v1/services/proc/message-entities?app_root=" + enc(appRoot)).body().contains("Money"));
        assertTrue(get("/v1/services/proc/message-entities/Money?app_root=" + enc(appRoot)).body().contains("amount"));
        assertEquals(200, delete("/v1/services/proc/message-entities/Money?app_root=" + enc(appRoot)).statusCode());

        // State scope is rejected here — state entities have their own resource.
        HttpResponse<String> badScope = post("/v1/services/proc/message-entities?app_root=" + enc(appRoot) + "&scope=state",
            "{\"name\":\"Nope\"}");
        assertEquals("state scope must 400 on message-entities: " + badScope.body(), 400, badScope.statusCode());

        // Whole message added to the shared ROE model via scope=roe.
        HttpResponse<String> m = post("/v1/services/proc/messages?app_root=" + enc(appRoot) + "&scope=roe",
            "{\"name\":\"SharedEvent\",\"fields\":[{\"name\":\"ts\",\"type\":\"Long\"}]}");
        assertEquals("POST roe message: " + m.body(), 200, m.statusCode());
        // Visible under scope=roe...
        assertTrue(get("/v1/services/proc/messages?app_root=" + enc(appRoot) + "&scope=roe").body().contains("SharedEvent"));
        // ...but NOT in the service's own message model.
        assertFalse(get("/v1/services/proc/messages?app_root=" + enc(appRoot)).body().contains("SharedEvent"));
        assertEquals(200, delete("/v1/services/proc/messages/SharedEvent?app_root=" + enc(appRoot) + "&scope=roe").statusCode());
    }

    @Test
    public void collections_roundTrip_and_entityReferentialSafety() throws Exception {
        post("/v1/services?app_root=" + enc(appRoot),
            "{\"name\":\"proc\",\"type\":\"processor\",\"clustered\":false,\"partitions\":1}");

        // A state entity and a collection that contains it.
        post("/v1/services/proc/state-entities?app_root=" + enc(appRoot),
            "{\"name\":\"Account\",\"fields\":[{\"name\":\"id\",\"type\":\"String\",\"attributes\":{\"isKey\":\"true\"}}]}");
        HttpResponse<String> addC = post("/v1/services/proc/collections?app_root=" + enc(appRoot),
            "{\"name\":\"byId\",\"is\":\"StringMap\",\"contains\":\"Account\"}");
        assertEquals("POST collection: " + addC.body(), 200, addC.statusCode());
        assertTrue(get("/v1/services/proc/collections?app_root=" + enc(appRoot)).body().contains("byId"));
        assertTrue(get("/v1/services/proc/collections/byId?app_root=" + enc(appRoot)).body().contains("StringMap"));

        // Removing the entity is blocked while the collection still references it.
        HttpResponse<String> blocked = delete("/v1/services/proc/state-entities/Account?app_root=" + enc(appRoot));
        assertEquals("referenced entity removal must be blocked: " + blocked.body(), 422, blocked.statusCode());
        assertTrue(blocked.body().contains("byId"));
        // force overrides.
        assertEquals(200, delete("/v1/services/proc/state-entities/Account?app_root=" + enc(appRoot) + "&force=true").statusCode());
        // Collection removal is unconditional.
        assertEquals(200, delete("/v1/services/proc/collections/byId?app_root=" + enc(appRoot)).statusCode());
    }

    @Test
    public void messageRemoval_blockedByEventHandler_unlessForced() throws Exception {
        post("/v1/services?app_root=" + enc(appRoot),
            "{\"name\":\"proc\",\"type\":\"processor\",\"clustered\":false,\"partitions\":1}");
        post("/v1/services/proc/messages?app_root=" + enc(appRoot),
            "{\"name\":\"Req\",\"fields\":[{\"name\":\"x\",\"type\":\"int\"}]}");
        post("/v1/services/proc/handlers?app_root=" + enc(appRoot),
            "{\"method\":\"onReq\",\"messageType\":\"Req\"}");

        HttpResponse<String> blocked = delete("/v1/services/proc/messages/Req?app_root=" + enc(appRoot));
        assertEquals("message removal must be blocked by the handler: " + blocked.body(), 422, blocked.statusCode());
        assertTrue(blocked.body().contains("onReq"));
        assertEquals(200, delete("/v1/services/proc/messages/Req?app_root=" + enc(appRoot) + "&force=true").statusCode());
    }

    @Test
    public void config_fragments_listAndAdd() throws Exception {
        HttpResponse<String> list = get("/v1/config/fragments?app_root=" + enc(appRoot));
        assertEquals(200, list.statusCode());

        HttpResponse<String> add = post("/v1/config/fragments?app_root=" + enc(appRoot),
            "{\"scopePath\":[\"buses\"],\"xml\":\"<bus xmlns=\\\"http://www.neeveresearch.com/schema/x-ddl\\\" name=\\\"aux\\\" descriptor=\\\"activemq://aux.local\\\"/>\"}");
        assertEquals("POST fragment: " + add.body(), 200, add.statusCode());
    }

    @Test
    public void config_validate_runsAndReturnsResult() throws Exception {
        HttpResponse<String> r = post("/v1/config/validate?app_root=" + enc(appRoot), "");
        assertEquals(200, r.statusCode());
        // Result shape: {"ok":true|false, "errors":[...], "warnings":[...]}
        assertTrue(r.body().contains("\"ok\"") || r.body().contains("\"errors\""));
    }

    @Test
    public void factoryIds_listAndNext() throws Exception {
        HttpResponse<String> list = get("/v1/factory-ids?app_root=" + enc(appRoot));
        assertEquals(200, list.statusCode());

        HttpResponse<String> next = get("/v1/factory-ids/next?app_root=" + enc(appRoot));
        assertEquals(200, next.statusCode());
        assertTrue(next.body().contains("nextAvailableId"));
    }

    @Test
    public void errors_missingAppRoot_returns400WithEnvelope() throws Exception {
        HttpResponse<String> r = get("/v1/services");
        assertEquals(400, r.statusCode());
        assertTrue("400 body should carry the error envelope, got: " + r.body(),
            r.body().contains("\"code\":\"BadRequest\""));
    }

    @Test
    public void errors_serviceNotFound_returns404WithEnvelope() throws Exception {
        HttpResponse<String> r = get("/v1/services/does-not-exist?app_root=" + enc(appRoot));
        assertEquals(404, r.statusCode());
        assertTrue(r.body().contains("\"code\":\"NotFound\""));
    }

    @Test
    public void openapi_yamlEndpointReturnsSpec() throws Exception {
        HttpResponse<String> r = get("/openapi");
        assertEquals(200, r.statusCode());
        // Title from @OpenAPIDefinition on AbstractResource, populated at compile
        // time by swagger-maven-plugin-jakarta.
        assertTrue("spec has title, got: " + r.body().substring(0, Math.min(400, r.body().length())),
            r.body().contains("Rumi App Builder REST API"));
        // At least a few endpoints we annotated.
        assertTrue(r.body().contains("/v1/apps"));
        assertTrue(r.body().contains("/v1/services"));
        assertTrue(r.body().contains("/v1/config/fragments"));
        assertTrue(r.body().contains("/v1/factory-ids"));
    }

    @Test
    public void swagger_endpointReturnsSwaggerUiHtml() throws Exception {
        HttpResponse<String> r = get("/swagger");
        assertEquals(200, r.statusCode());
        // HTML template with swagger-ui CSS/JS inlined; renders against /openapi.
        assertTrue(r.body().contains("swagger-ui"));
        assertTrue(r.body().contains("/openapi"));
    }

    // --- helpers ------------------------------------------------------

    private HttpResponse<String> get(String path) throws Exception {
        return http.send(HttpRequest.newBuilder(URI.create(base + path)).GET().build(),
            HttpResponse.BodyHandlers.ofString());
    }

    private HttpResponse<String> post(String path, String body) throws Exception {
        return http.send(HttpRequest.newBuilder(URI.create(base + path))
                .header("Content-Type", "application/json")
                .POST(HttpRequest.BodyPublishers.ofString(body)).build(),
            HttpResponse.BodyHandlers.ofString());
    }

    private HttpResponse<String> put(String path, String json) throws Exception {
        return http.send(HttpRequest.newBuilder(URI.create(base + path))
                .header("Content-Type", "application/json")
                .PUT(HttpRequest.BodyPublishers.ofString(json)).build(),
            HttpResponse.BodyHandlers.ofString());
    }

    private HttpResponse<String> delete(String path) throws Exception {
        return http.send(HttpRequest.newBuilder(URI.create(base + path)).DELETE().build(),
            HttpResponse.BodyHandlers.ofString());
    }

    private static String enc(String s) { return URLEncoder.encode(s, StandardCharsets.UTF_8); }
}
