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
import com.neeve.appbuilder.model.HandlerDef;
import org.junit.After;
import org.junit.Before;
import org.junit.Test;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.stream.Stream;

import static org.junit.Assert.*;

public class JavaSourceEditorTest {

    private Path tempDir;
    private Path appRoot;

    @Before
    public void setUp() throws IOException {
        tempDir = Files.createTempDirectory("javaedit-");
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

    // ---- addHandler ---------------------------------------------------

    @Test
    public void addHandler_insertsMethodIntoClass() throws Exception {
        writeEmptyMain("feeder");

        ChangeSet result = JavaSourceEditor.addHandler(
            appRoot, "feeder", "onOrderRequest", "OrderRequest", null, false);

        assertTrue(result.isApplied());
        assertFalse(result.isNoop());
        assertEquals(1, result.getFilesModified().size());

        // The introspector should now see the new handler.
        List<HandlerDef> handlers = HandlerIntrospector.listHandlers(appRoot, "feeder");
        assertEquals(1, handlers.size());
        assertEquals("onOrderRequest", handlers.get(0).getMethodName());
        assertEquals("OrderRequest", handlers.get(0).getMessageType());
    }

    @Test
    public void addHandler_withExplicitBody() throws Exception {
        writeEmptyMain("feeder");

        ChangeSet result = JavaSourceEditor.addHandler(
            appRoot, "feeder", "onTick", "Tick",
            "System.out.println(\"got tick: \" + message);",
            false);
        assertTrue(result.isApplied());

        String written = readMain("feeder");
        assertTrue("body included", written.contains("System.out.println"));
        // Must still parse back successfully.
        assertEquals(1, HandlerIntrospector.listHandlers(appRoot, "feeder").size());
    }

    @Test
    public void addHandler_isIdempotent() throws Exception {
        writeEmptyMain("feeder");

        JavaSourceEditor.addHandler(appRoot, "feeder", "onOrder", "Order", null, false);
        // Second add with same name should be a noop.
        ChangeSet second = JavaSourceEditor.addHandler(
            appRoot, "feeder", "onOrder", "Order", null, false);

        assertFalse(second.isApplied());
        assertTrue(second.isNoop());
        assertNotNull(second.getReason());

        // Still only one handler in the file.
        assertEquals(1, HandlerIntrospector.listHandlers(appRoot, "feeder").size());
    }

    @Test
    public void addHandler_dryRunDoesNotTouchDisk() throws Exception {
        writeEmptyMain("feeder");
        String before = readMain("feeder");

        ChangeSet result = JavaSourceEditor.addHandler(
            appRoot, "feeder", "onOrder", "Order", null, true);

        assertFalse(result.isApplied());
        assertEquals(1, result.getFilesModified().size());
        assertEquals("file on disk unchanged", before, readMain("feeder"));
        assertTrue("introspector still reports zero handlers",
            HandlerIntrospector.listHandlers(appRoot, "feeder").isEmpty());
    }

    @Test
    public void addHandler_preservesExistingMethodsAndComments() throws Exception {
        Path mainJava = AppIntrospector.resolveMainJavaFile(appRoot, "feeder");
        Files.createDirectories(mainJava.getParent());
        Files.writeString(mainJava,
            "package com.example.trading.feeder;\n" +
            "\n" +
            "public class Main {\n" +
            "    // existing documentation comment\n" +
            "    public void pre() {}\n" +
            "}\n");

        JavaSourceEditor.addHandler(appRoot, "feeder", "onTick", "Tick", null, false);
        String written = readMain("feeder");

        assertTrue("pre method retained", written.contains("public void pre()"));
        assertTrue("comment retained", written.contains("existing documentation comment"));
        assertTrue("new handler added", written.contains("onTick"));
    }

    // ---- removeHandler ------------------------------------------------

    @Test
    public void removeHandler_removesMatchingMethod() throws Exception {
        writeEmptyMain("feeder");
        JavaSourceEditor.addHandler(appRoot, "feeder", "onA", "A", null, false);
        JavaSourceEditor.addHandler(appRoot, "feeder", "onB", "B", null, false);
        assertEquals(2, HandlerIntrospector.listHandlers(appRoot, "feeder").size());

        ChangeSet result = JavaSourceEditor.removeHandler(appRoot, "feeder", "onA", false);
        assertTrue(result.isApplied());
        assertFalse(result.isNoop());

        List<HandlerDef> remaining = HandlerIntrospector.listHandlers(appRoot, "feeder");
        assertEquals(1, remaining.size());
        assertEquals("onB", remaining.get(0).getMethodName());
    }

    @Test
    public void removeHandler_noopWhenAbsent() throws Exception {
        writeEmptyMain("feeder");
        JavaSourceEditor.addHandler(appRoot, "feeder", "onA", "A", null, false);

        ChangeSet result = JavaSourceEditor.removeHandler(appRoot, "feeder", "onDoesNotExist", false);
        assertFalse(result.isApplied());
        assertTrue(result.isNoop());

        // Original handler still present.
        assertEquals(1, HandlerIntrospector.listHandlers(appRoot, "feeder").size());
    }

    @Test
    public void removeHandler_dryRunDoesNotTouchDisk() throws Exception {
        writeEmptyMain("feeder");
        JavaSourceEditor.addHandler(appRoot, "feeder", "onA", "A", null, false);
        String before = readMain("feeder");

        ChangeSet result = JavaSourceEditor.removeHandler(appRoot, "feeder", "onA", true);
        assertFalse(result.isApplied());
        assertEquals("file on disk unchanged", before, readMain("feeder"));
        assertEquals(1, HandlerIntrospector.listHandlers(appRoot, "feeder").size());
    }

    @Test
    public void removeHandler_doesNotRemoveNonHandlerMethodsWithSameName() throws Exception {
        Path mainJava = AppIntrospector.resolveMainJavaFile(appRoot, "feeder");
        Files.createDirectories(mainJava.getParent());
        Files.writeString(mainJava,
            "package com.example.trading.feeder;\n" +
            "public class Main {\n" +
            "    public void onTick() { /* not an event handler */ }\n" +
            "}\n");

        ChangeSet result = JavaSourceEditor.removeHandler(appRoot, "feeder", "onTick", false);
        assertTrue("no @EventHandler named onTick -> noop", result.isNoop());

        // Non-handler method with that name should still be there.
        String written = readMain("feeder");
        assertTrue(written.contains("onTick"));
    }

    // ---- add-then-remove round trip -----------------------------------

    @Test
    public void addThenRemove_returnsToEquivalentState() throws Exception {
        writeEmptyMain("feeder");
        JavaSourceEditor.addHandler(appRoot, "feeder", "onOrder", "Order", null, false);
        JavaSourceEditor.removeHandler(appRoot, "feeder", "onOrder", false);
        assertTrue("no handlers after add+remove round-trip",
            HandlerIntrospector.listHandlers(appRoot, "feeder").isEmpty());
    }

    // ---- ServiceIntrospector integration ------------------------------

    @Test
    public void serviceIntrospector_populatesHandlersAfterAdd() throws Exception {
        PhaseBTestSupport.writeParentPom(appRoot, "test-trading-roe", "test-trading-system", "test-trading-feeder");
        Files.createDirectories(appRoot.resolve("test-trading-feeder"));
        writeEmptyMain("feeder");
        JavaSourceEditor.addHandler(appRoot, "feeder", "onTick", "Tick", null, false);

        var svc = ServiceIntrospector.getService(appRoot, "feeder");
        assertNotNull(svc);
        assertEquals(1, svc.getHandlers().size());
        assertEquals("onTick", svc.getHandlers().get(0).getMethodName());
    }

    // ---- helpers -------------------------------------------------------

    private void writeEmptyMain(String serviceName) throws IOException {
        Path mainJava = AppIntrospector.resolveMainJavaFile(appRoot, serviceName);
        Files.createDirectories(mainJava.getParent());
        Files.writeString(mainJava,
            "package com.example.trading." + serviceName.replace('-', '.') + ";\n" +
            "\n" +
            "public class Main {\n" +
            "}\n");
    }

    private String readMain(String serviceName) throws IOException {
        return Files.readString(AppIntrospector.resolveMainJavaFile(appRoot, serviceName));
    }
}
