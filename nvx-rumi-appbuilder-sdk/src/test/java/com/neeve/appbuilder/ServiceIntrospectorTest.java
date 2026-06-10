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

import com.neeve.appbuilder.model.ServiceInfo;
import org.junit.After;
import org.junit.Before;
import org.junit.Test;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.stream.Stream;

import static org.junit.Assert.*;

public class ServiceIntrospectorTest {

    private Path tempDir;
    private Path appRoot;

    @Before
    public void setUp() throws IOException {
        tempDir = Files.createTempDirectory("svcintr-");
        appRoot = PhaseBTestSupport.scaffoldApp(tempDir, "trading", "com.example.trading");
    }

    @After
    public void tearDown() throws IOException {
        if (tempDir != null && Files.exists(tempDir)) {
            try (Stream<Path> walk = Files.walk(tempDir)) {
                walk.sorted((a, b) -> b.getNameCount() - a.getNameCount())
                    .forEach(p -> { try { Files.deleteIfExists(p); } catch (IOException ignored) {} });
            }
        }
    }

    @Test
    public void listServiceNames_emptyWhenNoParentPom() throws Exception {
        // scaffoldApp() writes .rumi but no pom.xml.
        assertTrue(ServiceIntrospector.listServiceNames(appRoot).isEmpty());
    }

    @Test
    public void listServiceNames_stripsPrefixAndFiltersBuiltIns() throws Exception {
        // Parent artifact id is "test-trading". Built-ins: test-trading-roe, test-trading-system.
        PhaseBTestSupport.writeParentPom(appRoot,
            "test-trading-roe",
            "test-trading-system",
            "test-trading-order-processor",
            "test-trading-feeder",
            "test-trading-csv-out");

        List<String> names = ServiceIntrospector.listServiceNames(appRoot);
        assertEquals(3, names.size());
        assertEquals(List.of("order-processor", "feeder", "csv-out"), names);
    }

    @Test
    public void listServiceNames_skipsModulesWithoutMatchingPrefix() throws Exception {
        PhaseBTestSupport.writeParentPom(appRoot,
            "test-trading-roe",
            "test-trading-system",
            "test-trading-order-processor",
            "unrelated-thing"); // doesn't start with "test-trading-"
        List<String> names = ServiceIntrospector.listServiceNames(appRoot);
        assertEquals(List.of("order-processor"), names);
    }

    @Test
    public void listServices_rollsUpTypeAndModels() throws Exception {
        PhaseBTestSupport.writeParentPom(appRoot,
            "test-trading-roe",
            "test-trading-system",
            "test-trading-order-processor",
            "test-trading-feeder",
            "test-trading-csv-out");

        // order-processor: PROCESSOR (has state.xml), with 1 entity + 1 message
        PhaseBTestSupport.writeStateXml(appRoot, "order-processor",
            "<model xmlns=\"http://www.neeveresearch.com/schema/x-adml\">" +
            "<entities><entity name=\"Order\" id=\"1\"/></entities>" +
            "</model>");
        PhaseBTestSupport.writeMessagesXml(appRoot, "order-processor",
            "<model xmlns=\"http://www.neeveresearch.com/schema/x-adml\">" +
            "<messages><message name=\"PlaceOrder\" id=\"1\"/></messages>" +
            "</model>");
        // order-processor module dir needs to exist for resolveServiceType
        Files.createDirectories(appRoot.resolve("test-trading-order-processor"));

        // feeder: DRIVER (no state, no connector)
        Files.createDirectories(appRoot.resolve("test-trading-feeder"));
        PhaseBTestSupport.writeMessagesXml(appRoot, "feeder",
            "<model xmlns=\"http://www.neeveresearch.com/schema/x-adml\">" +
            "<messages><message name=\"Tick\" id=\"1\"/></messages>" +
            "</model>");

        // csv-out: CONNECTOR (has connector/ subdir)
        Files.createDirectories(appRoot.resolve("test-trading-csv-out"));
        Path csvOutMainJava = AppIntrospector.resolveMainJavaFile(appRoot, "csv-out");
        Files.createDirectories(csvOutMainJava.getParent().resolve("connector"));
        PhaseBTestSupport.writeMessagesXml(appRoot, "csv-out",
            "<model xmlns=\"http://www.neeveresearch.com/schema/x-adml\">" +
            "<messages/>" +
            "</model>");

        List<ServiceInfo> services = ServiceIntrospector.listServices(appRoot);
        assertEquals(3, services.size());

        ServiceInfo op = services.get(0);
        assertEquals("order-processor", op.getName());
        assertEquals(ServiceBuilder.ServiceType.PROCESSOR, op.getType());
        assertEquals(1, op.getStateEntities().size());
        assertEquals(1, op.getMessages().size());
        assertTrue(op.getHandlers().isEmpty());  // Phase C hasn't landed

        ServiceInfo feeder = services.get(1);
        assertEquals("feeder", feeder.getName());
        assertEquals(ServiceBuilder.ServiceType.DRIVER, feeder.getType());
        assertTrue(feeder.getStateEntities().isEmpty());
        assertEquals(1, feeder.getMessages().size());

        ServiceInfo csv = services.get(2);
        assertEquals("csv-out", csv.getName());
        assertEquals(ServiceBuilder.ServiceType.CONNECTOR, csv.getType());
        assertTrue(csv.getStateEntities().isEmpty());
        assertEquals(0, csv.getMessages().size());
    }

    @Test
    public void getService_returnsNullWhenAbsent() throws Exception {
        PhaseBTestSupport.writeParentPom(appRoot,
            "test-trading-roe", "test-trading-system", "test-trading-order-processor");
        Files.createDirectories(appRoot.resolve("test-trading-order-processor"));
        assertNull(ServiceIntrospector.getService(appRoot, "does-not-exist"));
    }

    @Test
    public void getService_returnsMatchWhenPresent() throws Exception {
        PhaseBTestSupport.writeParentPom(appRoot,
            "test-trading-roe", "test-trading-system", "test-trading-feeder");
        Files.createDirectories(appRoot.resolve("test-trading-feeder"));
        ServiceInfo s = ServiceIntrospector.getService(appRoot, "feeder");
        assertNotNull(s);
        assertEquals("feeder", s.getName());
        assertEquals(ServiceBuilder.ServiceType.DRIVER, s.getType());
    }
}
