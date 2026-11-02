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
 * RUMI-457. A REST endpoint on a web service that calls another service's API
 * operation through its generated client. That it actually serves a request is
 * proven by {@code ci/verify-generated-app.sh}, which builds and runs a
 * generated app; this covers what the editor writes and refuses.
 */
public class RestEndpointEditorTest {
    private static final String WEB = "gateway";
    private static final String SVC = "order-processor";

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

    private ChangeSet add(String method, String path) throws Exception {
        return RestEndpointEditor.addEndpoint(appRoot, WEB, method, path, SVC, "lookupOrder", false);
    }

    @Test
    public void addWritesTheEndpointClientDependencyAndClientBus() throws Exception {
        ChangeSet cs = add("GET", "/orders/{orderId}");
        assertFalse(cs.isNoop());

        String resource = Files.readString(webSource.resolve("resources/OrdersByOrderIdResource.java"));
        assertTrue(resource.contains("@Path(\"/orders/{orderId}\")"));
        assertTrue(resource.contains("com.example.demo.roe.LookupOrder.create()"));
        assertTrue(resource.contains("OrderProcessorClient.get().lookupOrder(request)"));
        assertTrue(Files.exists(webSource.resolve("rest/RestEndpointSupport.java")));

        // the client must be keyed with the ENGINE's name, partition suffix and all
        String client = Files.readString(webSource.resolve("rest/OrderProcessorClient.java"));
        assertTrue(client, client.contains("SERVICE_NAME = \"demo-order-processor-1\""));
        assertTrue(client.contains("extends com.example.demo.order.processor.AbstractClient"));

        assertTrue(Files.readString(webPom).contains("<artifactId>test-demo-order-processor</artifactId>"));

        String cfg = Files.readString(config);
        assertTrue(cfg, cfg.contains("name=\"demo-order-processor-1_client_bus\""));
        assertTrue("the test profile gets a loopback namespace of its own: " + cfg,
            cfg.contains("<REST_CLIENT_BUS_DESCRIPTOR>loopback://demo-client</REST_CLIENT_BUS_DESCRIPTOR>"));
        assertTrue(cfg.contains("<demo-order-processor-1>"));
        assertTrue(cfg.contains("<connectionDescriptor>${REST_CLIENT_BUS_DESCRIPTOR}</connectionDescriptor>"));
        assertTrue(ConfigValidator.validate(appRoot).isOk());

        List<RestEndpointDef> endpoints = RestEndpointIntrospector.listEndpoints(appRoot, WEB);
        assertEquals(1, endpoints.size());
        assertEquals("GET /orders/{orderId} -> order-processor.lookupOrder", endpoints.get(0).toString());
    }

    @Test
    public void anIdenticalEndpointIsANoopAndADifferentOneOnTheSameRouteIsRefused() throws Exception {
        add("GET", "/orders/{orderId}");
        assertTrue(add("get", "/orders/{orderId}").isNoop());

        MessageEditor.addMessage(appRoot, SVC, FieldEditor.ModelScope.ROE_MESSAGES, "CancelOrder",
            List.of(new FieldDef("orderId", "String", Map.of())), false);
        ApiOperationEditor.addOperation(appRoot, SVC, "cancelOrder", "CancelOrder", "OrderFound", false);
        try {
            RestEndpointEditor.addEndpoint(appRoot, WEB, "GET", "/orders/{orderId}", SVC, "cancelOrder", false);
            fail("expected the taken route to be refused");
        } catch (IllegalArgumentException expected) {
            assertTrue(expected.getMessage().contains("remove_rest_endpoint"));
        }
        // a second method on the same path shares its resource class
        RestEndpointEditor.addEndpoint(appRoot, WEB, "DELETE", "/orders/{orderId}", SVC, "cancelOrder", false);
        assertEquals(2, RestEndpointIntrospector.listEndpoints(appRoot, WEB).size());
        try (var files = Files.list(webSource.resolve("resources"))) {
            assertEquals("WebMain plus one class for the path", 2, files.count());
        }
    }

