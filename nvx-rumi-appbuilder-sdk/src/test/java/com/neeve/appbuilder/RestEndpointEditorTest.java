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
package com.neeve.appbuilder;

import com.neeve.appbuilder.model.ChangeSet;
import com.neeve.appbuilder.model.FieldDef;
import com.neeve.appbuilder.model.RestEndpointDef;
import com.neeve.appbuilder.model.ServiceClientDef;
import com.neeve.appbuilder.test.TestAppFactory;
import org.junit.After;
import org.junit.Before;
import org.junit.Test;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.Map;

import static org.junit.Assert.*;

/**
 * RUMI-457. REST endpoints whose bodies are the caller's code, and the service
 * clients that prepare a web service for request-reply with another service.
 * That a generated app actually serves through them is proven by
 * {@code ci/verify-generated-app.sh}; this covers what the editors write and refuse.
 */
public class RestEndpointEditorTest {
    private static final String WEB = "gateway";
    private static final String SVC = "order-processor";
    private static final String CALL_BODY =
          "final com.example.demo.roe.LookupOrder request = RestJson.read(uri, null, com.example.demo.roe.LookupOrder.create());\n"
        + "try {\n"
        + "    return RestJson.reply(OrderProcessorClient.get().lookupOrder(request));\n"
        + "} catch (com.neeve.client.EClientException e) {\n"
        + "    return RestJson.clientFailure(e);\n"
        + "}";

    private Path tempDir;
    private Path appRoot;
    private Path config;
    private Path webPom;
    private Path webSource;

    @Before
    public void setUp() throws Exception {
        tempDir = Files.createTempDirectory("restendpoint-");
        appRoot = TestAppFactory.newApp("demo").packageName("com.example.demo").scaffoldAt(tempDir);
        TestAppFactory.addProcessor(appRoot, SVC);
        TestAppFactory.addWebservice(appRoot, WEB);
        MessageEditor.addMessage(appRoot, SVC, FieldEditor.ModelScope.ROE_MESSAGES, "LookupOrder",
            List.of(new FieldDef("orderId", "String", Map.of())), false);
        MessageEditor.addMessage(appRoot, SVC, FieldEditor.ModelScope.ROE_MESSAGES, "OrderFound",
            List.of(new FieldDef("status", "String", Map.of())), false);
        ApiOperationEditor.addOperation(appRoot, SVC, "lookupOrder", "LookupOrder", "OrderFound", false);
        config = ConnectorSupport.configPath(appRoot, AppIntrospector.loadAppParams(appRoot).getTokenMap());
        webPom = AppIntrospector.resolveServiceModuleDir(appRoot, WEB).resolve("pom.xml");
        webSource = AppIntrospector.resolveMainJavaFile(appRoot, WEB).getParent();
    }

    @After
    public void tearDown() throws Exception {
        TestAppFactory.deleteRecursive(tempDir);
    }

    private ChangeSet addClient() throws Exception {
        return ServiceClientEditor.addServiceClient(appRoot, WEB, SVC, false);
    }

    private ChangeSet addEndpoint(String method, String path, String body) throws Exception {
        return RestEndpointEditor.addEndpoint(appRoot, WEB, method, path, body, List.of(), false);
    }

    // --- service clients ----------------------------------------------------

