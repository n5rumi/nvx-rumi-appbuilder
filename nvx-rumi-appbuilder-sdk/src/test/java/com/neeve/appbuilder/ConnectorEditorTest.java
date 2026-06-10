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
import com.neeve.appbuilder.model.ConnectorDef;
import com.neeve.appbuilder.test.TestAppFactory;
import org.junit.After;
import org.junit.Before;
import org.junit.Test;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;

import static org.junit.Assert.*;

/**
 * Exercises {@link ConnectorEditor} and {@link ConnectorIntrospector} against
 * a real scaffolded app: snap a connector into a processor service, verify all
 * three artifacts (Java class, bus binding, app messaging reference), then
 * remove it and verify full reversal. Also covers idempotency and dry-run.
 */
public class ConnectorEditorTest {

    private Path tempDir;
    private Path appRoot;

    private Path connectorJava;     // expected Kafka.java
    private static final String CONNECTOR = "kafka";
    private static final String SERVICE = "order-processor";
    private static final String BUS_NAME = "trading-order-processor-kafka";
    private static final String FQCN = "com.example.trading.order.processor.connector.Kafka";

    @Before
    public void setUp() throws Exception {
        tempDir = Files.createTempDirectory("connector-editor-");
        appRoot = TestAppFactory.newApp("trading")
            .packageName("com.example.trading")
            .scaffoldAt(tempDir);
        TestAppFactory.addProcessor(appRoot, SERVICE);
        connectorJava = AppIntrospector.resolveMainJavaFile(appRoot, SERVICE)
            .getParent().resolve("connector").resolve("Kafka.java");
    }

    @After
    public void tearDown() throws IOException {
        TestAppFactory.deleteRecursive(tempDir);
    }

    private String config() throws IOException {
        return Files.readString(appRoot.resolve("test-trading-system").resolve("conf/config.xml"));
    }

    @Test
    public void addConnector_createsAllThreeArtifacts() throws Exception {
        ChangeSet cs = ConnectorEditor.addConnector(appRoot, SERVICE, CONNECTOR, false);
        assertTrue(cs.isApplied());
        assertFalse(cs.isNoop());

        // (1) Java class.
        assertTrue("connector class created", Files.exists(connectorJava));
        String java = Files.readString(connectorJava);
        assertTrue(java.contains("class Kafka implements Connector"));
        assertTrue(java.contains("package com.example.trading.order.processor.connector;"));

        // (2) connector:// bus binding.
        String config = config();
        assertTrue("bus binding present", config.contains("connector://"));
        assertTrue("binds the connector class", config.contains("classname=" + FQCN));
        assertTrue("connector bus named", config.contains("name=\"" + BUS_NAME + "\""));

        // (3) app messaging reference is wired (introspector cross-checks both).
        ConnectorDef def = ConnectorIntrospector.getConnector(appRoot, SERVICE, CONNECTOR);
        assertNotNull(def);
        assertEquals(FQCN, def.getClassName());
        assertEquals(BUS_NAME, def.getBusName());
        assertEquals("in", def.getInboundChannel());
    }

    @Test
    public void listConnectors_returnsAdded() throws Exception {
        ConnectorEditor.addConnector(appRoot, SERVICE, CONNECTOR, false);
        List<ConnectorDef> connectors = ConnectorIntrospector.listConnectors(appRoot, SERVICE);
        assertEquals(1, connectors.size());
        assertEquals("kafka", connectors.get(0).getName());
    }

    @Test
    public void addConnector_isIdempotent() throws Exception {
        ConnectorEditor.addConnector(appRoot, SERVICE, CONNECTOR, false);
        ChangeSet second = ConnectorEditor.addConnector(appRoot, SERVICE, CONNECTOR, false);
        assertTrue("second add is a noop", second.isNoop());
        assertEquals(1, ConnectorIntrospector.listConnectors(appRoot, SERVICE).size());
    }

    @Test
    public void removeConnector_revertsEverything() throws Exception {
        ConnectorEditor.addConnector(appRoot, SERVICE, CONNECTOR, false);
        ChangeSet cs = ConnectorEditor.removeConnector(appRoot, SERVICE, CONNECTOR, false);
        assertTrue(cs.isApplied());

        assertFalse("connector class deleted", Files.exists(connectorJava));
        String config = config();
        assertFalse("bus binding gone", config.contains("classname=" + FQCN));
        assertFalse("bus name gone", config.contains(BUS_NAME));
        assertTrue("introspector sees none", ConnectorIntrospector.listConnectors(appRoot, SERVICE).isEmpty());
    }

    @Test
    public void removeConnector_absentIsNoop() throws Exception {
        ChangeSet cs = ConnectorEditor.removeConnector(appRoot, SERVICE, "nope", false);
        assertTrue(cs.isNoop());
    }

    @Test
    public void addConnector_dryRunWritesNothing() throws Exception {
        String before = config();
        ChangeSet cs = ConnectorEditor.addConnector(appRoot, SERVICE, CONNECTOR, true);
        assertFalse("dry run not applied", cs.isApplied());
        assertFalse("dry run lists the would-be artifacts", cs.getFilesCreated().isEmpty());

        assertFalse("no class written", Files.exists(connectorJava));
        assertEquals("config untouched", before, config());
    }
}
