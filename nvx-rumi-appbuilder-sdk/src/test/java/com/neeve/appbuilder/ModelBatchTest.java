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

import com.neeve.appbuilder.model.BatchResult;
import com.neeve.appbuilder.model.CollectionDef;
import com.neeve.appbuilder.model.EntityDef;
import com.neeve.appbuilder.model.FieldDef;
import com.neeve.appbuilder.model.ModelEdit;
import com.neeve.appbuilder.test.TestAppFactory;
import org.junit.After;
import org.junit.Before;
import org.junit.Test;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.stream.Collectors;
import java.util.stream.Stream;

import static org.junit.Assert.*;

/** RUMI-412: batching a model build into one call. */
public class ModelBatchTest {

    private Path tempDir;
    private Path appRoot;

    @Before
    public void setUp() throws Exception {
        tempDir = Files.createTempDirectory("modelbatch-");
        appRoot = TestAppFactory.newApp("trading").packageName("com.example.trading").scaffoldAt(tempDir);
        TestAppFactory.addProcessor(appRoot, "proc");
    }

    @After
    public void tearDown() throws Exception {
        TestAppFactory.deleteRecursive(tempDir);
    }

    private static ModelEdit message(String svc, String name, FieldDef... fields) {
        return new ModelEdit(ModelEdit.Kind.MESSAGE, svc, name, null, List.of(fields), null, null);
    }

    private static FieldDef f(String name, String type) {
        return new FieldDef(name, type, Map.of());
    }

    /** Every model file's bytes, so a rollback can be checked exactly. */
    private Map<Path, String> modelSnapshot() throws Exception {
        try (Stream<Path> w = Files.walk(appRoot)) {
            return w.filter(Files::isRegularFile)
                .filter(p -> p.toString().contains("main/models") && p.toString().endsWith(".xml"))
                .collect(Collectors.toMap(p -> p, p -> {
                    try { return Files.readString(p); } catch (Exception e) { throw new RuntimeException(e); }
                }));
        }
    }

    // ---- the point of the ticket ---------------------------------------

    @Test
    public void oneCallBuildsAWholeModel() throws Exception {
        BatchResult r = ModelBatch.apply(appRoot, List.of(
            message("proc", "PlaceOrder", f("qty", "Long"), f("symbol", "String")),
            message("proc", "CancelOrder", f("orderId", "String")),
            new ModelEdit(ModelEdit.Kind.STATE_ENTITY, "proc", "Order", null,
                List.of(new FieldDef("id", "String", Map.of("isKey", "true"))), null, null),
            new ModelEdit(ModelEdit.Kind.FIELDS, "proc", "PlaceOrder", "messages",
                List.of(f("price", "Double")), null, null)
        ), false);

        assertTrue(r.isApplied());
        assertEquals(4, r.getItems().size());
        assertEquals(4, r.getChangedCount());
        assertEquals(2, MessageIntrospector.listMessages(appRoot, "proc",
            FieldEditor.ModelScope.SERVICE_MESSAGES).size());
    }

    /**
     * RUMI-424. The batch path called the overloads that pass an empty
     * attribute map, so {@code asEmbedded="true"} was dropped and a model
     * needing an embedded entity could not be authored in one call at all --
     * while the per-element tools accepted the same attribute.
     */
    @Test
    public void aStateEntityCarriesItsEntityLevelAttributes() throws Exception {
        ModelBatch.apply(appRoot, List.of(
            new ModelEdit(ModelEdit.Kind.STATE_ENTITY, "proc", "ProductProfile", null,
                List.of(new FieldDef("productName", "String", Map.of())),
                null, null, Map.of("asEmbedded", "true"))), false);

        EntityDef profile = StateIntrospector.getStateEntity(appRoot, "proc", "ProductProfile");
        assertEquals("asEmbedded survives the batch path",
            "true", profile.getAttributes().get("asEmbedded"));
    }

    @Test
    public void aMessageEntityCarriesItsEntityLevelAttributes() throws Exception {
        ModelBatch.apply(appRoot, List.of(
            new ModelEdit(ModelEdit.Kind.MESSAGE_ENTITY, "proc", "Money", null,
                List.of(new FieldDef("amount", "Long", Map.of())),
                null, null, Map.of("asEmbedded", "true"))), false);

        EntityDef money = MessageIntrospector.getEntity(appRoot, "proc", "Money",
            FieldEditor.ModelScope.SERVICE_MESSAGES);
        assertEquals("asEmbedded survives the batch path",
            "true", money.getAttributes().get("asEmbedded"));
    }