    @Test
    public void addingAClientWritesTheClassDependencyAndClientBus() throws Exception {
        ChangeSet cs = addClient();
        assertFalse(cs.isNoop());
        assertTrue("the reason says how to call it: " + cs.getReason(),
            cs.getReason().contains("OrderProcessorClient.get().<operation>(request)")
                && cs.getReason().contains("lookupOrder(LookupOrder) -> OrderFound"));

        // keyed with the ENGINE name, partition suffix and all
        String client = Files.readString(webSource.resolve("rest/OrderProcessorClient.java"));
        assertTrue(client, client.contains("SERVICE_NAME = \"demo-order-processor-1\""));
        assertTrue(client.contains("TARGET_SERVICE = \"order-processor\""));
        assertTrue(client.contains("CLIENT_NAME = \"demo-gateway-rest\""));
        assertTrue(Files.readString(webPom).contains("<artifactId>test-demo-order-processor</artifactId>"));

        String cfg = Files.readString(config);
        assertTrue(cfg, cfg.contains("name=\"demo-order-processor-1_client_bus\""));
        assertTrue(cfg.contains("<REST_CLIENT_BUS_DESCRIPTOR>loopback://demo-client</REST_CLIENT_BUS_DESCRIPTOR>"));
        assertTrue(cfg.contains("<connectionDescriptor>${REST_CLIENT_BUS_DESCRIPTOR}</connectionDescriptor>"));
        assertTrue(ConfigValidator.validate(appRoot).isOk());

        List<ServiceClientDef> clients = ServiceClientIntrospector.listClients(appRoot, WEB);
        assertEquals(1, clients.size());
        assertEquals("order-processor", clients.get(0).getService());
        assertEquals("demo-order-processor-1", clients.get(0).getEngine());
        assertEquals(List.of("lookupOrder(LookupOrder) -> OrderFound"), clients.get(0).getMethods());

        assertTrue(addClient().isNoop());
    }

    @Test
    public void aClientIsRefusedWhereItCannotWork() throws Exception {
        TestAppFactory.addProcessor(appRoot, "ledger", ServiceBuilder.ServiceHAModel.STATE_REPLICATION, false, 2);
        String before = Files.readString(config);
        assertRefused(() -> ServiceClientEditor.addServiceClient(appRoot, SVC, WEB, false), "is not a webservice");
        assertRefused(() -> ServiceClientEditor.addServiceClient(appRoot, WEB, WEB, false), "its own service");
        assertRefused(() -> ServiceClientEditor.addServiceClient(appRoot, WEB, "nope", false), "not found");
        assertRefused(() -> ServiceClientEditor.addServiceClient(appRoot, WEB, "ledger", false), "partitions");
        assertEquals(before, Files.readString(config));
        assertFalse(Files.exists(webSource.resolve("rest")));
    }

    @Test
    public void aRefusalWritesNothing() throws Exception {
        Files.writeString(config, Files.readString(config).replace("APP_BUS_DESCRIPTOR>", "OTHER_BUS>"));
        String before = Files.readString(config);
        String pomBefore = Files.readString(webPom);
        assertRefused(this::addClient, "APP_BUS_DESCRIPTOR");
        assertEquals(before, Files.readString(config));
        assertEquals(pomBefore, Files.readString(webPom));
        assertFalse(Files.exists(webSource.resolve("rest")));
    }

    @Test
    public void removingTheClientPutsEverythingBack() throws Exception {
        String configBefore = Files.readString(config);
        String pomBefore = Files.readString(webPom);
        addClient();
        ServiceClientEditor.removeServiceClient(appRoot, WEB, SVC, false, false);
        assertFalse(Files.exists(webSource.resolve("rest")));
        assertEquals(normalize(pomBefore), normalize(Files.readString(webPom)));
        assertEquals(normalize(configBefore), normalize(Files.readString(config)));
        assertTrue(ServiceClientEditor.removeServiceClient(appRoot, WEB, SVC, false, false).isNoop());
    }

    @Test
    public void aClientInUseIsNotRemovedUnlessForced() throws Exception {
        addClient();
        addEndpoint("GET", "/orders/{orderId}", CALL_BODY);
        assertRefused(() -> ServiceClientEditor.removeServiceClient(appRoot, WEB, SVC, false, false),
            "still used by OrdersByOrderIdResource.java");
        ServiceClientEditor.removeServiceClient(appRoot, WEB, SVC, true, false);
        assertFalse(Files.exists(webSource.resolve("rest/OrderProcessorClient.java")));
    }

