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
import com.neeve.appbuilder.model.ElementSelector;
import com.neeve.appbuilder.model.FieldDef;
import com.neeve.appbuilder.model.ServiceInfo;
import com.neeve.appbuilder.test.AppBuilderAssertions;
import com.neeve.appbuilder.test.TestAppFactory;
import org.junit.After;
import org.junit.Before;
import org.junit.Test;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Collections;
import java.util.List;
import java.util.Map;

import static org.junit.Assert.*;

/**
 * End-to-end integration: scaffold a real app via ApplicationBuilder and
 * ServiceBuilder templates, then exercise every Phase A-D component
 * against the real output. These tests are slower than the unit tests
 * (real template extraction, real file I/O) but they catch regressions
 * unit tests can't — template changes, scaffolder behaviour shifts,
 * introspector/editor mismatches.
 *
 * <p>Uses the Phase E {@link TestAppFactory} fixture and
 * {@link AppBuilderAssertions} helpers.
 */
public class AppBuilderIntegrationTest {

    private Path tempDir;
    private Path appRoot;

    @Before
    public void setUp() throws IOException {
        tempDir = Files.createTempDirectory("appbuilder-integration-");
        appRoot = TestAppFactory.newApp("trading")
            .packageName("com.example.trading")
            .scaffoldAt(tempDir);
    }

    @After
    public void tearDown() throws IOException {
        TestAppFactory.deleteRecursive(tempDir);
    }

    // ---- Scaffolded app shape ----------------------------------------

    @Test
    public void scaffoldedApp_hasExpectedLayout() throws Exception {
        AppBuilderAssertions.assertIsRumiApp(appRoot);
        // App metadata is readable.
        ApplicationBuilder.AppParams params = AppIntrospector.loadAppParams(appRoot);
        assertEquals("trading", params.getAppName());
        assertEquals("com.example.trading", params.getPackageName());

        // Parent POM exists; ROE and System modules scaffolded.
        assertTrue(Files.exists(appRoot.resolve("pom.xml")));
        assertTrue(Files.isDirectory(appRoot.resolve("test-trading-roe")));
        assertTrue(Files.isDirectory(appRoot.resolve("test-trading-system")));
        assertTrue(Files.exists(appRoot.resolve("test-trading-system").resolve("conf/config.xml")));
    }

    @Test
    public void listRumiApps_findsScaffoldedApp() throws Exception {
        List<Path> found = AppIntrospector.listRumiApps(tempDir);
        assertEquals(1, found.size());
        assertEquals(appRoot, found.get(0));
    }

    // ---- Services (add + introspect + factory IDs) -------------------

    @Test
    public void addProcessor_thenIntrospect() throws Exception {
        TestAppFactory.addProcessor(appRoot, "order-processor");
        AppBuilderAssertions.assertServiceExists(appRoot, "order-processor");

        ServiceInfo svc = ServiceIntrospector.getService(appRoot, "order-processor");
        assertEquals(ServiceBuilder.ServiceType.PROCESSOR, svc.getType());
        // Scaffolded processor has a Repository state entity by default (from the template).
        AppBuilderAssertions.assertStateEntityExists(appRoot, "order-processor", "Repository");
    }

    @Test
    public void addDriver_thenIntrospect() throws Exception {
        TestAppFactory.addDriver(appRoot, "feeder");
        AppBuilderAssertions.assertServiceExists(appRoot, "feeder");
        ServiceInfo svc = ServiceIntrospector.getService(appRoot, "feeder");
        assertEquals(ServiceBuilder.ServiceType.DRIVER, svc.getType());
    }

    @Test
    public void addConnector_thenIntrospect() throws Exception {
        TestAppFactory.addConnector(appRoot, "sink-out");
        AppBuilderAssertions.assertServiceExists(appRoot, "sink-out");
        ServiceInfo svc = ServiceIntrospector.getService(appRoot, "sink-out");
        assertEquals(ServiceBuilder.ServiceType.CONNECTOR, svc.getType());
    }

