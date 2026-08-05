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

import com.neeve.appbuilder.model.CollectionDef;
import com.neeve.appbuilder.model.EntityDef;
import org.junit.After;
import org.junit.Before;
import org.junit.Test;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.stream.Stream;

import static org.junit.Assert.*;

public class StateIntrospectorTest {

    private Path tempDir;
    private Path appRoot;

    @Before
    public void setUp() throws IOException {
        tempDir = Files.createTempDirectory("stateintr-");
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
    public void listStateEntities_emptyWhenFileMissing() throws Exception {
        assertTrue(StateIntrospector.listStateEntities(appRoot, "anyService").isEmpty());
    }

    @Test
    public void listStateEntities_returnsEntitiesWithFields() throws Exception {
        PhaseBTestSupport.writeStateXml(appRoot, "orderProcessor",
            "<model xmlns=\"http://www.neeveresearch.com/schema/x-adml\">" +
            "<entities>" +
            "  <entity name=\"Order\" id=\"1\">" +
            "    <field name=\"id\" type=\"String\" isKey=\"true\"/>" +
            "    <field name=\"status\" type=\"String\"/>" +
            "  </entity>" +
            "  <entity name=\"Trade\" id=\"2\">" +
            "    <field name=\"symbol\" type=\"String\"/>" +
            "  </entity>" +
            "</entities>" +
            "</model>");
        List<EntityDef> entities = StateIntrospector.listStateEntities(appRoot, "orderProcessor");
        assertEquals(2, entities.size());
        assertEquals("Order", entities.get(0).getName());
        assertEquals(Integer.valueOf(1), entities.get(0).getId());
        assertEquals(2, entities.get(0).getFields().size());
        assertEquals("true", entities.get(0).getFields().get(0).getAttributes().get("isKey"));

        assertEquals("Trade", entities.get(1).getName());
        assertEquals(1, entities.get(1).getFields().size());
    }

    @Test
    public void listStateEntities_emptyWhenNoEntitiesContainer() throws Exception {
        PhaseBTestSupport.writeStateXml(appRoot, "orderProcessor",
            "<model xmlns=\"http://www.neeveresearch.com/schema/x-adml\">" +
            "<factories><factory name=\"StateFactory\" id=\"1\"/></factories>" +
            "</model>");
        assertTrue(StateIntrospector.listStateEntities(appRoot, "orderProcessor").isEmpty());
    }

    @Test
    public void listStateEntities_ignoresWrongNamespace() throws Exception {
        PhaseBTestSupport.writeStateXml(appRoot, "orderProcessor",
            "<model xmlns=\"http://example.com/not-adml\">" +
            "<entities><entity name=\"Order\"/></entities>" +
            "</model>");
        assertTrue(StateIntrospector.listStateEntities(appRoot, "orderProcessor").isEmpty());
    }

    @Test
    public void getStateEntity_returnsNullWhenAbsent() throws Exception {
        PhaseBTestSupport.writeStateXml(appRoot, "orderProcessor",
            "<model xmlns=\"http://www.neeveresearch.com/schema/x-adml\">" +
            "<entities><entity name=\"Order\"/></entities>" +
            "</model>");
        assertNull(StateIntrospector.getStateEntity(appRoot, "orderProcessor", "Missing"));
    }

    @Test
    public void getStateEntity_returnsMatchWhenPresent() throws Exception {
        PhaseBTestSupport.writeStateXml(appRoot, "orderProcessor",
            "<model xmlns=\"http://www.neeveresearch.com/schema/x-adml\">" +
            "<entities>" +
            "  <entity name=\"Order\" id=\"1\"><field name=\"id\" type=\"String\"/></entity>" +
            "  <entity name=\"Trade\" id=\"2\"><field name=\"symbol\" type=\"String\"/></entity>" +
            "</entities>" +
            "</model>");
        EntityDef trade = StateIntrospector.getStateEntity(appRoot, "orderProcessor", "Trade");
        assertNotNull(trade);
        assertEquals(Integer.valueOf(2), trade.getId());
        assertEquals("symbol", trade.getFields().get(0).getName());
    }

    @Test
    public void listCollections_returnsCollectionsWithAttributes() throws Exception {
        PhaseBTestSupport.writeStateXml(appRoot, "orderProcessor",
            "<model xmlns=\"http://www.neeveresearch.com/schema/x-adml\">" +
            "<entities/>" +
            "<collections>" +
            "  <collection name=\"OrdersBySymbol\" id=\"10\" keyField=\"symbol\"/>" +
            "  <collection name=\"OrdersById\" id=\"11\" keyField=\"id\"/>" +
            "</collections>" +
            "</model>");
        List<CollectionDef> collections = StateIntrospector.listCollections(appRoot, "orderProcessor");
        assertEquals(2, collections.size());
        assertEquals("OrdersBySymbol", collections.get(0).getName());
        assertEquals("symbol", collections.get(0).getAttributes().get("keyField"));
    }

    @Test
    public void listCollections_emptyWhenNoCollectionsContainer() throws Exception {
        PhaseBTestSupport.writeStateXml(appRoot, "orderProcessor",
            "<model xmlns=\"http://www.neeveresearch.com/schema/x-adml\">" +
            "<entities/>" +
            "</model>");
        assertTrue(StateIntrospector.listCollections(appRoot, "orderProcessor").isEmpty());
    }
}