    @Test
    public void aClientBusTheDeveloperDeclaredIsUsedAsDeclared() throws Exception {
        String declared = "<bus descriptor=\"${ORDERS_CLIENT_BUS}\" name=\"demo-order-processor-1_client_bus\">"
            + "<channels><channel id=\"1\" name=\"requests\"><qos>Guaranteed</qos><key>demo-order-processor</key></channel>"
            + "<channel id=\"2\" name=\"responses\"><qos>Guaranteed</qos></channel></channels></bus>";
        Files.writeString(config, Files.readString(config).replace("</buses>", declared + "</buses>"));
        addClient();
        String cfg = Files.readString(config);
        assertTrue(cfg, cfg.contains("<connectionDescriptor>${ORDERS_CLIENT_BUS}</connectionDescriptor>"));
        assertTrue(cfg.contains("<requestsChannelKey>demo-order-processor</requestsChannelKey>"));
        assertFalse("no generated descriptor for a declared bus", cfg.contains("CLIENT_BUS_DESCRIPTOR"));
        ServiceClientEditor.removeServiceClient(appRoot, WEB, SVC, false, false);
        cfg = Files.readString(config);
        assertTrue("a declared bus is never removed", cfg.contains("demo-order-processor-1_client_bus"));
        assertFalse(cfg.contains("requestsChannelKey"));
    }

    @Test
    public void aDependencyTheDeveloperDeclaredSurvivesRemoval() throws Exception {
        String dep = "        <dependency>\n            <groupId>com.example.demo</groupId>\n"
            + "            <artifactId>test-demo-order-processor</artifactId>\n"
            + "            <version>${project.version}</version>\n        </dependency>\n";
        Files.writeString(webPom, Files.readString(webPom).replaceFirst("</dependencies>",
            java.util.regex.Matcher.quoteReplacement(dep) + "    </dependencies>"));
        addClient();
        ServiceClientEditor.removeServiceClient(appRoot, WEB, SVC, false, false);
        assertTrue(Files.readString(webPom).contains("<artifactId>test-demo-order-processor</artifactId>"));
    }

    @Test
    public void aDevelopersOwnClientBusDescriptorVariableIsNeverRemoved() throws Exception {
        String cfg = Files.readString(config);
        int env = cfg.indexOf("<env>", cfg.indexOf("<profile name=\"test\""));
        Files.writeString(config, cfg.substring(0, env + 5) + "<CLIENT_BUS_DESCRIPTOR>loopback://theirs</CLIENT_BUS_DESCRIPTOR>" + cfg.substring(env + 5));
        addClient();
        ServiceClientEditor.removeServiceClient(appRoot, WEB, SVC, false, false);
        assertTrue(Files.readString(config).contains("<CLIENT_BUS_DESCRIPTOR>loopback://theirs</CLIENT_BUS_DESCRIPTOR>"));
    }

    @Test
    public void aClientCanStillBeRemovedAfterItsTargetIsRepartitioned() throws Exception {
        addClient();
        String cfg = Files.readString(config);
        String one = "<app name=\"demo-order-processor-1\" template=\"demo-order-processor-template\"/>";
        Files.writeString(config, cfg.replace(one, one + "<app name=\"demo-order-processor-2\" template=\"demo-order-processor-template\"/>"));
        ServiceClientEditor.removeServiceClient(appRoot, WEB, SVC, false, false);
        assertFalse(Files.readString(config).contains("demo-order-processor-1_client_bus"));
    }

    @Test
    public void theDependencyGoesIntoTheProjectDependenciesNotDependencyManagement() throws Exception {
        String pom = Files.readString(webPom);
        String managed = "    <dependencyManagement>\n        <dependencies>\n        </dependencies>\n    </dependencyManagement>\n";
        int deps = pom.indexOf("    <dependencies>");
        Files.writeString(webPom, pom.substring(0, deps) + managed + pom.substring(deps));
        addClient();
        String after = Files.readString(webPom);
        String managedBlock = after.substring(after.indexOf("<dependencyManagement>"), after.indexOf("</dependencyManagement>"));
        assertFalse(managedBlock.contains("test-demo-order-processor"));
        assertTrue(after.contains("<artifactId>test-demo-order-processor</artifactId>"));
    }

