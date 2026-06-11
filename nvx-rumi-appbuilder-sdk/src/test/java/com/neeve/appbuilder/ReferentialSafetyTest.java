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

import com.neeve.appbuilder.FieldEditor.ModelScope;
import com.neeve.appbuilder.model.FieldDef;
import com.neeve.appbuilder.test.TestAppFactory;
import org.junit.After;
import org.junit.Before;
import org.junit.Test;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.Map;

import static org.junit.Assert.*;

/**
 * Slice-3D referential safety: a model element can't be removed while something
 * still points at it, unless removal is forced.
 */
public class ReferentialSafetyTest {

    private Path tempDir;
    private Path appRoot;
    private static final String SVC = "order-processor";

    @Before
    public void setUp() throws Exception {
        tempDir = Files.createTempDirectory("ref-safety-");
        appRoot = TestAppFactory.newApp("demo").packageName("com.example.demo").scaffoldAt(tempDir);
        TestAppFactory.addProcessor(appRoot, SVC);
    }

    @After
    public void tearDown() throws IOException {
        TestAppFactory.deleteRecursive(tempDir);
    }

    // --- entity referenced by a message field --------------------------

    @Test
    public void removeEntity_blockedWhenReferencedByField_forceOverrides() throws Exception {
        EntityEditor.addEntity(appRoot, SVC, ModelScope.SERVICE_MESSAGES, "Money",
            Map.of("asEmbedded", "true"), List.of(new FieldDef("amount", "Long", Map.of())), false);
        MessageEditor.addMessage(appRoot, SVC, ModelScope.SERVICE_MESSAGES, "Tick",
            List.of(new FieldDef("notional", "Money", Map.of())), false);

        try {
            EntityEditor.removeEntity(appRoot, SVC, ModelScope.SERVICE_MESSAGES, "Money", false);
            fail("expected removal to be blocked by the referencing field");
        } catch (IllegalStateException expected) {
            assertTrue(expected.getMessage().contains("notional"));
        }
        // still there
        assertNotNull(MessageIntrospector.getEntity(appRoot, SVC, "Money", ModelScope.SERVICE_MESSAGES));

        // force removes it (leaving the model dangling until the field is fixed)
        EntityEditor.removeEntity(appRoot, SVC, ModelScope.SERVICE_MESSAGES, "Money", false, true);
        assertNull(MessageIntrospector.getEntity(appRoot, SVC, "Money", ModelScope.SERVICE_MESSAGES));
    }

    // --- entity referenced by a collection -----------------------------

    @Test
    public void removeEntity_blockedWhenReferencedByCollection() throws Exception {
        EntityEditor.addEntity(appRoot, SVC, ModelScope.SERVICE_STATE, "Account",
            List.of(new FieldDef("id", "String", Map.of("isKey", "true"))), false);
        CollectionEditor.addCollection(appRoot, SVC, "byId", "StringMap", "Account", false);

        try {
            StateEditor.removeStateEntity(appRoot, SVC, "Account", false);
            fail("expected removal to be blocked by the referencing collection");
        } catch (IllegalStateException expected) {
            assertTrue(expected.getMessage().contains("byId"));
        }
        assertTrue(StateEditor.removeStateEntity(appRoot, SVC, "Account", false, true).isApplied());
    }

    // --- message referenced by an @EventHandler ------------------------

    @Test
    public void removeMessage_blockedWhenHandledByEventHandler_forceOverrides() throws Exception {
        MessageEditor.addMessage(appRoot, SVC, "Order", List.of(new FieldDef("sym", "String", Map.of())), false);
        JavaSourceEditor.addHandler(appRoot, SVC, "onOrder", "Order", "/* count */", false);

        try {
            MessageEditor.removeMessage(appRoot, SVC, "Order", false);
            fail("expected removal to be blocked by the @EventHandler");
        } catch (IllegalStateException expected) {
            assertTrue(expected.getMessage().contains("onOrder"));
        }
        assertNotNull(MessageIntrospector.getMessage(appRoot, SVC, "Order"));

        assertTrue(MessageEditor.removeMessage(appRoot, SVC, ModelScope.SERVICE_MESSAGES, "Order", false, true).isApplied());
        assertNull(MessageIntrospector.getMessage(appRoot, SVC, "Order"));
    }

    @Test
    public void removeMessage_allowedWhenUnreferenced() throws Exception {
        MessageEditor.addMessage(appRoot, SVC, "Lonely", List.of(), false);
        assertTrue(MessageEditor.removeMessage(appRoot, SVC, "Lonely", false).isApplied());
    }
}
