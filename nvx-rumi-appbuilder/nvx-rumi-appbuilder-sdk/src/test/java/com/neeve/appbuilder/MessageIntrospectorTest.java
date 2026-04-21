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

import com.neeve.appbuilder.model.MessageDef;
import org.junit.After;
import org.junit.Before;
import org.junit.Test;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.stream.Stream;

import static org.junit.Assert.*;

public class MessageIntrospectorTest {

    private Path tempDir;
    private Path appRoot;

    @Before
    public void setUp() throws IOException {
        tempDir = Files.createTempDirectory("msgintr-");
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
    public void listMessages_emptyWhenFileMissing() throws Exception {
        assertTrue(MessageIntrospector.listMessages(appRoot, "noSuchService").isEmpty());
    }

    @Test
    public void listMessages_emptyWhenNoMessagesDefined() throws Exception {
        PhaseBTestSupport.writeMessagesXml(appRoot, "driver",
            "<model xmlns=\"http://www.neeveresearch.com/schema/x-adml\">" +
            "<factories><factory name=\"MessageFactory\" id=\"1\"/></factories>" +
            "<messages/>" +
            "</model>");
        assertTrue(MessageIntrospector.listMessages(appRoot, "driver").isEmpty());
    }

    @Test
    public void listMessages_returnsAllMessagesInDocumentOrder() throws Exception {
        PhaseBTestSupport.writeMessagesXml(appRoot, "driver",
            "<model xmlns=\"http://www.neeveresearch.com/schema/x-adml\">" +
            "<messages>" +
            "  <message name=\"OrderRequest\" id=\"1\">" +
            "    <field name=\"orderId\" type=\"String\"/>" +
            "    <field name=\"quantity\" type=\"int\"/>" +
            "  </message>" +
            "  <message name=\"OrderResponse\" id=\"2\">" +
            "    <field name=\"status\" type=\"String\"/>" +
            "  </message>" +
            "</messages>" +
            "</model>");

        List<MessageDef> messages = MessageIntrospector.listMessages(appRoot, "driver");
        assertEquals(2, messages.size());
        assertEquals("OrderRequest", messages.get(0).getName());
        assertEquals(Integer.valueOf(1), messages.get(0).getId());
        assertEquals(2, messages.get(0).getFields().size());
        assertEquals("orderId", messages.get(0).getFields().get(0).getName());
        assertEquals("String", messages.get(0).getFields().get(0).getType());

        assertEquals("OrderResponse", messages.get(1).getName());
        assertEquals(Integer.valueOf(2), messages.get(1).getId());
        assertEquals(1, messages.get(1).getFields().size());
    }

    @Test
    public void listMessages_preservesAdditionalAttributes() throws Exception {
        PhaseBTestSupport.writeMessagesXml(appRoot, "driver",
            "<model xmlns=\"http://www.neeveresearch.com/schema/x-adml\">" +
            "<messages><message name=\"M\" id=\"3\" namespace=\"com.x\" custom=\"v\"/></messages>" +
            "</model>");
        MessageDef m = MessageIntrospector.listMessages(appRoot, "driver").get(0);
        assertEquals("com.x", m.getAttributes().get("namespace"));
        assertEquals("v", m.getAttributes().get("custom"));
    }

    @Test
    public void listMessages_handlesMissingIdAttribute() throws Exception {
        PhaseBTestSupport.writeMessagesXml(appRoot, "driver",
            "<model xmlns=\"http://www.neeveresearch.com/schema/x-adml\">" +
            "<messages><message name=\"NoId\"/></messages>" +
            "</model>");
        MessageDef m = MessageIntrospector.listMessages(appRoot, "driver").get(0);
        assertEquals("NoId", m.getName());
        assertNull(m.getId());
    }

    @Test
    public void listMessages_ignoresWrongNamespace() throws Exception {
        PhaseBTestSupport.writeMessagesXml(appRoot, "driver",
            "<model xmlns=\"http://example.com/not-adml\">" +
            "<messages><message name=\"WrongNS\"/></messages>" +
            "</model>");
        assertTrue(MessageIntrospector.listMessages(appRoot, "driver").isEmpty());
    }

    @Test
    public void getMessage_returnsNullWhenAbsent() throws Exception {
        PhaseBTestSupport.writeMessagesXml(appRoot, "driver",
            "<model xmlns=\"http://www.neeveresearch.com/schema/x-adml\">" +
            "<messages><message name=\"Foo\"/></messages>" +
            "</model>");
        assertNull(MessageIntrospector.getMessage(appRoot, "driver", "Bar"));
    }

    @Test
    public void getMessage_returnsMatchWhenPresent() throws Exception {
        PhaseBTestSupport.writeMessagesXml(appRoot, "driver",
            "<model xmlns=\"http://www.neeveresearch.com/schema/x-adml\">" +
            "<messages>" +
            "  <message name=\"Foo\"><field name=\"x\" type=\"int\"/></message>" +
            "  <message name=\"Bar\"><field name=\"y\" type=\"String\"/></message>" +
            "</messages>" +
            "</model>");
        MessageDef bar = MessageIntrospector.getMessage(appRoot, "driver", "Bar");
        assertNotNull(bar);
        assertEquals(1, bar.getFields().size());
        assertEquals("y", bar.getFields().get(0).getName());
    }
}