    /**
     * RUMI-424 review. A message entity used as a field type must be embedded or
     * ADM codegen rejects the referencing model, and being a field type is the
     * usual reason to declare one. add_message_entity defaults asEmbedded=true;
     * without the same default here the two paths produced different models from
     * the same request, and the batch one did not compile.
     */
    @Test
    public void aMessageEntityIsEmbeddedByDefault() throws Exception {
        ModelBatch.apply(appRoot, List.of(
            new ModelEdit(ModelEdit.Kind.MESSAGE_ENTITY, "proc", "Money", null,
                List.of(new FieldDef("amount", "Long", Map.of())), null, null)), false);

        EntityDef money = MessageIntrospector.getEntity(appRoot, "proc", "Money",
            FieldEditor.ModelScope.SERVICE_MESSAGES);
        assertEquals("defaults to embedded, like the per-element tool",
            "true", money.getAttributes().get("asEmbedded"));
    }

    @Test
    public void anExplicitAsEmbeddedFalseIsHonoured() throws Exception {
        ModelBatch.apply(appRoot, List.of(
            new ModelEdit(ModelEdit.Kind.MESSAGE_ENTITY, "proc", "Standalone", null,
                List.of(new FieldDef("v", "Long", Map.of())),
                null, null, Map.of("asEmbedded", "false"))), false);

        EntityDef e = MessageIntrospector.getEntity(appRoot, "proc", "Standalone",
            FieldEditor.ModelScope.SERVICE_MESSAGES);
        assertEquals("the default never overrides an explicit choice",
            "false", e.getAttributes().get("asEmbedded"));
    }

    /** RUMI-424 review: the same silent drop, one case label along. */
    @Test
    public void aCollectionCarriesItsAttributes() throws Exception {
        ModelBatch.apply(appRoot, List.of(
            new ModelEdit(ModelEdit.Kind.STATE_ENTITY, "proc", "Order", null,
                List.of(new FieldDef("id", "String", Map.of("isKey", "true"))), null, null),
            new ModelEdit(ModelEdit.Kind.COLLECTION, "proc", "Orders", null,
                List.of(), "StringMap", "Order", Map.of("transactional", "true"))), false);

        CollectionDef orders = StateIntrospector.getCollection(appRoot, "proc", "Orders");
        assertEquals("collection attributes survive the batch path",
            "true", orders.getAttributes().get("transactional"));
    }

    /**
     * Nothing on the MESSAGE or FIELDS path can carry entity attributes, so
     * accepting them could only discard them -- which is the failure this whole
     * ticket is about. Refuse instead.
     */
    @Test
    public void attributesOnAKindThatCannotApplyThemAreRefused() {
        for (ModelEdit.Kind k : List.of(ModelEdit.Kind.MESSAGE, ModelEdit.Kind.FIELDS)) {
            try {
                ModelBatch.apply(appRoot, List.of(
                    new ModelEdit(k, "proc", "Thing", "messages",
                        List.of(new FieldDef("v", "Long", Map.of())),
                        null, null, Map.of("asEmbedded", "true"))), false);
                fail("expected " + k + " to refuse entity attributes");
            } catch (IllegalArgumentException expected) {
                assertTrue("names the offending key: " + expected.getMessage(),
                    expected.getMessage().contains("attributes"));
            } catch (Exception e) {
                fail("expected IllegalArgumentException for " + k + ", got " + e);
            }
        }
    }

    /**
     * RUMI-427 review. A state entity and a collection always write the state
     * model, so a scope saying otherwise was accepted and then dropped -- the
     * same accept-and-drop trap as RUMI-424, and it would have written to the
     * state model while reporting success.
     */
    @Test
    public void aScopeThatContradictsWhereTheKindWritesIsRefused() {
        for (ModelEdit.Kind k : List.of(ModelEdit.Kind.STATE_ENTITY, ModelEdit.Kind.COLLECTION)) {
            try {
                ModelBatch.apply(appRoot, List.of(
                    new ModelEdit(k, "proc", "Thing", "messages",
                        List.of(new FieldDef("id", "String", Map.of("isKey", "true"))),
                        "StringMap", "Order")), false);
                fail("expected " + k + " to refuse scope 'messages'");
            } catch (IllegalArgumentException expected) {
                assertTrue("explains where the kind writes: " + expected.getMessage(),
                    expected.getMessage().contains("state model"));
            } catch (Exception e) {
                fail("expected IllegalArgumentException for " + k + ", got " + e);
            }
        }
    }