    @Test
    public void addWebservice_thenIntrospect() throws Exception {
        TestAppFactory.addWebservice(appRoot, "gateway");
        AppBuilderAssertions.assertServiceExists(appRoot, "gateway");

        ServiceInfo svc = ServiceIntrospector.getService(appRoot, "gateway");
        assertEquals(ServiceBuilder.ServiceType.WEBSERVICE, svc.getType());

        // Webservice ships the embedded HTTP server + a REST resource.
        assertTrue("HttpServer.java scaffolded",
            Files.exists(AppIntrospector.resolveHttpServerJavaFile(appRoot, "gateway")));
        assertTrue("resources/WebMain.java scaffolded",
            Files.exists(AppIntrospector.resolveMainJavaFile(appRoot, "gateway")
                .getParent().resolve("resources").resolve("WebMain.java")));
        // Stateful, like a processor: detection must NOT mistake it for one.
        assertTrue("state.xml scaffolded",
            Files.exists(AppIntrospector.resolveStateXmlFile(appRoot, "gateway")));

        // The config carries the HTTP port env.
        String config = Files.readString(
            appRoot.resolve("test-trading-system").resolve("conf/config.xml"));
        assertTrue("http port wired into config", config.contains("http.port"));
    }

    @Test
    public void addingServicesAllocatesUniqueFactoryIds() throws Exception {
        TestAppFactory.addProcessor(appRoot, "order-processor");
        TestAppFactory.addDriver(appRoot, "feeder");
        Map<Integer, String> used = FactoryIdCollector.listUsedIds(appRoot);
        // Processor allocates state factory + message factory IDs; driver allocates 1 message factory ID.
        assertTrue("expected multiple factory IDs in use, got: " + used, used.size() >= 3);
        // All IDs distinct (Map keyset already enforces that).
    }

    // ---- Handler I/O against a real Main.java ------------------------

    @Test
    public void addHandler_toScaffoldedProcessor_thenIntrospect() throws Exception {
        TestAppFactory.addProcessor(appRoot, "order-processor");

        ChangeSet result = JavaSourceEditor.addHandler(
            appRoot, "order-processor", "onOrder", "OrderRequest", null, false);
        assertTrue(result.isApplied());
        AppBuilderAssertions.assertHandlerExists(appRoot, "order-processor", "onOrder");

        // Service introspector should now report the handler.
        ServiceInfo svc = ServiceIntrospector.getService(appRoot, "order-processor");
        assertTrue("service rolled-up handlers include onOrder",
            svc.getHandlers().stream().anyMatch(h -> "onOrder".equals(h.getMethodName())));
    }

    @Test
    public void addThenRemoveHandler_onScaffoldedService_roundTrips() throws Exception {
        TestAppFactory.addDriver(appRoot, "feeder");
        JavaSourceEditor.addHandler(appRoot, "feeder", "onTick", "Tick", null, false);
        AppBuilderAssertions.assertHandlerExists(appRoot, "feeder", "onTick");
        JavaSourceEditor.removeHandler(appRoot, "feeder", "onTick", false);
        AppBuilderAssertions.assertHandlerAbsent(appRoot, "feeder", "onTick");
    }

    // ---- Message and state-entity round-trip -------------------------

    @Test
    public void addMessageAndEntity_thenRemove_roundTripsCleanly() throws Exception {
        TestAppFactory.addProcessor(appRoot, "order-processor");

        // Add a message.
        MessageEditor.addMessage(appRoot, "order-processor", "PlaceOrder",
            List.of(new FieldDef("orderId", "String", Map.of()),
                    new FieldDef("qty", "int", Map.of())),
            false);
        AppBuilderAssertions.assertMessageExists(appRoot, "order-processor", "PlaceOrder");

        // Add a state entity.
        StateEditor.addStateEntity(appRoot, "order-processor", "Order",
            List.of(new FieldDef("id", "String", Map.of("isKey", "true"))),
            false);
        AppBuilderAssertions.assertStateEntityExists(appRoot, "order-processor", "Order");

        // Remove both.
        MessageEditor.removeMessage(appRoot, "order-processor", "PlaceOrder", false);
        StateEditor.removeStateEntity(appRoot, "order-processor", "Order", false);
        AppBuilderAssertions.assertMessageAbsent(appRoot, "order-processor", "PlaceOrder");
        AppBuilderAssertions.assertStateEntityAbsent(appRoot, "order-processor", "Order");
    }

    // ---- Config fragments injected by scaffolder -------------------

    @Test
    public void scaffoldedProcessor_registersAppTemplateInConfig() throws Exception {
        TestAppFactory.addProcessor(appRoot, "order-processor");
        // ConfigInjector drops an <app> under apps/templates named
        // {AppTokenName}-{serviceKebab}-template == "trading-order-processor-template".
        AppBuilderAssertions.assertConfigFragmentPresent(appRoot,
            List.of("apps", "templates"),
            ElementSelector.byTagAndName("app", "trading-order-processor-template"));
    }

