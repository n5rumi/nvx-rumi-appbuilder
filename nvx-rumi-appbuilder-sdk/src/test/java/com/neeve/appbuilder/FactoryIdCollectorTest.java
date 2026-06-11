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

import org.junit.After;
import org.junit.Before;
import org.junit.Test;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.Map;
import java.util.stream.Stream;

import static org.junit.Assert.*;

public class FactoryIdCollectorTest {

    private Path appRoot;

    @Before
    public void setUp() throws IOException {
        appRoot = Files.createTempDirectory("factoryid-test-");
    }

    @After
    public void tearDown() throws IOException {
        if (appRoot != null && Files.exists(appRoot)) {
            try (Stream<Path> walk = Files.walk(appRoot)) {
                walk.sorted((a, b) -> b.getNameCount() - a.getNameCount())
                    .forEach(p -> { try { Files.deleteIfExists(p); } catch (IOException ignored) {} });
            }
        }
    }

    // ---- collectAvailableFactoryIds (existing behavior, preserved) -----

    @Test
    public void collect_fillsGapsBeforeIncrementing() throws Exception {
        writeModel("svc-a/src/main/models/messages/messages.xml", 1);
        writeModel("svc-b/src/main/models/state/state.xml", 3);
        writeModel("svc-c/src/main/models/messages/messages.xml", 5);

        List<Integer> available = FactoryIdCollector.collectAvailableFactoryIds(appRoot, 4);
        assertEquals("gap-fill + increment: 2, 4, 6, 7", List.of(2, 4, 6, 7), available);
    }

    @Test
    public void collect_startsFromOneOnEmptyApp() throws Exception {
        List<Integer> available = FactoryIdCollector.collectAvailableFactoryIds(appRoot, 3);
        assertEquals(List.of(1, 2, 3), available);
    }

    // ---- nextAvailableId -----------------------------------------------

    @Test
    public void nextAvailableId_returnsFirstGap() throws Exception {
        writeModel("svc/src/main/models/messages/messages.xml", 1);
        writeModel("svc/src/main/models/state/state.xml", 3);
        // Used: {1, 3}. Gap at 2.
        assertEquals(2, FactoryIdCollector.nextAvailableId(appRoot));
    }

    @Test
    public void nextAvailableId_incrementsWhenNoGap() throws Exception {
        writeModel("svc/src/main/models/messages/messages.xml", 1);
        writeModel("svc/src/main/models/state/state.xml", 2);
        assertEquals(3, FactoryIdCollector.nextAvailableId(appRoot));
    }

    @Test
    public void nextAvailableId_returnsOneForEmptyApp() throws Exception {
        assertEquals(1, FactoryIdCollector.nextAvailableId(appRoot));
    }

    // ---- listUsedIds ---------------------------------------------------

    @Test
    public void listUsedIds_returnsAllIdsAcrossServices() throws Exception {
        writeModel("svc-a/src/main/models/messages/messages.xml", 1, "com.example.a.Messages");
        writeModel("svc-a/src/main/models/state/state.xml", 2, "com.example.a.State");
        writeModel("svc-b/src/main/models/messages/messages.xml", 3, "com.example.b.Messages");

        Map<Integer, String> used = FactoryIdCollector.listUsedIds(appRoot);
        assertEquals(3, used.size());
        assertTrue(used.get(1).contains("com.example.a.Messages"));
        assertTrue(used.get(2).contains("com.example.a.State"));
        assertTrue(used.get(3).contains("com.example.b.Messages"));
    }

    @Test
    public void listUsedIds_returnsEmptyWhenNoFactories() throws Exception {
        Map<Integer, String> used = FactoryIdCollector.listUsedIds(appRoot);
        assertTrue(used.isEmpty());
    }

