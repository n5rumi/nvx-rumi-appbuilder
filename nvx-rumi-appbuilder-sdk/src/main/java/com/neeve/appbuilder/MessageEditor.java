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
import com.neeve.appbuilder.model.FieldDef;
import org.w3c.dom.Comment;
import org.w3c.dom.Document;
import org.w3c.dom.Element;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;

import static com.neeve.appbuilder.MessageIntrospector.ADML_NAMESPACE;

/**
 * Add and remove whole {@code <message>} declarations in an X-ADML message
 * model — either a service's private {@code messages.xml} or the app-wide
 * shared ROE message model (selected via {@link FieldEditor.ModelScope}).
 * Symmetric with {@link EntityEditor} over {@code <entity>} declarations.
 *
 * <p>Message IDs are local to the model's factory — they are not drawn from
 * the app-wide factory-id pool managed by {@link FactoryIdCollector}. Allocation
 * goes through {@link ModelIdAllocator}: a new message gets the high-water-mark
 * type id, and a removed message leaves an {@code <!-- id=N reserved -->}
 * tombstone so its id is <em>never re-handed-out</em> (a recycled type id would
 * let an old peer misinterpret a new message on the wire).
 *
 * <p>Adds are identity-matched on {@code messageName}: adding a message with a
 * name that already exists is a noop.
 */
public final class MessageEditor {
    private MessageEditor() {}

    /**
     * Add a {@code <message>} to the service's private message model
     * ({@link FieldEditor.ModelScope#SERVICE_MESSAGES}).
     *
     * @param fields the {@code <field>} declarations to add under the
     *        message. Each field's name and type become the corresponding
     *        XML attributes; any additional attributes on the FieldDef
     *        are passed through.
     * @return a ChangeSet; {@link ChangeSet#isNoop()} if a message with
     *         the same name already exists.
     */
    public static ChangeSet addMessage(Path appRoot,
                                       String serviceName,
                                       String messageName,
                                       List<FieldDef> fields,
                                       boolean dryRun) throws IOException {
        return addMessage(appRoot, serviceName, FieldEditor.ModelScope.SERVICE_MESSAGES,
            messageName, fields, dryRun);
    }

    /**
     * Add a {@code <message>} to the message model named by {@code scope}
     * ({@link FieldEditor.ModelScope#SERVICE_MESSAGES SERVICE_MESSAGES} or
     * {@link FieldEditor.ModelScope#ROE_MESSAGES ROE_MESSAGES} — the shared
     * app-wide model). {@code serviceName} is ignored for the ROE scope.
     *
     * @return a ChangeSet; {@link ChangeSet#isNoop()} if a message with the
     *         same name already exists in that model.
     */
    public static ChangeSet addMessage(Path appRoot,
                                       String serviceName,
                                       FieldEditor.ModelScope scope,
                                       String messageName,
                                       List<FieldDef> fields,
                                       boolean dryRun) throws IOException {
        Path modelFile = resolveMessageModelFile(appRoot, serviceName, scope);
        Document doc = loadMessagesDoc(modelFile);

        if (MessageIntrospector.parseMessages(doc).stream().anyMatch(m -> messageName.equals(m.getName()))) {
            return ChangeSet.noop("message '" + messageName + "' already exists in " + scope + " model");
        }

        Element root = doc.getDocumentElement();
        Element messages = XmlDomUtils.getOrCreateChild(root, "messages");

        Element message = doc.createElementNS(ADML_NAMESPACE, "message");
        message.setAttribute("name", messageName);
        message.setAttribute("id", String.valueOf(ModelIdAllocator.nextTypeId(doc)));
        ModelTypeWriter.appendFields(doc, message, fields);
        messages.appendChild(message);

        return writeBack(doc, modelFile, dryRun);
    }

    /**
     * Remove a {@code <message>} from the service's private message model.
     */
    public static ChangeSet removeMessage(Path appRoot,
                                          String serviceName,
                                          String messageName,
                                          boolean dryRun) throws IOException {
        return removeMessage(appRoot, serviceName, FieldEditor.ModelScope.SERVICE_MESSAGES,
            messageName, dryRun);
    }

    /**
     * Remove a {@code <message>} from the message model named by {@code scope}.
     * The message's id is retired via an {@code <!-- id=N reserved -->} tombstone
     * so it is never reused. No-op if the message is absent.
     */
    public static ChangeSet removeMessage(Path appRoot,
                                          String serviceName,
                                          FieldEditor.ModelScope scope,
                                          String messageName,
                                          boolean dryRun) throws IOException {
        Path modelFile = resolveMessageModelFile(appRoot, serviceName, scope);
        Document doc = loadMessagesDoc(modelFile);

        Element target = findMessageElement(doc, messageName);
        if (target == null) {
            return ChangeSet.noop("no message named '" + messageName + "' in " + scope + " model");
        }

        // Retire the type id with a tombstone so it is never re-handed-out.
        Comment tombstone = ModelIdAllocator.reservedTombstone(doc, target.getAttribute("id"), messageName);
        if (tombstone != null) {
            target.getParentNode().insertBefore(tombstone, target);
        }
        XmlDomUtils.removeElement(target);

        return writeBack(doc, modelFile, dryRun);
    }

    // --- internal -----------------------------------------------------

    /** Resolve the message model file for a scope; rejects the state scope. */
    private static Path resolveMessageModelFile(Path appRoot, String serviceName,
                                                FieldEditor.ModelScope scope) throws IOException {
        if (scope == FieldEditor.ModelScope.SERVICE_STATE) {
            throw new IllegalArgumentException(
                "messages live in a message model, not the state model (scope " + scope + ")");
        }
        Path modelFile = FieldEditor.resolveModelFile(appRoot, serviceName, scope);
        if (!Files.exists(modelFile)) {
            throw new IOException("messages.xml not found at " + modelFile);
        }
        return modelFile;
    }

    private static Document loadMessagesDoc(Path messagesXml) throws IOException {
        try {
            return XmlDomUtils.parseXmlDocument(messagesXml);
        } catch (Exception e) {
            throw new IOException("failed to parse " + messagesXml, e);
        }
    }

    private static Element findMessageElement(Document doc, String messageName) {
        Element root = doc.getDocumentElement();
        if (!ADML_NAMESPACE.equals(root.getNamespaceURI())) return null;
        org.w3c.dom.NodeList messages = doc.getElementsByTagNameNS(ADML_NAMESPACE, "message");
        for (int i = 0; i < messages.getLength(); i++) {
            Element m = (Element) messages.item(i);
            if (messageName.equals(m.getAttribute("name"))) return m;
        }
        return null;
    }

    private static ChangeSet writeBack(Document doc, Path modelFile, boolean dryRun) throws IOException {
        ChangeSet.Builder cs = ChangeSet.builder().addModified(modelFile);
        if (dryRun) return cs.applied(false).build();
        try {
            XmlDomUtils.saveXmlDocument(doc, modelFile);
        } catch (Exception e) {
            throw new IOException("failed to write " + modelFile, e);
        }
        return cs.applied(true).build();
    }
}