    /** Saying 'state' explicitly is redundant but true, so it is allowed. */
    @Test
    public void anExplicitStateScopeIsAccepted() throws Exception {
        ModelBatch.apply(appRoot, List.of(
            new ModelEdit(ModelEdit.Kind.STATE_ENTITY, "proc", "Redundant", "state",
                List.of(new FieldDef("id", "String", Map.of("isKey", "true"))), null, null)), false);

        assertNotNull("the entity was created",
            StateIntrospector.getStateEntity(appRoot, "proc", "Redundant"));
    }

    /** An edit with no attributes must be unchanged by the new parameter. */
    @Test
    public void anEntityWithoutAttributesIsUnaffected() throws Exception {
        ModelBatch.apply(appRoot, List.of(
            new ModelEdit(ModelEdit.Kind.STATE_ENTITY, "proc", "Plain", null,
                List.of(new FieldDef("id", "String", Map.of("isKey", "true"))),
                null, null)), false);

        EntityDef plain = StateIntrospector.getStateEntity(appRoot, "proc", "Plain");
        assertNull("no asEmbedded was asked for", plain.getAttributes().get("asEmbedded"));
    }

    @Test
    public void itemsApplyInOrderSoAFieldCanFollowTheMessageThatDefinesIt() throws Exception {
        // Reordering by kind would break this, which is the normal shape.
        ModelBatch.apply(appRoot, List.of(
            message("proc", "Tick"),
            new ModelEdit(ModelEdit.Kind.FIELDS, "proc", "Tick", "messages",
                List.of(f("last", "Double")), null, null)
        ), false);

        assertTrue(Files.readString(FieldEditor.resolveModelFile(appRoot, "proc",
            FieldEditor.ModelScope.SERVICE_MESSAGES)).contains("last"));
    }

    // ---- all-or-nothing -------------------------------------------------

    /**
     * The load-bearing claim. A batch that fails partway must leave the app
     * exactly as it was — otherwise "apply my model" becomes a call you cannot
     * safely retry.
     */
    @Test
    public void aFailedItemRollsTheWholeBatchBack() throws Exception {
        Map<Path, String> before = modelSnapshot();

        try {
            ModelBatch.apply(appRoot, List.of(
                message("proc", "GoodOne", f("a", "Long")),          // applies
                new ModelEdit(ModelEdit.Kind.FIELDS, "proc", "NoSuchMessage", "messages",
                    List.of(f("b", "Long")), null, null)             // fails
            ), false);
            fail("a field on a message that does not exist must fail the batch");
        } catch (Exception expected) {
            // the failure itself is what the caller needs; the state is the test
        }

        assertEquals("the batch must leave every model file exactly as it was",
            before, modelSnapshot());
    }

    @Test
    public void aMalformedItemIsRejectedBeforeAnythingIsWritten() throws Exception {
        Map<Path, String> before = modelSnapshot();
        try {
            ModelBatch.apply(appRoot, List.of(
                message("proc", "WouldHaveApplied", f("a", "Long")),
                new ModelEdit(ModelEdit.Kind.COLLECTION, "proc", "Orders", null, List.of(), null, null)
            ), false);
            fail("a collection without is/contains must be rejected");
        } catch (IllegalArgumentException expected) {
            assertTrue(expected.getMessage().contains("Orders"));
        }
        assertEquals("validation happens before any write", before, modelSnapshot());
    }

    @Test
    public void aDryRunWritesNothing() throws Exception {
        Map<Path, String> before = modelSnapshot();
        BatchResult r = ModelBatch.apply(appRoot, List.of(
            message("proc", "Ghost", f("a", "Long"))), true);
        assertFalse(r.isApplied());
        assertEquals(before, modelSnapshot());
    }

    // ---- re-applying a model -------------------------------------------

    @Test
    public void reApplyingTheSameBatchIsSafeAndReportsWhatWasAlreadyThere() throws Exception {
        List<ModelEdit> edits = List.of(message("proc", "PlaceOrder", f("qty", "Long")));
        ModelBatch.apply(appRoot, edits, false);
        Map<Path, String> afterFirst = modelSnapshot();

        BatchResult second = ModelBatch.apply(appRoot, edits, false);

        assertEquals("a re-apply must not rewrite the file", afterFirst, modelSnapshot());
        assertEquals("and it must say so rather than claiming a change",
            0, second.getChangedCount());
        assertTrue(second.getItems().get(0).isNoop());
    }

    @Test
    public void anEmptyBatchIsHarmless() throws Exception {
        BatchResult r = ModelBatch.apply(appRoot, new ArrayList<>(), false);
        assertTrue(r.getItems().isEmpty());
        assertFalse(r.isApplied());
    }