    @Test
    public void invalidRequestsAreRefusedBeforeAnythingIsWritten() throws Exception {
        String before = Files.readString(config);
        assertRefused(() -> add("FETCH", "/orders"), "method must be one of");
        assertRefused(() -> add("GET", "orders"), "must start with '/'");
        assertRefused(() -> add("GET", "/orders/"), "must not end with '/'");
        assertRefused(() -> add("GET", "/orders/{id}"), "is not a field of LookupOrder");
        assertRefused(() -> RestEndpointEditor.addEndpoint(appRoot, WEB, "GET", "/x", SVC, "nope", false),
            "add_operation");
        assertRefused(() -> RestEndpointEditor.addEndpoint(appRoot, SVC, "GET", "/x", WEB, "lookupOrder", false),
            "is not a webservice");
        assertRefused(() -> RestEndpointEditor.addEndpoint(appRoot, WEB, "GET", "/x", WEB, "lookupOrder", false),
            "its own operations");
        assertEquals(before, Files.readString(config));
        assertFalse(Files.exists(webSource.resolve("rest/RestEndpointSupport.java")));
    }

    @Test
    public void aPartitionedServiceIsRefusedRatherThanCalledOnOnePartition() throws Exception {
        TestAppFactory.addProcessor(appRoot, "ledger", ServiceBuilder.ServiceHAModel.STATE_REPLICATION, false, 2);
        MessageEditor.addMessage(appRoot, "ledger", FieldEditor.ModelScope.ROE_MESSAGES, "Balance",
            List.of(new FieldDef("account", "String", Map.of())), false);
        ApiOperationEditor.addOperation(appRoot, "ledger", "balance", "Balance", "OrderFound", false);
        assertRefused(() -> RestEndpointEditor.addEndpoint(appRoot, WEB, "GET", "/balance", "ledger", "balance", false),
            "partitions");
    }

    @Test
    public void removingTheLastEndpointPutsEverythingBack() throws Exception {
        String configBefore = Files.readString(config);
        String pomBefore = Files.readString(webPom);
        add("GET", "/orders/{orderId}");
        add("POST", "/orders/lookup");

        // the client outlives the first removal, because another endpoint still uses it
        RestEndpointEditor.removeEndpoint(appRoot, WEB, "GET", "/orders/{orderId}", false);
        assertFalse(Files.exists(webSource.resolve("resources/OrdersByOrderIdResource.java")));
        assertTrue(Files.exists(webSource.resolve("rest/OrderProcessorClient.java")));
        assertTrue(Files.readString(config).contains("_client_bus"));

        RestEndpointEditor.removeEndpoint(appRoot, WEB, "POST", "/orders/lookup", false);
        assertTrue(RestEndpointIntrospector.listEndpoints(appRoot, WEB).isEmpty());
        assertFalse(Files.exists(webSource.resolve("rest/RestEndpointSupport.java")));
        assertFalse(Files.exists(webSource.resolve("rest")));
        assertEquals(normalize(pomBefore), normalize(Files.readString(webPom)));
        assertEquals(normalize(configBefore), normalize(Files.readString(config)));

        assertTrue(RestEndpointEditor.removeEndpoint(appRoot, WEB, "GET", "/orders/{orderId}", false).isNoop());
    }

    @Test
    public void aServiceThatEndpointsCallCannotBeRemovedButItsWebServiceCan() throws Exception {
        String configBefore = Files.readString(config);
        add("GET", "/orders/{orderId}");
        assertRefused(() -> ServiceRemover.removeService(appRoot, SVC, false), "remove_rest_endpoint");

        ServiceRemover.removeService(appRoot, WEB, false);
        String cfg = Files.readString(config);
        assertFalse("removing the web service takes its client bus with it: " + cfg, cfg.contains("_client_bus"));
        assertFalse(cfg.contains("CLIENT_BUS_DESCRIPTOR"));
        ServiceRemover.removeService(appRoot, SVC, false);
        assertNotEquals(configBefore, cfg);
    }

