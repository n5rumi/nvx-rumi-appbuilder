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
import com.neeve.appbuilder.test.ProjectValidity;
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
 * RUMI-378. Every mutating operation is applied to a real scaffolded app and
 * the files it touched are then <em>schema-validated</em>.
 *
 * <p>The suites this replaces were good at what they covered and did not
 * cover this. The editor tests re-read their output through their own
 * introspector, which proves the output is parseable by the component that
 * wrote it and nothing more; the REST tests assert status codes and JSON
 * substrings; the MCP tests mock the HTTP layer entirely. Nothing anywhere
 * asserted that a project was still valid after an edit.
 *
 * <p>Coverage rides on {@link ChangeSet} rather than a hand-written list of
 * files, so it follows each operation's actual blast radius. The companion
 * guard {@link MutatingOperationCoverageTest} makes sure a newly added
 * operation cannot skip this suite quietly.
 */
public class MutatingOperationValidityTest {

    private Path tempDir;
    private Path appRoot;

    @Before
    public void setUp() throws Exception {
        tempDir = Files.createTempDirectory("mutvalid-");
        appRoot = TestAppFactory.newApp("trading")
            .packageName("com.example.trading")
            .scaffoldAt(tempDir);
    }

    @After
    public void tearDown() throws IOException {
        TestAppFactory.deleteRecursive(tempDir);
    }

    /** Apply an operation, then validate every file it says it touched. */
    private static ChangeSet valid(ChangeSet cs) throws IOException {
        ProjectValidity.assertChangeSetValid(cs);
        return cs;
    }

    // --- app + service lifecycle --------------------------------------

    @Test
    public void scaffoldingAnAppLeavesItValid() throws Exception {
        ProjectValidity.assertProjectValid(appRoot);
    }

    @Test
    public void addingEachServiceTypeLeavesTheProjectValid() throws Exception {
        TestAppFactory.addProcessor(appRoot, "order-processor");
        ProjectValidity.assertProjectValid(appRoot);

        TestAppFactory.addDriver(appRoot, "feeder");
        ProjectValidity.assertProjectValid(appRoot);

        TestAppFactory.addConnector(appRoot, "sink");
        ProjectValidity.assertProjectValid(appRoot);

        TestAppFactory.addWebservice(appRoot, "gateway");
        ProjectValidity.assertProjectValid(appRoot);
    }

    @Test
    public void removingAServiceLeavesTheProjectValid() throws Exception {
        TestAppFactory.addProcessor(appRoot, "order-processor");
        valid(ServiceRemover.removeService(appRoot, "order-processor", false));
        // A removal reverts config fragments and parent-POM references too,
        // which reach past the files the change set names.
        ProjectValidity.assertProjectValid(appRoot);
    }

    // --- handlers ------------------------------------------------------

    @Test
    public void addingAndRemovingAHandlerLeavesTheProjectValid() throws Exception {
        TestAppFactory.addProcessor(appRoot, "order-processor");
        MessageEditor.addMessage(appRoot, "order-processor", "OrderRequest",
            List.of(new FieldDef("qty", "Long", Map.of())), false);

        valid(JavaSourceEditor.addHandler(appRoot, "order-processor", "onOrder",
            "OrderRequest", null, false));
        ProjectValidity.assertProjectValid(appRoot);

        valid(JavaSourceEditor.removeHandler(appRoot, "order-processor", "onOrder", false));
        ProjectValidity.assertProjectValid(appRoot);
    }

    // --- connectors ----------------------------------------------------

    @Test
    public void addingAndRemovingAConnectorLeavesTheProjectValid() throws Exception {
        TestAppFactory.addProcessor(appRoot, "order-processor");

        valid(ConnectorEditor.addConnector(appRoot, "order-processor", "FeedConnector", false));
        ProjectValidity.assertProjectValid(appRoot);

        valid(ConnectorEditor.removeConnector(appRoot, "order-processor", "FeedConnector", false));
        ProjectValidity.assertProjectValid(appRoot);
    }

    // --- messages ------------------------------------------------------

    @Test
    public void addingAndRemovingAMessageLeavesTheProjectValid() throws Exception {
        TestAppFactory.addProcessor(appRoot, "order-processor");

        valid(MessageEditor.addMessage(appRoot, "order-processor", "PlaceOrder",
            List.of(new FieldDef("qty", "Long", Map.of()),
                    new FieldDef("symbol", "String", Map.of())), false));
        valid(MessageEditor.removeMessage(appRoot, "order-processor", "PlaceOrder", false));
    }

    @Test
    public void addingAMessageToTheSharedRoeModelLeavesItValid() throws Exception {
        TestAppFactory.addProcessor(appRoot, "order-processor");

        valid(MessageEditor.addMessage(appRoot, "order-processor",
            FieldEditor.ModelScope.ROE_MESSAGES, "Tick",
            List.of(new FieldDef("px", "Double", Map.of())), false));
        valid(MessageEditor.removeMessage(appRoot, "order-processor",
            FieldEditor.ModelScope.ROE_MESSAGES, "Tick", false));
    }

    // --- entities ------------------------------------------------------

    @Test
    public void addingAndRemovingAStateEntityLeavesTheProjectValid() throws Exception {
        TestAppFactory.addProcessor(appRoot, "order-processor");

        valid(StateEditor.addStateEntity(appRoot, "order-processor", "Order",
            List.of(new FieldDef("id", "String", Map.of("isKey", "true")),
                    new FieldDef("qty", "Long", Map.of())), false));
        valid(StateEditor.removeStateEntity(appRoot, "order-processor", "Order", false));
    }