    @Test
    public void listUsedIds_keysSortedAscending() throws Exception {
        writeModel("a/src/main/models/x/a.xml", 5, "five");
        writeModel("a/src/main/models/x/b.xml", 2, "two");
        writeModel("a/src/main/models/x/c.xml", 8, "eight");

        Map<Integer, String> used = FactoryIdCollector.listUsedIds(appRoot);
        List<Integer> keys = new java.util.ArrayList<>(used.keySet());
        assertEquals(List.of(2, 5, 8), keys);
    }

    @Test
    public void listUsedIds_descriptionFallsBackToNameThenTag() throws Exception {
        // className set -> className used
        writeModelRaw("a/src/main/models/x/a.xml",
            "<a:model xmlns:a=\"http://www.neeveresearch.com/schema/x-adml\">" +
            "<a:factory id=\"1\" className=\"com.example.WithClassName\"/>" +
            "</a:model>");
        // name set (no className) -> name used
        writeModelRaw("a/src/main/models/x/b.xml",
            "<a:model xmlns:a=\"http://www.neeveresearch.com/schema/x-adml\">" +
            "<a:factory id=\"2\" name=\"NamedFactory\"/>" +
            "</a:model>");
        // neither set -> fallback
        writeModelRaw("a/src/main/models/x/c.xml",
            "<a:model xmlns:a=\"http://www.neeveresearch.com/schema/x-adml\">" +
            "<a:factory id=\"3\"/>" +
            "</a:model>");

        Map<Integer, String> used = FactoryIdCollector.listUsedIds(appRoot);
        assertTrue(used.get(1).contains("com.example.WithClassName"));
        assertTrue(used.get(2).contains("NamedFactory"));
        assertTrue(used.get(3).contains("<factory>"));
    }

    // ---- release -------------------------------------------------------

    @Test
    public void release_noopWhenIdUnreferenced() throws Exception {
        writeModel("svc/src/main/models/messages/messages.xml", 1, "com.example.Messages");
        // ID 99 is not in use anywhere; release should succeed silently.
        FactoryIdCollector.release(appRoot, 99);
    }

    @Test
    public void release_throwsWhenIdStillInUse() throws Exception {
        writeModel("svc/src/main/models/messages/messages.xml", 7, "com.example.StillHere");
        try {
            FactoryIdCollector.release(appRoot, 7);
            fail("expected IllegalStateException");
        } catch (IllegalStateException expected) {
            assertTrue(expected.getMessage().contains("7"));
            assertTrue(expected.getMessage().contains("com.example.StillHere"));
        }
    }

    @Test
    public void release_idInGapAlwaysOk() throws Exception {
        // Gaps (e.g. 2 when only 1 and 3 are used) are always releasable — the gap means no factory has that ID.
        writeModel("svc/src/main/models/messages/messages.xml", 1, "one");
        writeModel("svc/src/main/models/state/state.xml", 3, "three");
        FactoryIdCollector.release(appRoot, 2);
    }

    // ---- app-global never-reuse ledger (slice 4) -----------------------

    @Test
    public void recordAllocatedIds_writesLedger_andRetiresRemovedIds() throws Exception {
        writeModel("svc-a/src/main/models/messages/messages.xml", 1);
        writeModel("svc-b/src/main/models/state/state.xml", 2);
        writeModel("svc-c/src/main/models/messages/messages.xml", 3);
        assertEquals(List.of(1, 2, 3), FactoryIdCollector.recordAllocatedIds(appRoot));
        assertTrue("ledger sidecar is written", Files.exists(appRoot.resolve(".rumi-factory-ids")));

        // Remove svc-b's model (its files are gone), leaving a 2-shaped gap.
        Files.deleteIfExists(appRoot.resolve("svc-b/src/main/models/state/state.xml"));

        // Without the ledger this would gap-fill to 2; the ledger retires it.
        assertEquals("removed id must not be reused", 4, FactoryIdCollector.nextAvailableId(appRoot));
    }

