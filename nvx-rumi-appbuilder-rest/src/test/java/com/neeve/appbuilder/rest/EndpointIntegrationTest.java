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
            "{\"name\":\"Order\",\"fields\":[{\"name\":\"id\",\"type\":\"String\",\"attributes\":{\"key\":\"true\"}}]}");
        assertEquals(200, s.statusCode());
        assertTrue(get("/v1/services/proc/state-entities?app_root=" + enc(appRoot)).body().contains("Order"));
        assertEquals(200, delete("/v1/services/proc/state-entities/Order?app_root=" + enc(appRoot)).statusCode());
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

    private HttpResponse<String> delete(String path) throws Exception {
        return http.send(HttpRequest.newBuilder(URI.create(base + path)).DELETE().build(),
            HttpResponse.BodyHandlers.ofString());
    }

    private static String enc(String s) { return URLEncoder.encode(s, StandardCharsets.UTF_8); }
}
