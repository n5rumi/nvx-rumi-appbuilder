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
import com.neeve.appbuilder.model.FieldDef;
import com.neeve.appbuilder.model.MessageDef;
import org.junit.After;
import org.junit.Before;
import org.junit.Test;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Collections;
import java.util.List;
import java.util.Map;
import java.util.stream.Stream;

import static org.junit.Assert.*;

public class MessageEditorTest {

    private Path tempDir;
    private Path appRoot;

    private static final String EMPTY_MESSAGES =
        "<model xmlns=\"http://www.neeveresearch.com/schema/x-adml\">" +
        "<factories><factory name=\"MessageFactory\" id=\"10\"/></factories>" +
        "<messages/>" +
        "</model>";

    @Before
    public void setUp() throws IOException {
        tempDir = Files.createTempDirectory("msgedit-");
        appRoot = PhaseBTestSupport.scaffoldApp(tempDir, "trading", "com.example.trading");
        PhaseBTestSupport.writeMessagesXml(appRoot, "feeder", EMPTY_MESSAGES);
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
    public void addMessage_insertsElementWithAutoId() throws Exception {
        ChangeSet r = MessageEditor.addMessage(appRoot, "feeder", "PlaceOrder",
            List.of(new FieldDef("orderId", "String", Map.of()),
                    new FieldDef("qty", "int", Map.of())),
            false);
        assertTrue(r.isApplied());
        assertFalse(r.isNoop());

        List<MessageDef> messages = MessageIntrospector.listMessages(appRoot, "feeder");
        assertEquals(1, messages.size());
        MessageDef m = messages.get(0);
        assertEquals("PlaceOrder", m.getName());
        assertEquals(Integer.valueOf(1), m.getId());
        assertEquals(2, m.getFields().size());
        assertEquals("String", m.getFields().get(0).getType());
    }

    @Test
    public void addMessage_isIdempotent() throws Exception {
        MessageEditor.addMessage(appRoot, "feeder", "PlaceOrder",
            Collections.emptyList(), false);
        ChangeSet r = MessageEditor.addMessage(appRoot, "feeder", "PlaceOrder",
            Collections.emptyList(), false);
        assertTrue(r.isNoop());
        assertEquals(1, MessageIntrospector.listMessages(appRoot, "feeder").size());
    }

    @Test
    public void addMessage_neverReusesIdGaps() throws Exception {
        // A gap at id=2 typically means a message was removed; its id must be
        // retired, not recycled (wire-format backward compatibility). The new
        // message gets max+1, never the gap.
        PhaseBTestSupport.writeMessagesXml(appRoot, "feeder",
            "<model xmlns=\"http://www.neeveresearch.com/schema/x-adml\">" +
            "<messages>" +
            "  <message name=\"A\" id=\"1\"/>" +
            "  <message name=\"C\" id=\"3\"/>" +
            "</messages></model>");
        MessageEditor.addMessage(appRoot, "feeder", "B", Collections.emptyList(), false);
        MessageDef b = MessageIntrospector.getMessage(appRoot, "feeder", "B");
        assertEquals("must not recycle the freed id 2", Integer.valueOf(4), b.getId());
    }

    @Test
    public void addMessage_honorsReservedTombstones() throws Exception {
        // A deleted message leaves an "id=N reserved" tombstone comment so its
        // id is never re-handed-out, even though the element is physically gone.
        PhaseBTestSupport.writeMessagesXml(appRoot, "feeder",
            "<model xmlns=\"http://www.neeveresearch.com/schema/x-adml\">" +
            "<messages>" +
            "  <message name=\"A\" id=\"2\"/>" +
            "  <!-- id=5 reserved (removed OldMsg) -->" +
            "</messages></model>");
        MessageEditor.addMessage(appRoot, "feeder", "B", Collections.emptyList(), false);
        MessageDef b = MessageIntrospector.getMessage(appRoot, "feeder", "B");
        assertEquals("must skip the reserved id 5", Integer.valueOf(6), b.getId());
    }

    @Test
    public void addMessage_dryRunDoesNotTouchDisk() throws Exception {
        String before = Files.readString(AppIntrospector.resolveMessagesXmlFile(appRoot, "feeder"));
        ChangeSet r = MessageEditor.addMessage(appRoot, "feeder", "PlaceOrder",
            Collections.emptyList(), true);
        assertFalse(r.isApplied());
        assertEquals(1, r.getFilesModified().size());
        assertEquals(before, Files.readString(AppIntrospector.resolveMessagesXmlFile(appRoot, "feeder")));
    }

    @Test
    public void removeMessage_stripsElement() throws Exception {
        MessageEditor.addMessage(appRoot, "feeder", "PlaceOrder",
            Collections.emptyList(), false);
        ChangeSet r = MessageEditor.removeMessage(appRoot, "feeder", "PlaceOrder", false);
        assertTrue(r.isApplied());
        assertTrue(MessageIntrospector.listMessages(appRoot, "feeder").isEmpty());
    }

    @Test
    public void removeMessage_noopWhenAbsent() throws Exception {
        ChangeSet r = MessageEditor.removeMessage(appRoot, "feeder", "DoesNotExist", false);
        assertTrue(r.isNoop());
    }

    @Test
    public void addThenRemove_roundTrip() throws Exception {
        MessageEditor.addMessage(appRoot, "feeder", "PlaceOrder",
            List.of(new FieldDef("id", "String", Map.of("isKey", "true"))), false);
        MessageEditor.removeMessage(appRoot, "feeder", "PlaceOrder", false);
        assertTrue(MessageIntrospector.listMessages(appRoot, "feeder").isEmpty());
    }

    @Test
    public void removeMessage_retiresId_neverReused() throws Exception {
        MessageEditor.addMessage(appRoot, "feeder", "A", Collections.emptyList(), false); // id 1
        MessageEditor.addMessage(appRoot, "feeder", "B", Collections.emptyList(), false); // id 2
        MessageEditor.removeMessage(appRoot, "feeder", "B", false);

        String xml = Files.readString(AppIntrospector.resolveMessagesXmlFile(appRoot, "feeder"));
        assertTrue("removed message leaves a reserved tombstone", xml.contains("id=2 reserved"));

        MessageEditor.addMessage(appRoot, "feeder", "C", Collections.emptyList(), false);
        assertEquals("must not recycle the retired id 2", Integer.valueOf(3),
            MessageIntrospector.getMessage(appRoot, "feeder", "C").getId());
    }

    @Test
    public void addMessage_toRoeScope_landsInRoeModel() throws Exception {
        // Stand up an empty ROE message model alongside the service one.
        Path roe = AppIntrospector.resolveRoeMessagesXmlFile(appRoot);
        Files.createDirectories(roe.getParent());
        Files.writeString(roe,
            "<model xmlns=\"http://www.neeveresearch.com/schema/x-adml\">" +
            "<factories><factory name=\"Factory\" id=\"1\"/></factories>" +
            "<messages/><entities/></model>");

        MessageEditor.addMessage(appRoot, "feeder", ModelScope.ROE_MESSAGES, "SharedEvent",
            List.of(new FieldDef("ts", "long", Map.of())), false);

        assertNotNull("added to the shared ROE model",
            MessageIntrospector.getMessage(appRoot, "feeder", "SharedEvent", ModelScope.ROE_MESSAGES));
        // The service's own message model is untouched.
        assertNull(MessageIntrospector.getMessage(appRoot, "feeder", "SharedEvent"));
    }

    @Test
    public void addMessage_rejectsStateScope() throws Exception {
        try {
            MessageEditor.addMessage(appRoot, "feeder", ModelScope.SERVICE_STATE, "Nope",
                Collections.emptyList(), false);
            fail("expected IllegalArgumentException for the state scope");
        } catch (IllegalArgumentException expected) {
            assertTrue(expected.getMessage().toLowerCase().contains("state"));
        }
    }
}