    @Test
    public void aServiceAWebServiceHoldsAClientForCannotBeRemovedButTheWebServiceCan() throws Exception {
        addClient();
        assertRefused(() -> ServiceRemover.removeService(appRoot, SVC, false), "remove_service_client");
        ServiceRemover.removeService(appRoot, WEB, false);
        String cfg = Files.readString(config);
        assertFalse("removing the web service takes its client bus with it: " + cfg, cfg.contains("_client_bus"));
        assertFalse(cfg.contains("CLIENT_BUS_DESCRIPTOR"));
    }

    // --- endpoints ----------------------------------------------------------

    @Test
    public void anEndpointIsTheCallersBodyInsideAGeneratedMethod() throws Exception {
        addClient();
        ChangeSet cs = addEndpoint("GET", "/orders/{orderId}", CALL_BODY);
        assertFalse(cs.isNoop());
        String resource = Files.readString(webSource.resolve("resources/OrdersByOrderIdResource.java"));
        assertTrue(resource, resource.contains("@Path(\"/orders/{orderId}\")"));
        assertTrue(resource, resource.contains(
            "public Response get(@Context final UriInfo uri, @PathParam(\"orderId\") final String orderId) {"));
        assertTrue(resource.contains("        return RestJson.reply(OrderProcessorClient.get().lookupOrder(request));"));
        assertTrue(Files.exists(webSource.resolve("rest/RestJson.java")));

        RestEndpointDef got = RestEndpointIntrospector.getEndpoint(appRoot, WEB, "get", "/orders/{orderId}");
        assertEquals("the body comes back verbatim", CALL_BODY, got.getBody());
        assertEquals("GET /orders/{orderId}", RestEndpointIntrospector.listEndpoints(appRoot, WEB).get(0).toString());
        assertTrue(addEndpoint("GET", "/orders/{orderId}", CALL_BODY).isNoop());
    }

    @Test
    public void aBodyThatCallsAServiceWithoutItsClientIsRefusedNamingTheStep() throws Exception {
        assertRefused(() -> addEndpoint("GET", "/orders/{orderId}", CALL_BODY),
            "add_service_client(gateway, order-processor)");
        assertFalse(Files.exists(webSource.resolve("resources/OrdersByOrderIdResource.java")));
    }

    @Test
    public void bodyMethodsReceiveTheRequestBodyAndImportsAreAdded() throws Exception {
        addEndpoint("POST", "/echo", "return Response.ok(body).build();");
        RestEndpointEditor.addEndpoint(appRoot, WEB, "PUT", "/echo", "return RestJson.error(Status.CONFLICT, \"no\");",
            List.of("jakarta.ws.rs.core.Response.Status"), false);
        String resource = Files.readString(webSource.resolve("resources/EchoResource.java"));
        assertTrue(resource.contains("public Response post(@Context final UriInfo uri, final String body) {"));
        assertTrue(resource.contains("import jakarta.ws.rs.core.Response.Status;"));
        assertEquals(1, resource.split("import jakarta.ws.rs.core.Response.Status;", -1).length - 1);
    }

    @Test
    public void addingTheSameBodyAgainStillAddsNewImports() throws Exception {
        String body = "return RestJson.error(Status.CONFLICT, \"no\");";
        addEndpoint("GET", "/ping", body);
        ChangeSet retry = RestEndpointEditor.addEndpoint(appRoot, WEB, "GET", "/ping", body,
            List.of("jakarta.ws.rs.core.Response.Status"), false);
        assertFalse(retry.isNoop());
        assertTrue(Files.readString(webSource.resolve("resources/PingResource.java"))
            .contains("import jakarta.ws.rs.core.Response.Status;"));
        assertTrue(RestEndpointEditor.addEndpoint(appRoot, WEB, "GET", "/ping", body,
            List.of("jakarta.ws.rs.core.Response.Status"), false).isNoop());
    }