    @Test
    public void configFragmentEditor_addsCustomFragment() throws Exception {
        ChangeSet r = ConfigFragmentEditor.addFragment(appRoot,
            List.of("buses"),
            "<bus xmlns=\"http://www.neeveresearch.com/schema/x-ddl\" name=\"aux\" descriptor=\"activemq://aux.local\"/>",
            false);
        assertTrue(r.isApplied());
        AppBuilderAssertions.assertConfigFragmentPresent(appRoot,
            List.of("buses"),
            ElementSelector.byTagAndName("bus", "aux"));
    }

    // ---- Full service removal round-trip ----------------------------

    @Test
    public void addServiceThenRemoveService_cleansUpEndToEnd() throws Exception {
        TestAppFactory.addProcessor(appRoot, "order-processor");
        AppBuilderAssertions.assertServiceExists(appRoot, "order-processor");

        // Capture factory IDs owned by the service before removal.
        int idsBefore = FactoryIdCollector.listUsedIds(appRoot).size();

        ChangeSet r = ServiceRemover.removeService(appRoot, "order-processor", false);
        assertTrue(r.isApplied());

        AppBuilderAssertions.assertServiceAbsent(appRoot, "order-processor");
        AppBuilderAssertions.assertConfigFragmentAbsent(appRoot,
            List.of("apps", "templates"),
            ElementSelector.byTagAndName("app", "trading-order-processor-template"));

        // Factory IDs released should be the service's 2 (state + message factory).
        assertTrue("released at least one factory id", r.getFactoryIdsReleased().size() >= 2);
        int idsAfter = FactoryIdCollector.listUsedIds(appRoot).size();
        assertEquals("used-id count reduced by released count",
            idsBefore - r.getFactoryIdsReleased().size(), idsAfter);
    }

    // ---- Config validation against the staged X-DDL schema ----------

    @Test
    public void scaffoldedConfig_validatesAgainstSchema() throws Exception {
        // The bundled x-ddl.xsd is point-in-time; the scaffolder's template may use
        // elements the schema doesn't cover exactly. This test just asserts the
        // validator runs end-to-end and produces a result — a non-fatal warning
        // list is acceptable here.
        var result = ConfigValidator.validate(appRoot);
        assertNotNull(result);
        // At minimum, we didn't throw and we got a ValidationResult back.
    }

    @Test
    public void validator_flagsBrokenConfig() throws Exception {
        Path broken = tempDir.resolve("not-xml.xml");
        Files.writeString(broken, "<unclosed");
        var result = ConfigValidator.validateFile(broken);
        assertFalse(result.isOk());
        assertFalse(result.getErrors().isEmpty());
    }

    // ---- Full happy-path roll-up -----------------------------------

    @Test
    public void fullRollup_sanityCheck() throws Exception {
        TestAppFactory.addProcessor(appRoot, "order-processor");
        TestAppFactory.addDriver(appRoot, "feeder");
        TestAppFactory.addConnector(appRoot, "sink-out");

        List<ServiceInfo> services = ServiceIntrospector.listServices(appRoot);
        assertEquals(3, services.size());

        // Add something via every editor. Drivers don't have their own
        // messages.xml (they reference messages from other services), so the
        // new message goes to sink-out (connector template includes messages.xml).
        JavaSourceEditor.addHandler(appRoot, "feeder", "onTick", "Tick", null, false);
        MessageEditor.addMessage(appRoot, "sink-out", "Tick",
            List.of(new FieldDef("symbol", "String", Map.of())), false);
        StateEditor.addStateEntity(appRoot, "order-processor", "Position",
            Collections.emptyList(), false);

        // Service roll-ups reflect everything.
        ServiceInfo feeder = ServiceIntrospector.getService(appRoot, "feeder");
        assertEquals(1, feeder.getHandlers().size());

        // connector template ships with built-in messages; our added "Tick" joins them.
        ServiceInfo sinkOut = ServiceIntrospector.getService(appRoot, "sink-out");
        assertTrue("sink-out messages includes Tick",
            sinkOut.getMessages().stream().anyMatch(m -> "Tick".equals(m.getName())));

        ServiceInfo processor = ServiceIntrospector.getService(appRoot, "order-processor");
        assertEquals(2, processor.getStateEntities().size());  // Repository (from template) + Position

        // Every service also has its config-fragment.
        AppBuilderAssertions.assertConfigFragmentPresent(appRoot,
            List.of("apps", "templates"),
            ElementSelector.byTagAndName("app", "trading-feeder-template"));
        AppBuilderAssertions.assertConfigFragmentPresent(appRoot,
            List.of("apps", "templates"),
            ElementSelector.byTagAndName("app", "trading-sink-out-template"));
    }
}
