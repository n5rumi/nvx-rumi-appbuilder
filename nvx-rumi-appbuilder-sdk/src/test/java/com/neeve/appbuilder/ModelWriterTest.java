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

import com.neeve.appbuilder.model.FieldDef;
import org.junit.After;
import org.junit.Before;
import org.junit.Test;
import org.w3c.dom.Document;
import org.w3c.dom.Element;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.Map;
import java.util.stream.Stream;

import static org.junit.Assert.*;

/**
 * RUMI-376, the write path. The behaviour that matters to a caller is not
 * "invalid input is rejected" but "a rejected edit changes nothing" — an
 * agent retries far more often than it cleans up, so a half-applied edit
 * would turn one bad tool call into a project that no longer builds.
 */
public class ModelWriterTest {

    private static final String VALID_MODEL =
        "<?xml version=\"1.0\"?>\n"
      + "<model xmlns=\"http://www.neeveresearch.com/schema/x-adml\" "
      + "namespace=\"com.example.trading.feeder.messages\" defaultFactoryId=\"3\">\n"
      + "    <factories><factory name=\"Factory\" id=\"3\"/></factories>\n"
      + "    <messages></messages>\n"
      + "    <entities></entities>\n"
      + "</model>\n";

    private Path tempDir;
    private Path appRoot;

    @Before
    public void setUp() throws IOException {
        tempDir = Files.createTempDirectory("modelwriter-");
        appRoot = PhaseBTestSupport.scaffoldApp(tempDir, "trading", "com.example.trading");
        PhaseBTestSupport.writeMessagesXml(appRoot, "feeder", VALID_MODEL);
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


    private Path writeModel() throws IOException {
        Path p = tempDir.resolve("messages.xml");
        Files.writeString(p, VALID_MODEL);
        return p;
    }

    @Test
    public void writesAValidModel() throws Exception {
        Path model = writeModel();
        Document doc = XmlDomUtils.parseXmlDocument(model);
        Element messages = (Element) doc.getDocumentElement()
            .getElementsByTagNameNS("http://www.neeveresearch.com/schema/x-adml", "messages").item(0);
        Element msg = doc.createElementNS(
            "http://www.neeveresearch.com/schema/x-adml", "message");
        msg.setAttribute("name", "Tick");
        msg.setAttribute("id", "1");
        messages.appendChild(msg);

        ModelWriter.saveValidated(doc, model);
        assertTrue(Files.readString(model).contains("Tick"));
    }

    @Test
    public void aRejectedEditLeavesTheFileByteForByteUntouched() throws Exception {
        Path model = writeModel();
        byte[] before = Files.readAllBytes(model);

        Document doc = XmlDomUtils.parseXmlDocument(model);
        Element messages = (Element) doc.getDocumentElement()
            .getElementsByTagNameNS("http://www.neeveresearch.com/schema/x-adml", "messages").item(0);
        Element msg = doc.createElementNS(
            "http://www.neeveresearch.com/schema/x-adml", "message");
        msg.setAttribute("name", "Tick");
        msg.setAttribute("id", "1");
        Element field = doc.createElementNS(
            "http://www.neeveresearch.com/schema/x-adml", "field");
        field.setAttribute("name", "qty");
        field.setAttribute("id", "1");
        field.setAttribute("type", "Long");
        field.setAttribute("nonsense", "true"); // not an ADML field attribute
        msg.appendChild(field);
        messages.appendChild(msg);

        try {
            ModelWriter.saveValidated(doc, model);
            fail("expected the invalid edit to be rejected");
        } catch (ModelValidationException expected) {
            assertTrue(expected.getMessage(), expected.getMessage().contains("nonsense"));
            assertEquals(model, expected.getModelFile());
        }
        assertArrayEquals("a rejected edit must not touch the file", before, Files.readAllBytes(model));
    }

    /**
     * A dry run exists to tell the caller what the real call would do. One
     * that reported success and was then followed by a rejected write would
     * be worse than not offering the check at all.
     */
    @Test
    public void aDryRunValidatesAndStillWritesNothing() throws Exception {
        Path model = writeModel();
        byte[] before = Files.readAllBytes(model);

        Document doc = XmlDomUtils.parseXmlDocument(model);
        Element messages = (Element) doc.getDocumentElement()
            .getElementsByTagNameNS("http://www.neeveresearch.com/schema/x-adml", "messages").item(0);
        Element msg = doc.createElementNS(
            "http://www.neeveresearch.com/schema/x-adml", "message");
        msg.setAttribute("name", "Tick");
        msg.setAttribute("id", "not-a-number");
        messages.appendChild(msg);

        try {
            ModelWriter.saveValidated(doc, model, true);
            fail("a dry run must report the same rejection the real call would");
        } catch (ModelValidationException expected) {
            // expected
        }
        assertArrayEquals(before, Files.readAllBytes(model));
    }

    @Test
    public void aValidDryRunWritesNothing() throws Exception {
        Path model = writeModel();
        byte[] before = Files.readAllBytes(model);
        Document doc = XmlDomUtils.parseXmlDocument(model);
        ModelWriter.saveValidated(doc, model, true);
        assertArrayEquals(before, Files.readAllBytes(model));
    }

    // --- through the editors ------------------------------------------

    @Test
    public void editorRejectsAFieldTypeThatResolvesToNothing() throws Exception {
        MessageEditor.addMessage(appRoot, "feeder", "Tick",
            List.of(new FieldDef("px", "Double", Map.of())), false);
        try {
            FieldEditor.addField(appRoot, "feeder", FieldEditor.ModelScope.SERVICE_MESSAGES,
                "Tick", "bad", "NoSuchTypeAnywhere", Map.of(), false);
            fail("expected the unresolvable field type to be rejected");
        } catch (ModelValidationException expected) {
            assertTrue(expected.getMessage(), expected.getMessage().contains("NoSuchTypeAnywhere"));
        }
    }

    /**
     * The exception extends IllegalStateException so the REST layer's existing
     * mapping renders it 422, the same status referential-safety uses. Losing
     * that inheritance would silently downgrade a rejected edit to a 500.
     */
    @Test
    public void aRejectedEditSurfacesAsUnprocessableNotAServerError() throws Exception {
        assertTrue(IllegalStateException.class.isAssignableFrom(ModelValidationException.class));
        assertFalse(IOException.class.isAssignableFrom(ModelValidationException.class));
    }
}
