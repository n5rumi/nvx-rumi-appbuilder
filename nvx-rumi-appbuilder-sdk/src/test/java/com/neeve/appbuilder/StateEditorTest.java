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
import com.neeve.appbuilder.model.EntityDef;
import com.neeve.appbuilder.model.FieldDef;
import org.junit.After;
import org.junit.Before;
import org.junit.Test;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Collections;
import java.util.List;
import java.util.Map;
import java.util.stream.Stream;

import static org.junit.Assert.*;

public class StateEditorTest {

    private Path tempDir;
    private Path appRoot;

    private static final String EMPTY_STATE =
        "<model xmlns=\"http://www.neeveresearch.com/schema/x-adml\">" +
        "<factories><factory name=\"StateFactory\" id=\"11\"/></factories>" +
        "<entities/>" +
        "</model>";

    @Before
    public void setUp() throws IOException {
        tempDir = Files.createTempDirectory("stateedit-");
        appRoot = PhaseBTestSupport.scaffoldApp(tempDir, "trading", "com.example.trading");
        PhaseBTestSupport.writeStateXml(appRoot, "orderProcessor", EMPTY_STATE);
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
    public void addStateEntity_insertsWithAutoId() throws Exception {
        ChangeSet r = StateEditor.addStateEntity(appRoot, "orderProcessor", "Order",
            List.of(new FieldDef("id", "String", Map.of("key", "true")),
                    new FieldDef("status", "String", Map.of())),
            false);
        assertTrue(r.isApplied());
        EntityDef order = StateIntrospector.getStateEntity(appRoot, "orderProcessor", "Order");
        assertNotNull(order);
        assertEquals(Integer.valueOf(1), order.getId());
        assertEquals(2, order.getFields().size());
        assertEquals("true", order.getFields().get(0).getAttributes().get("key"));
    }

    @Test
    public void addStateEntity_isIdempotent() throws Exception {
        StateEditor.addStateEntity(appRoot, "orderProcessor", "Order",
            Collections.emptyList(), false);
        ChangeSet r = StateEditor.addStateEntity(appRoot, "orderProcessor", "Order",
            Collections.emptyList(), false);
        assertTrue(r.isNoop());
        assertEquals(1, StateIntrospector.listStateEntities(appRoot, "orderProcessor").size());
    }

    @Test
    public void removeStateEntity_stripsElement() throws Exception {
        StateEditor.addStateEntity(appRoot, "orderProcessor", "Order",
            Collections.emptyList(), false);
        ChangeSet r = StateEditor.removeStateEntity(appRoot, "orderProcessor", "Order", false);
        assertTrue(r.isApplied());
        assertTrue(StateIntrospector.listStateEntities(appRoot, "orderProcessor").isEmpty());
    }

    @Test
    public void removeStateEntity_noopWhenAbsent() throws Exception {
        ChangeSet r = StateEditor.removeStateEntity(appRoot, "orderProcessor", "Missing", false);
        assertTrue(r.isNoop());
    }

    @Test
    public void removeStateEntity_retiresId_neverReused() throws Exception {
        StateEditor.addStateEntity(appRoot, "orderProcessor", "A", Collections.emptyList(), false); // id 1
        StateEditor.addStateEntity(appRoot, "orderProcessor", "B", Collections.emptyList(), false); // id 2
        StateEditor.removeStateEntity(appRoot, "orderProcessor", "B", false);

        String xml = Files.readString(AppIntrospector.resolveStateXmlFile(appRoot, "orderProcessor"));
        assertTrue("removed entity leaves a reserved tombstone", xml.contains("id=2 reserved"));

        StateEditor.addStateEntity(appRoot, "orderProcessor", "C", Collections.emptyList(), false);
        assertEquals("must not recycle the retired id 2", Integer.valueOf(3),
            StateIntrospector.getStateEntity(appRoot, "orderProcessor", "C").getId());
    }

    @Test
    public void addStateEntity_dryRunDoesNotTouchDisk() throws Exception {
        String before = Files.readString(AppIntrospector.resolveStateXmlFile(appRoot, "orderProcessor"));
        ChangeSet r = StateEditor.addStateEntity(appRoot, "orderProcessor", "Order",
            Collections.emptyList(), true);
        assertFalse(r.isApplied());
        assertEquals(before, Files.readString(AppIntrospector.resolveStateXmlFile(appRoot, "orderProcessor")));
    }

    @Test
    public void addStateEntity_throwsWhenStateXmlMissing() throws Exception {
        try {
            StateEditor.addStateEntity(appRoot, "feederNoState", "Whatever",
                Collections.emptyList(), false);
            fail("expected IOException");
        } catch (IOException expected) {
            assertTrue(expected.getMessage().contains("state.xml not found"));
        }
    }
}