    @Test
    public void ledger_protectsAgainstOutOfBandDeletion() throws Exception {
        writeModel("svc-a/src/main/models/messages/messages.xml", 1);
        writeModel("svc-b/src/main/models/messages/messages.xml", 2);
        FactoryIdCollector.recordAllocatedIds(appRoot);

        // Delete the whole module out of band (not via ServiceRemover).
        try (Stream<Path> w = Files.walk(appRoot.resolve("svc-b"))) {
            w.sorted((a, b) -> b.getNameCount() - a.getNameCount())
             .forEach(p -> { try { Files.deleteIfExists(p); } catch (IOException ignored) {} });
        }
        assertEquals("ledger survives out-of-band deletion", 3, FactoryIdCollector.nextAvailableId(appRoot));
    }

    @Test
    public void recordAllocatedIds_isAppendOnly_neverPrunes() throws Exception {
        writeModel("svc-a/src/main/models/messages/messages.xml", 5);
        FactoryIdCollector.recordAllocatedIds(appRoot);
        // Delete the only model, then record again — the ledger keeps the id.
        Files.deleteIfExists(appRoot.resolve("svc-a/src/main/models/messages/messages.xml"));
        assertEquals(List.of(5), FactoryIdCollector.recordAllocatedIds(appRoot));

        // Allocation is first-gap (frugal): it offers the lowest NEVER-used ids
        // (1..4) and the next above the high-water mark (6,7) — but never the
        // retired 5, even though it's now absent from every model file.
        List<Integer> avail = FactoryIdCollector.collectAvailableFactoryIds(appRoot, 6);
        assertFalse("retired id 5 must never be offered", avail.contains(5));
        assertEquals(List.of(1, 2, 3, 4, 6, 7), avail);
    }

    @Test
    public void scaffold_addRemoveAdd_doesNotRecycleFactoryIds() throws Exception {
        Path parent = Files.createTempDirectory("fid-e2e-");
        try {
            Path app = com.neeve.appbuilder.test.TestAppFactory.newApp("demo")
                .packageName("com.example.demo").scaffoldAt(parent);
            // Baseline = ids owned by the scaffold itself (e.g. the shared ROE factory).
            java.util.Set<Integer> baseline = new java.util.HashSet<>(FactoryIdCollector.listUsedIds(app).keySet());

            com.neeve.appbuilder.test.TestAppFactory.addProcessor(app, "alpha");
            java.util.Set<Integer> alphaOwned = new java.util.HashSet<>(FactoryIdCollector.listUsedIds(app).keySet());
            alphaOwned.removeAll(baseline);
            assertFalse("alpha allocates its own factory ids", alphaOwned.isEmpty());

            ServiceRemover.removeService(app, "alpha", false);
            com.neeve.appbuilder.test.TestAppFactory.addProcessor(app, "beta");
            java.util.Set<Integer> betaOwned = new java.util.HashSet<>(FactoryIdCollector.listUsedIds(app).keySet());
            betaOwned.removeAll(baseline);

            betaOwned.retainAll(alphaOwned);
            assertTrue("beta must not reuse any of alpha's retired factory ids: " + betaOwned, betaOwned.isEmpty());
        } finally {
            com.neeve.appbuilder.test.TestAppFactory.deleteRecursive(parent);
        }
    }

    // ---- fixtures -----------------------------------------------------

    private void writeModel(String relativePath, int factoryId) throws IOException {
        writeModel(relativePath, factoryId, "com.example.Generic");
    }

    /**
     * Write a minimal ADML model file with one {@code <factory>} element.
     */
    private void writeModel(String relativePath, int factoryId, String className) throws IOException {
        writeModelRaw(relativePath,
            "<a:model xmlns:a=\"http://www.neeveresearch.com/schema/x-adml\">" +
            "<a:factory id=\"" + factoryId + "\" className=\"" + className + "\"/>" +
            "</a:model>");
    }

    private void writeModelRaw(String relativePath, String xml) throws IOException {
        Path file = appRoot.resolve(relativePath);
        Files.createDirectories(file.getParent());
        Files.writeString(file, xml);
    }
}