    @Test
    public void theClientCheckReadsCodeNotCommentsOrStrings() throws Exception {
        addEndpoint("GET", "/ping", "// switch to OrderProcessorClient later\nreturn Response.ok(\"OrderProcessorClient\").build();");
        Path mine = webSource.resolve("rest/OrderProcessorClient.java");
        Files.createDirectories(mine.getParent());
        Files.writeString(mine, "package com.example.demo.gateway.rest; public class OrderProcessorClient {}");
        assertRefused(() -> addEndpoint("GET", "/orders/{orderId}", CALL_BODY), "add_service_client");
    }

    @Test
    public void aTargetNamedOnlyInDependencyManagementStillGetsAProjectDependency() throws Exception {
        String pom = Files.readString(webPom);
        String managed = "    <dependencyManagement>\n        <dependencies>\n            <dependency>\n"
            + "                <groupId>com.example.demo</groupId>\n"
            + "                <artifactId>test-demo-order-processor</artifactId>\n"
            + "                <version>1</version>\n            </dependency>\n        </dependencies>\n"
            + "    </dependencyManagement>\n";
        int deps = pom.indexOf("    <dependencies>");
        Files.writeString(webPom, pom.substring(0, deps) + managed + pom.substring(deps));
        addClient();
        String after = Files.readString(webPom);
        assertEquals("managed and project dependency", 2, after.split("<artifactId>test-demo-order-processor</artifactId>", -1).length - 1);
    }

    @Test
    public void updatingReplacesOnlyTheBody() throws Exception {
        addEndpoint("GET", "/ping", "return Response.ok(\"one\").build();");
        String newBody = "final String reply = \"two\";\nreturn Response.ok(reply).build();";
        RestEndpointEditor.updateEndpoint(appRoot, WEB, "GET", "/ping", newBody, List.of(), false);
        assertEquals(newBody, RestEndpointIntrospector.getEndpoint(appRoot, WEB, "GET", "/ping").getBody());
        assertTrue(RestEndpointEditor.updateEndpoint(appRoot, WEB, "GET", "/ping", newBody, List.of(), false).isNoop());
        assertRefused(() -> RestEndpointEditor.updateEndpoint(appRoot, WEB, "GET", "/nope", newBody, List.of(), false),
            "add_rest_endpoint");
        assertRefused(() -> addEndpoint("GET", "/ping", "return null;"), "update_rest_endpoint");
    }

    @Test
    public void invalidRequestsAreRefused() throws Exception {
        assertRefused(() -> addEndpoint("FETCH", "/orders", "return null;"), "method must be one of");
        assertRefused(() -> addEndpoint("GET", "orders", "return null;"), "must start with '/'");
        assertRefused(() -> addEndpoint("GET", "/orders/", "return null;"), "must not end with '/'");
        assertRefused(() -> addEndpoint("GET", "/x/{body}", "return null;"), "clash");
        assertRefused(() -> addEndpoint("GET", "/items/{class}", "return null;"), "Java keyword");
        assertRefused(() -> addEndpoint("GET", "/items/{Response}", "return null;"), "lowercase");
        assertRefused(() -> addEndpoint("GET", "/x", "  "), "body is required");
        assertRefused(() -> RestEndpointEditor.addEndpoint(appRoot, SVC, "GET", "/x", "return null;", List.of(), false),
            "is not a webservice");
        assertRefused(() -> RestEndpointEditor.addEndpoint(appRoot, WEB, "GET", "/x", "return null;",
            List.of("not an import!"), false), "not an import");
    }

