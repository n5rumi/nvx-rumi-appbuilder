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
import com.neeve.appbuilder.model.EntityDef;
import com.neeve.appbuilder.model.FieldDef;
import com.neeve.appbuilder.model.MessageDef;
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
 * Exercises {@link EntityEditor} against a real scaffolded processor across all
 * three model scopes — service state, the service message model (embedded
 * entities), and the shared ROE model — covering add/remove, idempotency, and
 * the never-reuse id retirement on removal.
 */
public class EntityEditorTest {

    private Path tempDir;
    private Path appRoot;
    private static final String SVC = "order-processor";

    @Before
    public void setUp() throws Exception {
        tempDir = Files.createTempDirectory("entity-editor-");
        appRoot = TestAppFactory.newApp("demo").packageName("com.example.demo").scaffoldAt(tempDir);
        TestAppFactory.addProcessor(appRoot, SVC);
    }

    @After
    public void tearDown() throws IOException {
        TestAppFactory.deleteRecursive(tempDir);
    }

    // --- service state entities (StateEditor delegates here) -----------

    @Test
    public void addEntity_onState_appearsInStateModel() throws Exception {
        ChangeSet r = EntityEditor.addEntity(appRoot, SVC, ModelScope.SERVICE_STATE, "Account",
            List.of(new FieldDef("id", "String", Map.of("isKey", "true"))), false);
        assertTrue(r.isApplied());
        EntityDef e = StateIntrospector.getStateEntity(appRoot, SVC, "Account");
        assertNotNull(e);
        assertEquals("true", e.getFields().get(0).getAttributes().get("isKey"));
        // The scaffolded Repository already holds id 1, so Account gets the next.
        assertEquals(Integer.valueOf(2), e.getId());
    }

    // --- embedded entities in the service message model ----------------

    @Test
    public void addEntity_onServiceMessages_isEmbeddedNotState() throws Exception {
        EntityEditor.addEntity(appRoot, SVC, ModelScope.SERVICE_MESSAGES, "Money",
            List.of(new FieldDef("amount", "long", Map.of()),
                    new FieldDef("ccy", "String", Map.of())), false);

        EntityDef money = MessageIntrospector.getEntity(appRoot, SVC, "Money", ModelScope.SERVICE_MESSAGES);
        assertNotNull("embedded entity lives in the message model", money);
        assertEquals(2, money.getFields().size());

        // It must NOT have leaked into the state model.
        assertNull(StateIntrospector.getStateEntity(appRoot, SVC, "Money"));
    }

    @Test
    public void messageAndEmbeddedEntity_shareTheTypeIdSpace() throws Exception {
        // A message and an embedded entity in the same model draw from one
        // factory id-space, so the entity's id follows the message's.
        MessageEditor.addMessage(appRoot, SVC, "Trade",
            List.of(new FieldDef("px", "double", Map.of())), false);
        EntityEditor.addEntity(appRoot, SVC, ModelScope.SERVICE_MESSAGES, "Leg",
            List.of(new FieldDef("qty", "long", Map.of())), false);

        MessageDef trade = MessageIntrospector.getMessage(appRoot, SVC, "Trade");
        EntityDef leg = MessageIntrospector.getEntity(appRoot, SVC, "Leg", ModelScope.SERVICE_MESSAGES);
        assertEquals(Integer.valueOf(1), trade.getId());
        assertEquals("entity shares the message factory id-space", Integer.valueOf(2), leg.getId());
    }

    @Test
    public void addField_onEmbeddedEntity_viaFieldEditor() throws Exception {
        EntityEditor.addEntity(appRoot, SVC, ModelScope.SERVICE_MESSAGES, "Money",
            List.of(new FieldDef("amount", "long", Map.of())), false);
        FieldEditor.addField(appRoot, SVC, ModelScope.SERVICE_MESSAGES, "Money", "ccy", "String", Map.of(), false);

        EntityDef money = MessageIntrospector.getEntity(appRoot, SVC, "Money", ModelScope.SERVICE_MESSAGES);
        assertTrue(money.getFields().stream().anyMatch(f -> "ccy".equals(f.getName())));
    }

    // --- shared ROE model ---------------------------------------------