    @Test
    public void anUnknownScopeIsRejectedByName() throws Exception {
        try {
            ModelBatch.apply(appRoot, List.of(
                new ModelEdit(ModelEdit.Kind.FIELDS, "proc", "X", "sate",
                    List.of(f("a", "Long")), null, null)), false);
            fail("a mistyped scope must be rejected, not silently defaulted");
        } catch (IllegalArgumentException expected) {
            assertTrue(expected.getMessage().contains("sate"));
        }
    }

    // ---- review follow-ups ----------------------------------------------

    /**
     * MESSAGE went through the 5-arg addMessage, which hard-codes
     * SERVICE_MESSAGES — so scope:"roe" wrote into the service's private model
     * and returned success. Silently writing to the wrong file is worse than
     * failing.
     */
    @Test
    public void aRoeScopedMessageLandsInRoeNotTheServiceModel() throws Exception {
        ModelBatch.apply(appRoot, List.of(
            new ModelEdit(ModelEdit.Kind.MESSAGE, "proc", "SharedEvent", "roe",
                List.of(f("id", "String")), null, null)), false);

        String roe = Files.readString(FieldEditor.resolveModelFile(appRoot, "proc",
            FieldEditor.ModelScope.ROE_MESSAGES));
        String svc = Files.readString(FieldEditor.resolveModelFile(appRoot, "proc",
            FieldEditor.ModelScope.SERVICE_MESSAGES));
        assertTrue("the ROE model must carry it", roe.contains("SharedEvent"));
        assertFalse("the service model must not", svc.contains("SharedEvent"));
    }

    @Test
    public void aBadScopeIsRejectedOnEveryKindNotJustFields() throws Exception {
        for (ModelEdit.Kind kind : List.of(ModelEdit.Kind.MESSAGE, ModelEdit.Kind.STATE_ENTITY)) {
            try {
                ModelBatch.apply(appRoot, List.of(
                    new ModelEdit(kind, "proc", "X", "sate", List.of(f("a", "Long")), null, null)), false);
                fail("a mistyped scope must be rejected on " + kind);
            } catch (IllegalArgumentException expected) {
                assertTrue(expected.getMessage().contains("sate"));
            }
        }
    }

    /**
     * A message and a state entity sharing a name is routine, so defaulting a
     * FIELDS edit to the message model would append a state entity's fields to
     * the message and report success.
     */
    @Test
    public void aFieldsEditWithoutAScopeIsRefusedRatherThanGuessed() throws Exception {
        ModelBatch.apply(appRoot, List.of(
            new ModelEdit(ModelEdit.Kind.MESSAGE, "proc", "Order", null, List.of(f("a", "Long")), null, null),
            new ModelEdit(ModelEdit.Kind.STATE_ENTITY, "proc", "Order", null,
                List.of(new FieldDef("id", "String", Map.of("isKey", "true"))), null, null)), false);

        try {
            ModelBatch.apply(appRoot, List.of(
                new ModelEdit(ModelEdit.Kind.FIELDS, "proc", "Order", null,
                    List.of(f("extra", "Long")), null, null)), false);
            fail("with a message and a state entity both named Order, guessing is not acceptable");
        } catch (IllegalArgumentException expected) {
            assertTrue(expected.getMessage().contains("scope"));
        }
    }

    @Test
    public void scopeAliasesMatchWhatTheFieldsEndpointAccepts() throws Exception {
        // The same value must not work on one endpoint and 400 on the other.
        ModelBatch.apply(appRoot, List.of(
            new ModelEdit(ModelEdit.Kind.MESSAGE, "proc", "Aliased", " Service_Messages ",
                List.of(f("a", "Long")), null, null)), false);
        assertTrue(Files.readString(FieldEditor.resolveModelFile(appRoot, "proc",
            FieldEditor.ModelScope.SERVICE_MESSAGES)).contains("Aliased"));
    }

    @Test
    public void aNamelessFieldIsRejectedBeforeAnythingIsWritten() throws Exception {
        Map<Path, String> before = modelSnapshot();
        try {
            ModelBatch.apply(appRoot, List.of(
                message("proc", "Broken", new FieldDef(null, "Long", Map.of()))), false);
            fail("a field with no name must be a rejected request, not an NPE");
        } catch (IllegalArgumentException expected) {
            assertTrue(expected.getMessage().toLowerCase().contains("name"));
        }
        assertEquals(before, modelSnapshot());
    }
}