    @Test
    public void anEquivalentRouteIsRefused() throws Exception {
        addEndpoint("GET", "/orders/{orderId}", "return null;");
        assertRefused(() -> addEndpoint("GET", "/orders/{id}", "return null;"), "same route");
        // a different method on an equivalent spelling would get a second root class and answer 405
        assertRefused(() -> addEndpoint("POST", "/orders/{id}", "return null;"), "use that spelling");
        addEndpoint("POST", "/orders/{orderId}", "return null;");
        Files.writeString(webSource.resolve("resources/MyLookup.java"),
            "package com.example.demo.gateway.resources;\n@Path(\"/lookup/{id}\")\npublic class MyLookup {}\n");
        assertRefused(() -> addEndpoint("GET", "/lookup/{key}", "return null;"), "hand-written MyLookup.java");
    }

    @Test
    public void removingTheLastEndpointRemovesItsClassAndRestJsonButNotClients() throws Exception {
        addClient();
        addEndpoint("GET", "/orders/{orderId}", CALL_BODY);
        addEndpoint("POST", "/orders/{orderId}", CALL_BODY);
        RestEndpointEditor.removeEndpoint(appRoot, WEB, "GET", "/orders/{orderId}", false);
        assertTrue(Files.exists(webSource.resolve("resources/OrdersByOrderIdResource.java")));
        RestEndpointEditor.removeEndpoint(appRoot, WEB, "POST", "/orders/{orderId}", false);
        assertFalse(Files.exists(webSource.resolve("resources/OrdersByOrderIdResource.java")));
        assertFalse(Files.exists(webSource.resolve("rest/RestJson.java")));
        assertTrue("clients go with remove_service_client", Files.exists(webSource.resolve("rest/OrderProcessorClient.java")));
        assertTrue(RestEndpointEditor.removeEndpoint(appRoot, WEB, "GET", "/orders/{orderId}", false).isNoop());
    }

    @Test
    public void anEndpointWithoutItsEndMarkerIsLeftAlone() throws Exception {
        addEndpoint("GET", "/ping", "return null;");
        Path resource = webSource.resolve("resources/PingResource.java");
        String broken = Files.readString(resource).replace("// @rest-endpoint-end", "");
        Files.writeString(resource, broken);
        assertRefused(() -> RestEndpointEditor.removeEndpoint(appRoot, WEB, "GET", "/ping", false), "@rest-endpoint-end");
        assertEquals(broken, Files.readString(resource));
    }

    @Test
    public void dryRunWritesNothing() throws Exception {
        String before = Files.readString(config);
        assertFalse(ServiceClientEditor.addServiceClient(appRoot, WEB, SVC, true).isApplied());
        assertFalse(RestEndpointEditor.addEndpoint(appRoot, WEB, "GET", "/ping", "return null;", List.of(), true).isApplied());
        assertEquals(before, Files.readString(config));
        assertFalse(Files.exists(webSource.resolve("rest")));
        assertTrue(RestEndpointIntrospector.listEndpoints(appRoot, WEB).isEmpty());
    }

    @Test
    public void resourceClassNamesFollowThePath() {
        assertEquals("OrdersByOrderIdLinesResource", RestEndpointEditor.resourceClassName("/orders/{orderId}/lines"));
        assertEquals("RootResource", RestEndpointEditor.resourceClassName("/"));
        assertEquals("V1HealthCheckResource", RestEndpointEditor.resourceClassName("/v1/health-check"));
        assertEquals("Path2faResource", RestEndpointEditor.resourceClassName("/2fa"));
    }

    private static String normalize(String text) {
        return text.replaceAll("\\s+", " ").trim();
    }

    private interface Action { void run() throws Exception; }

    private static void assertRefused(Action action, String expected) throws Exception {
        try {
            action.run();
            fail("expected a refusal mentioning '" + expected + "'");
        } catch (IllegalArgumentException | IllegalStateException e) {
            assertTrue("'" + e.getMessage() + "' should mention '" + expected + "'", e.getMessage().contains(expected));
        }
    }
}
