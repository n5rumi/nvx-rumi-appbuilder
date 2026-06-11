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

import com.neeve.appbuilder.model.ApiOperationDef;
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
 * Exercises {@link ApiOperationEditor} / {@link ApiIntrospector} against a real
 * scaffolded processor's api.xml.
 */
public class ApiOperationEditorTest {

    private Path tempDir;
    private Path appRoot;
    private static final String SVC = "order-processor";

    @Before
    public void setUp() throws Exception {
        tempDir = Files.createTempDirectory("api-op-editor-");
        appRoot = TestAppFactory.newApp("demo").packageName("com.example.demo").scaffoldAt(tempDir);
        TestAppFactory.addProcessor(appRoot, SVC);
        MessageEditor.addMessage(appRoot, SVC, "PingRequest", List.of(), false);
        MessageEditor.addMessage(appRoot, SVC, "PingResponse",
            List.of(new FieldDef("status", "String", Map.of())), false);
    }

    @After
    public void tearDown() throws IOException {
        TestAppFactory.deleteRecursive(tempDir);
    }

    @Test
    public void addOperation_thenIntrospect() throws Exception {
        ApiOperationEditor.addOperation(appRoot, SVC, "ping", "PingRequest", "PingResponse", null, null, false);
        ApiOperationDef op = ApiIntrospector.getOperation(appRoot, SVC, "ping");
        assertNotNull(op);
        assertEquals("PingRequest", op.getInMessage());
        assertEquals("PingResponse", op.getOutMessage());
    }

    @Test
    public void addOperation_rejectsUnknownMessage() throws Exception {
        try {
            ApiOperationEditor.addOperation(appRoot, SVC, "ping", "NopeRequest", "PingResponse", null, null, false);
            fail("expected validation failure for unknown inMessage");
        } catch (IllegalArgumentException expected) {
            // ok
        }
    }

    @Test
    public void addOperation_isIdempotent() throws Exception {
        ApiOperationEditor.addOperation(appRoot, SVC, "ping", "PingRequest", "PingResponse", null, null, false);
        assertTrue(ApiOperationEditor.addOperation(appRoot, SVC, "ping", "PingRequest", "PingResponse", null, null, false).isNoop());
    }

    @Test
    public void removeOperation_reverts() throws Exception {
        ApiOperationEditor.addOperation(appRoot, SVC, "ping", "PingRequest", "PingResponse", null, null, false);
        assertTrue(ApiOperationEditor.removeOperation(appRoot, SVC, "ping", false).isApplied());
        assertNull(ApiIntrospector.getOperation(appRoot, SVC, "ping"));
        assertTrue("remove of absent is noop",
            ApiOperationEditor.removeOperation(appRoot, SVC, "ping", false).isNoop());
    }

    @Test
    public void renameOperation_keepsMessages() throws Exception {
        ApiOperationEditor.addOperation(appRoot, SVC, "ping", "PingRequest", "PingResponse", null, null, false);
        ApiOperationEditor.renameOperation(appRoot, SVC, "ping", "healthcheck", false);
        assertNull(ApiIntrospector.getOperation(appRoot, SVC, "ping"));
        ApiOperationDef op = ApiIntrospector.getOperation(appRoot, SVC, "healthcheck");
        assertNotNull(op);
        assertEquals("PingRequest", op.getInMessage());
    }
}
