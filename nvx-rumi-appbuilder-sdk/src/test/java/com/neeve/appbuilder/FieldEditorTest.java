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
 * Exercises {@link FieldEditor} against a real scaffolded processor: add /
 * delete (with id retirement) / deprecate / rename fields on a message and on
 * a state entity.
 */
public class FieldEditorTest {

    private Path tempDir;
    private Path appRoot;
    private static final String SVC = "order-processor";

    @Before
    public void setUp() throws Exception {
        tempDir = Files.createTempDirectory("field-editor-");
        appRoot = TestAppFactory.newApp("demo").packageName("com.example.demo").scaffoldAt(tempDir);
        TestAppFactory.addProcessor(appRoot, SVC);
        // a message to edit (its one field gets id 1)
        MessageEditor.addMessage(appRoot, SVC, "Order",
            List.of(new FieldDef("symbol", "String", Map.of())), false);
    }

    @After
    public void tearDown() throws IOException {
        TestAppFactory.deleteRecursive(tempDir);
    }

    private MessageDef order() throws IOException {
        return MessageIntrospector.getMessage(appRoot, SVC, "Order");
    }

    private FieldDef field(MessageDef m, String name) {
        return m.getFields().stream().filter(f -> name.equals(f.getName())).findFirst().orElse(null);
    }

    @Test
    public void addField_assignsMonotonicId() throws Exception {
        FieldEditor.addField(appRoot, SVC, FieldEditor.ModelScope.SERVICE_MESSAGES,
            "Order", "price", "Double", Map.of(), false);
        FieldDef price = field(order(), "price");
        assertNotNull(price);
        assertEquals("Double", price.getType());
        assertEquals("2", price.getAttributes().get("id")); // symbol=1, price=2
    }

    @Test
    public void deleteField_retiresId_andNextAddDoesNotReuse() throws Exception {
        FieldEditor.addField(appRoot, SVC, FieldEditor.ModelScope.SERVICE_MESSAGES,
            "Order", "price", "Double", Map.of(), false); // id 2
        FieldEditor.deleteField(appRoot, SVC, FieldEditor.ModelScope.SERVICE_MESSAGES,
            "Order", "price", false);
        assertNull("price removed", field(order(), "price"));

        // a tombstone preserves the id
        String xml = Files.readString(AppIntrospector.resolveMessagesXmlFile(appRoot, SVC));
        assertTrue("reserved tombstone present", xml.contains("id=2 reserved"));

        // next add must NOT recycle id 2
        FieldEditor.addField(appRoot, SVC, FieldEditor.ModelScope.SERVICE_MESSAGES,
            "Order", "qty", "Long", Map.of(), false);
        assertEquals("3", field(order(), "qty").getAttributes().get("id"));
    }

    @Test
    public void deprecateField_keepsFieldButFlagsIt() throws Exception {
        FieldEditor.deprecateField(appRoot, SVC, FieldEditor.ModelScope.SERVICE_MESSAGES,
            "Order", "symbol", false);
        FieldDef symbol = field(order(), "symbol");
        assertNotNull("field kept", symbol);
        assertEquals("true", symbol.getAttributes().get("deprecated"));
    }

    @Test
    public void renameField_keepsId() throws Exception {
        String id = field(order(), "symbol").getAttributes().get("id");
        FieldEditor.renameField(appRoot, SVC, FieldEditor.ModelScope.SERVICE_MESSAGES,
            "Order", "symbol", "ticker", false);
        assertNull(field(order(), "symbol"));
        FieldDef ticker = field(order(), "ticker");
        assertNotNull(ticker);
        assertEquals("id unchanged on rename", id, ticker.getAttributes().get("id"));
    }

    @Test
    public void addField_onStateEntity() throws Exception {
        FieldEditor.addField(appRoot, SVC, FieldEditor.ModelScope.SERVICE_STATE,
            "Repository", "counter", "Long", Map.of(), false);
        EntityDef repo = StateIntrospector.getStateEntity(appRoot, SVC, "Repository");
        assertTrue("counter added to Repository",
            repo.getFields().stream().anyMatch(f -> "counter".equals(f.getName())));
    }

    @Test
    public void addField_isIdempotent() throws Exception {
        FieldEditor.addField(appRoot, SVC, FieldEditor.ModelScope.SERVICE_MESSAGES,
            "Order", "price", "Double", Map.of(), false);
        assertTrue(FieldEditor.addField(appRoot, SVC, FieldEditor.ModelScope.SERVICE_MESSAGES,
            "Order", "price", "Double", Map.of(), false).isNoop());
    }
}