    @Test
    public void addEntity_onRoe_appearsInRoeModel() throws Exception {
        EntityEditor.addEntity(appRoot, SVC, ModelScope.ROE_MESSAGES, "Customer",
            List.of(new FieldDef("name", "String", Map.of())), false);
        EntityDef c = MessageIntrospector.getEntity(appRoot, SVC, "Customer", ModelScope.ROE_MESSAGES);
        assertNotNull("entity added to the shared ROE model", c);
        // ROE serviceName is ignored — same result for any service name.
        assertNotNull(MessageIntrospector.getEntity(appRoot, "any-other-service", "Customer", ModelScope.ROE_MESSAGES));
    }

    // --- semantics: idempotency, removal, never-reuse -----------------

    @Test
    public void addEntity_isIdempotent() throws Exception {
        EntityEditor.addEntity(appRoot, SVC, ModelScope.SERVICE_MESSAGES, "Money", List.of(), false);
        ChangeSet r = EntityEditor.addEntity(appRoot, SVC, ModelScope.SERVICE_MESSAGES, "Money", List.of(), false);
        assertTrue(r.isNoop());
        assertEquals(1, MessageIntrospector.listEntities(appRoot, SVC, ModelScope.SERVICE_MESSAGES).size());
    }

    @Test
    public void removeEntity_retiresId_andNextAddDoesNotReuse() throws Exception {
        EntityEditor.addEntity(appRoot, SVC, ModelScope.SERVICE_MESSAGES, "A", List.of(), false); // id 1
        EntityEditor.addEntity(appRoot, SVC, ModelScope.SERVICE_MESSAGES, "B", List.of(), false); // id 2
        EntityEditor.removeEntity(appRoot, SVC, ModelScope.SERVICE_MESSAGES, "B", false);
        assertNull(MessageIntrospector.getEntity(appRoot, SVC, "B", ModelScope.SERVICE_MESSAGES));

        String xml = Files.readString(AppIntrospector.resolveMessagesXmlFile(appRoot, SVC));
        assertTrue("removed entity leaves a reserved tombstone", xml.contains("id=2 reserved"));

        EntityEditor.addEntity(appRoot, SVC, ModelScope.SERVICE_MESSAGES, "C", List.of(), false);
        assertEquals("must not recycle the retired id 2", Integer.valueOf(3),
            MessageIntrospector.getEntity(appRoot, SVC, "C", ModelScope.SERVICE_MESSAGES).getId());
    }

    @Test
    public void removeEntity_noopWhenAbsent() throws Exception {
        ChangeSet r = EntityEditor.removeEntity(appRoot, SVC, ModelScope.SERVICE_MESSAGES, "Nope", false);
        assertTrue(r.isNoop());
    }

    @Test
    public void addEntity_withEntityAttributes_setsAsEmbedded() throws Exception {
        EntityEditor.addEntity(appRoot, SVC, ModelScope.ROE_MESSAGES, "Money",
            Map.of("asEmbedded", "true"),
            List.of(new FieldDef("amount", "Long", Map.of())), false);
        EntityDef money = MessageIntrospector.getEntity(appRoot, SVC, "Money", ModelScope.ROE_MESSAGES);
        assertEquals("true", money.getAttributes().get("asEmbedded"));
    }

    @Test
    public void addEntity_ignoresNameAndIdInAttributes() throws Exception {
        EntityEditor.addEntity(appRoot, SVC, ModelScope.SERVICE_MESSAGES, "Real",
            Map.of("name", "Evil", "id", "999", "asEmbedded", "true"),
            List.of(), false);
        // The param name wins; no entity called "Evil" exists.
        assertNotNull(MessageIntrospector.getEntity(appRoot, SVC, "Real", ModelScope.SERVICE_MESSAGES));
        assertNull(MessageIntrospector.getEntity(appRoot, SVC, "Evil", ModelScope.SERVICE_MESSAGES));
        // The id is allocator-assigned (1 in a fresh message model), not the caller's 999.
        assertEquals(Integer.valueOf(1),
            MessageIntrospector.getEntity(appRoot, SVC, "Real", ModelScope.SERVICE_MESSAGES).getId());
    }

    @Test
    public void addEntity_dryRunDoesNotTouchDisk() throws Exception {
        Path roe = AppIntrospector.resolveRoeMessagesXmlFile(appRoot);
        String before = Files.readString(roe);
        ChangeSet r = EntityEditor.addEntity(appRoot, SVC, ModelScope.ROE_MESSAGES, "Ghost", List.of(), true);
        assertFalse(r.isApplied());
        assertEquals(before, Files.readString(roe));
    }
}
