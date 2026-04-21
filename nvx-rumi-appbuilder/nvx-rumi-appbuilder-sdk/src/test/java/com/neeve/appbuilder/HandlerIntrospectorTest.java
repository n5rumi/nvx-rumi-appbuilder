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

public class HandlerIntrospectorTest {

    private Path tempDir;
    private Path appRoot;

    @Before
    public void setUp() throws IOException {
        tempDir = Files.createTempDirectory("handintr-");
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
    public void listHandlers_emptyWhenMainJavaMissing() throws Exception {
        assertTrue(HandlerIntrospector.listHandlers(appRoot, "feeder").isEmpty());
    }

    @Test
    public void listHandlers_findsEventHandlerMethods() throws Exception {
        writeMainJava("feeder",
            "package com.example.trading.feeder;\n" +
            "\n" +
            "public class Main {\n" +
            "    @EventHandler\n" +
            "    public final void onOrderRequest(OrderRequest message) {\n" +
            "        // handle it\n" +
            "    }\n" +
            "\n" +
            "    @EventHandler\n" +
            "    public final void onOrderResponse(OrderResponse message) {}\n" +
            "\n" +
            "    public void notAHandler() {}\n" +
            "}\n");

        List<HandlerDef> handlers = HandlerIntrospector.listHandlers(appRoot, "feeder");
        assertEquals(2, handlers.size());
        assertEquals("onOrderRequest", handlers.get(0).getMethodName());
        assertEquals("OrderRequest", handlers.get(0).getMessageType());
        assertEquals("void", handlers.get(0).getReturnType());
        assertEquals("onOrderResponse", handlers.get(1).getMethodName());
        assertEquals("OrderResponse", handlers.get(1).getMessageType());
    }

    @Test
    public void listHandlers_recognisesFullyQualifiedAnnotation() throws Exception {
        writeMainJava("feeder",
            "package com.example.trading.feeder;\n" +
            "public class Main {\n" +
            "    @com.neeve.aep.annotations.EventHandler\n" +
            "    public final void onTick(Tick message) {}\n" +
            "}\n");

        List<HandlerDef> handlers = HandlerIntrospector.listHandlers(appRoot, "feeder");
        assertEquals(1, handlers.size());
        assertEquals("onTick", handlers.get(0).getMethodName());
        assertEquals("Tick", handlers.get(0).getMessageType());
    }

    @Test
    public void listHandlers_ignoresMethodsWithoutEventHandlerAnnotation() throws Exception {
        writeMainJava("feeder",
            "package com.example.trading.feeder;\n" +
            "public class Main {\n" +
            "    @Override\n" +
            "    public void run() {}\n" +
            "    @Deprecated\n" +
            "    public void old(String x) {}\n" +
            "}\n");
        assertTrue(HandlerIntrospector.listHandlers(appRoot, "feeder").isEmpty());
    }

    @Test
    public void listHandlers_nullMessageTypeForZeroOrMultipleParams() throws Exception {
        writeMainJava("feeder",
            "package com.example.trading.feeder;\n" +
            "public class Main {\n" +
            "    @EventHandler public final void noParams() {}\n" +
            "    @EventHandler public final void twoParams(String a, int b) {}\n" +
            "}\n");
        List<HandlerDef> handlers = HandlerIntrospector.listHandlers(appRoot, "feeder");
        assertEquals(2, handlers.size());
        assertNull(handlers.get(0).getMessageType());
        assertNull(handlers.get(1).getMessageType());
    }

    @Test
    public void listHandlers_startLineReportsPosition() throws Exception {
        writeMainJava("feeder",
            "package com.example.trading.feeder;\n" +                     // line 1
            "\n" +                                                          // line 2
            "public class Main {\n" +                                       // line 3
            "    @EventHandler\n" +                                         // line 4
            "    public final void onTick(Tick message) {}\n" +             // line 5 — handler starts here (annotation)
            "}\n");
        HandlerDef h = HandlerIntrospector.listHandlers(appRoot, "feeder").get(0);
        // JavaParser reports the method's start line as the annotation's line.
        assertTrue("startLine reported: " + h.getStartLine(), h.getStartLine() >= 4 && h.getStartLine() <= 5);
    }

    @Test
    public void getHandler_returnsMatchOrNull() throws Exception {
        writeMainJava("feeder",
            "package com.example.trading.feeder;\n" +
            "public class Main {\n" +
            "    @EventHandler public final void onA(A message) {}\n" +
            "    @EventHandler public final void onB(B message) {}\n" +
            "}\n");
        assertNull(HandlerIntrospector.getHandler(appRoot, "feeder", "onC"));
        HandlerDef b = HandlerIntrospector.getHandler(appRoot, "feeder", "onB");
        assertNotNull(b);
        assertEquals("B", b.getMessageType());
    }

    // ---- helpers -------------------------------------------------------

    private void writeMainJava(String serviceName, String source) throws IOException {
        // Ensure we're writing at AppIntrospector's resolved path.
        Path mainJava = AppIntrospector.resolveMainJavaFile(appRoot, serviceName);
        Files.createDirectories(mainJava.getParent());
        Files.writeString(mainJava, source);
    }
}
