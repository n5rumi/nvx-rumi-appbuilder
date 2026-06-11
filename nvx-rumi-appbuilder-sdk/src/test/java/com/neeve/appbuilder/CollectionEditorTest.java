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
import com.neeve.appbuilder.model.ChangeSet;
import com.neeve.appbuilder.model.CollectionDef;
import com.neeve.appbuilder.test.TestAppFactory;
import org.junit.After;
import org.junit.Before;
import org.junit.Test;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;

import static org.junit.Assert.*;

/**
 * Exercises {@link CollectionEditor} against a real scaffolded processor's
 * state model: add/remove collections, shared type-id space with entities,
 * never-reuse id retirement, idempotency, and required-attribute validation.
 */
public class CollectionEditorTest {

    private Path tempDir;
    private Path appRoot;
    private static final String SVC = "order-processor";

    @Before
    public void setUp() throws Exception {
        tempDir = Files.createTempDirectory("collection-editor-");
        appRoot = TestAppFactory.newApp("demo").packageName("com.example.demo").scaffoldAt(tempDir);
        TestAppFactory.addProcessor(appRoot, SVC);
    }

    @After
    public void tearDown() throws IOException {
        TestAppFactory.deleteRecursive(tempDir);
    }

    private CollectionDef coll(String name) throws IOException {
        return StateIntrospector.getCollection(appRoot, SVC, name);
    }

    @Test
    public void addCollection_onState_appears() throws Exception {
        // 'contains' is a type reference (entity/message); the editor is low-level
        // and writes it verbatim — codegen, not the editor, enforces it's an entity.
        ChangeSet r = CollectionEditor.addCollection(appRoot, SVC, "ordersBySym", "StringMap", "Account", false);
        assertTrue(r.isApplied());
        CollectionDef c = coll("ordersBySym");
        assertNotNull(c);
        assertEquals("StringMap", c.getAttributes().get("is"));
        assertEquals("Account", c.getAttributes().get("contains"));
        // Repository entity already holds type id 1, so the collection gets 2.
        assertEquals(Integer.valueOf(2), c.getId());
    }

    @Test
    public void addCollection_sharesTypeIdSpaceWithEntities() throws Exception {
        EntityEditor.addEntity(appRoot, SVC, ModelScope.SERVICE_STATE, "Account", java.util.List.of(), false); // id 2
        CollectionEditor.addCollection(appRoot, SVC, "byId", "LongMap", "Account", false); // id 3
        assertEquals(Integer.valueOf(3), coll("byId").getId());
    }

    @Test
    public void addCollection_writesContainsVerbatim() throws Exception {
        // 'contains' is an entity/message reference, not a scalar, so it is NOT
        // run through scalar normalization — it is written exactly as given.
        CollectionEditor.addCollection(appRoot, SVC, "byAcct", "StringMap", "Account", false);
        assertEquals("Account", coll("byAcct").getAttributes().get("contains"));
    }

    @Test
    public void addCollection_isIdempotent() throws Exception {
        CollectionEditor.addCollection(appRoot, SVC, "q", "Queue", "Account", false);
        ChangeSet r = CollectionEditor.addCollection(appRoot, SVC, "q", "Queue", "Account", false);
        assertTrue(r.isNoop());
        assertEquals(1, StateIntrospector.listCollections(appRoot, SVC).size());
    }

    @Test
    public void addCollection_requiresIsAndContains() throws Exception {
        try {
            CollectionEditor.addCollection(appRoot, SVC, "bad", "", "String", false);
            fail("expected IllegalArgumentException for blank 'is'");
        } catch (IllegalArgumentException expected) { }
        try {
            CollectionEditor.addCollection(appRoot, SVC, "bad", "Queue", "  ", false);
            fail("expected IllegalArgumentException for blank 'contains'");
        } catch (IllegalArgumentException expected) { }
    }

    @Test
    public void removeCollection_retiresId_neverReused() throws Exception {
        CollectionEditor.addCollection(appRoot, SVC, "a", "Queue", "Account", false); // id 2
        CollectionEditor.addCollection(appRoot, SVC, "b", "Queue", "Account", false); // id 3
        CollectionEditor.removeCollection(appRoot, SVC, "b", false);

        String xml = Files.readString(AppIntrospector.resolveStateXmlFile(appRoot, SVC));
        assertTrue("removed collection leaves a reserved tombstone", xml.contains("id=3 reserved"));

        CollectionEditor.addCollection(appRoot, SVC, "c", "Queue", "Account", false);
        assertEquals("must not recycle the retired id 3", Integer.valueOf(4), coll("c").getId());
    }

    @Test
    public void removeCollection_noopWhenAbsent() throws Exception {
        assertTrue(CollectionEditor.removeCollection(appRoot, SVC, "nope", false).isNoop());
    }
}