    /**
     * An entity used as a message field type must be asEmbedded. Exercising
     * the rule through the editors proves the builder can express a valid
     * embedded entity, not merely that the validator rejects an invalid one.
     */
    @Test
    public void addingAnEmbeddedEntityUsedAsAFieldTypeLeavesTheProjectValid() throws Exception {
        TestAppFactory.addProcessor(appRoot, "order-processor");

        valid(EntityEditor.addEntity(appRoot, "order-processor",
            FieldEditor.ModelScope.SERVICE_MESSAGES, "Money",
            Map.of("asEmbedded", "true"),
            List.of(new FieldDef("amount", "Long", Map.of())), false));
        valid(MessageEditor.addMessage(appRoot, "order-processor", "Priced",
            List.of(new FieldDef("notional", "Money", Map.of())), false));
        ProjectValidity.assertProjectValid(appRoot);

        valid(MessageEditor.removeMessage(appRoot, "order-processor", "Priced", false));
        valid(EntityEditor.removeEntity(appRoot, "order-processor",
            FieldEditor.ModelScope.SERVICE_MESSAGES, "Money", false));
    }

    // --- fields --------------------------------------------------------

    @Test
    public void everyFieldOperationLeavesTheProjectValid() throws Exception {
        TestAppFactory.addProcessor(appRoot, "order-processor");
        MessageEditor.addMessage(appRoot, "order-processor", "PlaceOrder",
            List.of(new FieldDef("qty", "Long", Map.of())), false);

        valid(FieldEditor.addField(appRoot, "order-processor",
            FieldEditor.ModelScope.SERVICE_MESSAGES, "PlaceOrder", "symbol", "String",
            Map.of(), false));
        valid(FieldEditor.renameField(appRoot, "order-processor",
            FieldEditor.ModelScope.SERVICE_MESSAGES, "PlaceOrder", "symbol", "ticker", false));
        valid(FieldEditor.deprecateField(appRoot, "order-processor",
            FieldEditor.ModelScope.SERVICE_MESSAGES, "PlaceOrder", "ticker", false));
        valid(FieldEditor.deleteField(appRoot, "order-processor",
            FieldEditor.ModelScope.SERVICE_MESSAGES, "PlaceOrder", "ticker", false));

        // The delete leaves an `id=N reserved` tombstone. A model carrying one
        // must still validate, or the never-reuse discipline would trade one
        // correctness property for another.
        ProjectValidity.assertProjectValid(appRoot);
    }

    // --- collections ---------------------------------------------------

    @Test
    public void addingAndRemovingACollectionLeavesTheProjectValid() throws Exception {
        TestAppFactory.addProcessor(appRoot, "order-processor");
        StateEditor.addStateEntity(appRoot, "order-processor", "Order",
            List.of(new FieldDef("id", "String", Map.of())), false);

        valid(CollectionEditor.addCollection(appRoot, "order-processor", "Orders",
            "StringMap", "Order", false));
        valid(CollectionEditor.removeCollection(appRoot, "order-processor", "Orders", false));
    }

    // --- api operations ------------------------------------------------

    @Test
    public void everyApiOperationEditLeavesTheProjectValid() throws Exception {
        TestAppFactory.addProcessor(appRoot, "order-processor");
        // Operations resolve their in/out messages against the ROE model.
        MessageEditor.addMessage(appRoot, "order-processor", FieldEditor.ModelScope.ROE_MESSAGES,
            "PlaceOrder", List.of(new FieldDef("qty", "Long", Map.of())), false);
        MessageEditor.addMessage(appRoot, "order-processor", FieldEditor.ModelScope.ROE_MESSAGES,
            "OrderAck", List.of(new FieldDef("ok", "Boolean", Map.of())), false);

        valid(ApiOperationEditor.addOperation(appRoot, "order-processor", "placeOrder",
            "PlaceOrder", "OrderAck", null, null, false));
        valid(ApiOperationEditor.renameOperation(appRoot, "order-processor", "placeOrder",
            "submitOrder", false));
        valid(ApiOperationEditor.removeOperation(appRoot, "order-processor", "submitOrder", false));
    }

    // --- config fragments ----------------------------------------------

    @Test
    public void addingAndRemovingAConfigFragmentLeavesTheProjectValid() throws Exception {
        TestAppFactory.addProcessor(appRoot, "order-processor");

        List<String> target = List.of("buses");
        valid(ConfigFragmentEditor.addFragment(appRoot, target,
            "<bus name=\"extra-bus\"/>", false));
        ProjectValidity.assertProjectValid(appRoot);

        valid(ConfigFragmentEditor.removeFragment(appRoot, target,
            ElementSelector.byTagAndName("bus", "extra-bus"), false));
        ProjectValidity.assertProjectValid(appRoot);
    }

    // --- dry runs ------------------------------------------------------

    /**
     * A dry run must leave the project exactly as it found it. Otherwise the
     * "inspect before you destroy" workflow agents are told to follow would
     * itself be a mutation.
     */
    @Test
    public void aDryRunChangesNothingOnDisk() throws Exception {
        TestAppFactory.addProcessor(appRoot, "order-processor");
        Path model = AppIntrospector.resolveMessagesXmlFile(appRoot, "order-processor");
        byte[] before = Files.readAllBytes(model);

        ChangeSet cs = MessageEditor.addMessage(appRoot, "order-processor", "DryRunOnly",
            List.of(new FieldDef("qty", "Long", Map.of())), true);

        assertFalse("a dry run must not report itself as applied", cs.isApplied());
        assertArrayEquals(before, Files.readAllBytes(model));
        ProjectValidity.assertProjectValid(appRoot);
    }
}