    @Test
    public void aClientBusTheDeveloperDeclaredIsUsedAsDeclared() throws Exception {
        // the shape a hand-wired app has: its own descriptor variable and a pinned requests key
        String declared = "<bus descriptor=\"${ORDERS_CLIENT_BUS}\" name=\"demo-order-processor-1_client_bus\">"
            + "<channels><channel id=\"1\" name=\"requests\"><qos>Guaranteed</qos><key>demo-order-processor</key></channel>"
            + "<channel id=\"2\" name=\"responses\"><qos>Guaranteed</qos></channel></channels></bus>";
        Files.writeString(config, Files.readString(config).replace("</buses>", declared + "</buses>"));

        add("GET", "/orders/{orderId}");
        String cfg = Files.readString(config);
        assertTrue(cfg, cfg.contains("<connectionDescriptor>${ORDERS_CLIENT_BUS}</connectionDescriptor>"));
        assertTrue("the client must send with the key the bus pins: " + cfg,
            cfg.contains("<requestsChannelKey>demo-order-processor</requestsChannelKey>"));
        assertFalse("no generated descriptor for a declared bus", cfg.contains("CLIENT_BUS_DESCRIPTOR"));

        RestEndpointEditor.removeEndpoint(appRoot, WEB, "GET", "/orders/{orderId}", false);
        cfg = Files.readString(config);
        assertTrue("a declared bus is never removed", cfg.contains("demo-order-processor-1_client_bus"));
        assertFalse(cfg.contains("requestsChannelKey"));
    }

    @Test
    public void aHandWrittenClassInTheGeneratedPackageIsNotOverwritten() throws Exception {
        Path mine = webSource.resolve("rest/OrderProcessorClient.java");
        Files.createDirectories(mine.getParent());
        Files.writeString(mine, "package com.example.demo.gateway.rest; public class OrderProcessorClient {}");
        assertRefused(() -> add("GET", "/orders/{orderId}"), "was not generated by App Builder");
        assertEquals("package com.example.demo.gateway.rest; public class OrderProcessorClient {}", Files.readString(mine));
    }

    // --- code review on PR #21 --------------------------------------------

    @Test
    public void aDependencyTheDeveloperDeclaredSurvivesTheLastEndpoint() throws Exception {
        String dep = "        <dependency>\n            <groupId>com.example.demo</groupId>\n"
            + "            <artifactId>test-demo-order-processor</artifactId>\n"
            + "            <version>${project.version}</version>\n        </dependency>\n";
        String pom = Files.readString(webPom);
        Files.writeString(webPom, pom.replaceFirst("</dependencies>", java.util.regex.Matcher.quoteReplacement(dep) + "    </dependencies>"));
        add("GET", "/orders/{orderId}");
        RestEndpointEditor.removeEndpoint(appRoot, WEB, "GET", "/orders/{orderId}", false);
        assertTrue(Files.readString(webPom).contains("<artifactId>test-demo-order-processor</artifactId>"));
    }

    @Test
    public void generatedConfigUsesAClientNameOfItsOwn() throws Exception {
        // a client the developer configured under the web service's own name
        String mine = "<demo-order-processor-1><client><demo-gateway><connectionDescriptor>loopback://mine</connectionDescriptor>"
            + "</demo-gateway></client></demo-order-processor-1>";
        String cfg = Files.readString(config);
        int env = cfg.indexOf("<env>", cfg.indexOf("<profile name=\"test\""));
        Files.writeString(config, cfg.substring(0, env + 5) + mine + cfg.substring(env + 5));
        add("GET", "/orders/{orderId}");
        assertTrue(Files.readString(webSource.resolve("rest/OrderProcessorClient.java")).contains("CLIENT_NAME = \"demo-gateway-rest\""));
        RestEndpointEditor.removeEndpoint(appRoot, WEB, "GET", "/orders/{orderId}", false);
        assertTrue("the developer's client config is untouched", Files.readString(config).contains("loopback://mine"));
    }

    @Test
    public void aRefusalWritesNothing() throws Exception {
        // no profile sets APP_BUS_DESCRIPTOR, which is only discovered while wiring the config
        Files.writeString(config, Files.readString(config).replace("APP_BUS_DESCRIPTOR>", "OTHER_BUS>"));
        String before = Files.readString(config);
        assertRefused(() -> add("GET", "/orders/{orderId}"), "APP_BUS_DESCRIPTOR");
        assertEquals(before, Files.readString(config));
        assertFalse(Files.exists(webSource.resolve("resources/OrdersByOrderIdResource.java")));
        assertFalse(Files.exists(webSource.resolve("rest")));
    }

    @Test
    public void aDevelopersOwnClientBusDescriptorVariableIsNeverRemoved() throws Exception {
        String cfg = Files.readString(config);
        int env = cfg.indexOf("<env>", cfg.indexOf("<profile name=\"test\""));
        Files.writeString(config, cfg.substring(0, env + 5) + "<CLIENT_BUS_DESCRIPTOR>loopback://theirs</CLIENT_BUS_DESCRIPTOR>" + cfg.substring(env + 5));
        add("GET", "/orders/{orderId}");
        RestEndpointEditor.removeEndpoint(appRoot, WEB, "GET", "/orders/{orderId}", false);
        assertTrue(Files.readString(config).contains("<CLIENT_BUS_DESCRIPTOR>loopback://theirs</CLIENT_BUS_DESCRIPTOR>"));
    }

