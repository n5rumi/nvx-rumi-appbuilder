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

import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;

import static org.junit.Assert.*;

/**
 * End-to-end smoke test: boots the real REST service on an ephemeral
 * port via {@link HttpServer} + {@link AppBuilderResourceConfig}, hits
 * {@code /health}, and asserts 200 + the expected service identifier.
 *
 * <p>This is the "does the stack wire up at all" test for the RUMI-300
 * scaffold. Endpoint-specific tests land alongside each resource in
 * RUMI-301.
 */
public class HttpServerSmokeTest {
    private HttpServer server;

    @Before
    public void setUp() throws Exception {
        server = new HttpServer("127.0.0.1", 0, new AppBuilderResourceConfig());
        server.start();
    }

    @After
    public void tearDown() throws Exception {
        if (server != null) server.stop();
    }

    @Test
    public void healthEndpoint_returns200AndServiceIdentity() throws Exception {
        HttpClient http = HttpClient.newHttpClient();
        HttpResponse<String> resp = http.send(
            HttpRequest.newBuilder(URI.create("http://127.0.0.1:" + server.getPort() + "/health")).GET().build(),
            HttpResponse.BodyHandlers.ofString());

        assertEquals(200, resp.statusCode());
        assertTrue("body contains status:ok, got: " + resp.body(),
            resp.body().contains("\"status\":\"ok\""));
        assertTrue("body identifies the service, got: " + resp.body(),
            resp.body().contains("nvx-rumi-appbuilder-rest"));
    }

    @Test
    public void unknownPath_returns404WithErrorEnvelope() throws Exception {
        HttpClient http = HttpClient.newHttpClient();
        HttpResponse<String> resp = http.send(
            HttpRequest.newBuilder(URI.create("http://127.0.0.1:" + server.getPort() + "/nonsense")).GET().build(),
            HttpResponse.BodyHandlers.ofString());

        assertEquals(404, resp.statusCode());
        // ExceptionMapper wraps the JAX-RS NotFoundException in our envelope.
        assertTrue("404 body should carry the error envelope, got: " + resp.body(),
            resp.body().contains("\"code\":\"NotFound\""));
    }
}