    @Test
    public void anEndpointCanStillBeRemovedAfterItsTargetIsRepartitioned() throws Exception {
        add("GET", "/orders/{orderId}");
        String cfg = Files.readString(config);
        String one = "<app name=\"demo-order-processor-1\" template=\"demo-order-processor-template\"/>";
        assertTrue(cfg.contains(one));
        Files.writeString(config, cfg.replace(one, one + "<app name=\"demo-order-processor-2\" template=\"demo-order-processor-template\"/>"));
        RestEndpointEditor.removeEndpoint(appRoot, WEB, "GET", "/orders/{orderId}", false);
        assertTrue(RestEndpointIntrospector.listEndpoints(appRoot, WEB).isEmpty());
        assertFalse(Files.readString(config).contains("demo-order-processor-1_client_bus"));
    }

    @Test
    public void anEquivalentRouteIsRefused() throws Exception {
        add("GET", "/orders/{orderId}");
        MessageEditor.addMessage(appRoot, SVC, FieldEditor.ModelScope.ROE_MESSAGES, "LookupById",
            List.of(new FieldDef("id", "String", Map.of())), false);
        ApiOperationEditor.addOperation(appRoot, SVC, "lookupById", "LookupById", "OrderFound", false);
        assertRefused(() -> RestEndpointEditor.addEndpoint(appRoot, WEB, "GET", "/orders/{id}", SVC, "lookupById", false),
            "same route");
        Files.writeString(webSource.resolve("resources/MyLookup.java"),
            "package com.example.demo.gateway.resources;\n@jakarta.ws.rs.Path(\"/x\")\n@Path(\"/lookup/{id}\")\npublic class MyLookup {}\n");
        assertRefused(() -> add("GET", "/lookup/{orderId}"), "hand-written MyLookup.java");
    }

    @Test
    public void theDependencyGoesIntoTheProjectDependenciesNotDependencyManagement() throws Exception {
        String pom = Files.readString(webPom);
        String managed = "    <dependencyManagement>\n        <dependencies>\n        </dependencies>\n    </dependencyManagement>\n";
        int deps = pom.indexOf("    <dependencies>");
        Files.writeString(webPom, pom.substring(0, deps) + managed + pom.substring(deps));
        add("GET", "/orders/{orderId}");
        String after = Files.readString(webPom);
        String managedBlock = after.substring(after.indexOf("<dependencyManagement>"), after.indexOf("</dependencyManagement>"));
        assertFalse(managedBlock.contains("test-demo-order-processor"));
        assertTrue(after.contains("<artifactId>test-demo-order-processor</artifactId>"));
    }

    @Test
    public void anEndpointWithoutItsEndMarkerIsNotRemovedToTheEndOfTheFile() throws Exception {
        add("GET", "/orders/{orderId}");
        Path resource = webSource.resolve("resources/OrdersByOrderIdResource.java");
        String broken = Files.readString(resource).replace("// @rest-endpoint-end", "");
        Files.writeString(resource, broken);
        assertRefused(() -> RestEndpointEditor.removeEndpoint(appRoot, WEB, "GET", "/orders/{orderId}", false),
            "@rest-endpoint-end");
        assertEquals(broken, Files.readString(resource));
    }

    @Test
    public void dryRunWritesNothing() throws Exception {
        String before = Files.readString(config);
        ChangeSet cs = RestEndpointEditor.addEndpoint(appRoot, WEB, "GET", "/orders/{orderId}", SVC, "lookupOrder", true);
        assertFalse(cs.isApplied());
        assertFalse(cs.getFilesCreated().isEmpty());
        assertEquals(before, Files.readString(config));
        assertTrue(RestEndpointIntrospector.listEndpoints(appRoot, WEB).isEmpty());
    }

    @Test
    public void aMissingHandlerIsReportedNotRefused() throws Exception {
        ChangeSet cs = add("GET", "/orders/{orderId}");
        assertNotNull(cs.getReason());
        assertTrue(cs.getReason(), cs.getReason().contains("no @EventHandler for LookupOrder"));
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
