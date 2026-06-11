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
import org.w3c.dom.Document;
import org.w3c.dom.Element;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;

import static com.neeve.appbuilder.MessageIntrospector.ADML_NAMESPACE;

/**
 * Add and remove {@code <message>} declarations in a service's
 * messages.xml. Symmetric with {@link StateEditor} over state.xml.
 *
 * <p>Message IDs are local to the service's message factory — they are
 * not drawn from the app-wide factory-id pool managed by
 * {@link FactoryIdCollector}. Adds pick the next available id by scanning
 * the service's existing messages; removes leave the id unused (the
 * scan-at-next-add picks it up automatically).
 *
 * <p>Adds are identity-matched on {@code messageName}: adding a message
 * with a name that already exists is a noop.
 */
public final class MessageEditor {
    private MessageEditor() {}

    /**
     * Add a {@code <message>} declaration to the service's messages.xml.
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
        Path messagesXml = AppIntrospector.resolveMessagesXmlFile(appRoot, serviceName);
        if (!Files.exists(messagesXml)) {
            throw new IOException("messages.xml not found at " + messagesXml);
        }
        Document doc = loadMessagesDoc(messagesXml);

        if (MessageIntrospector.parseMessages(doc).stream().anyMatch(m -> messageName.equals(m.getName()))) {
            return ChangeSet.noop("message '" + messageName + "' already exists on service " + serviceName);
        }

        Element root = doc.getDocumentElement();
        Element messages = XmlDomUtils.getOrCreateChild(root, "messages");

        Element message = doc.createElementNS(ADML_NAMESPACE, "message");
        message.setAttribute("name", messageName);
        message.setAttribute("id", String.valueOf(ModelIdAllocator.nextTypeId(doc)));
        for (FieldDef fd : fields) {
            Element field = doc.createElementNS(ADML_NAMESPACE, "field");
            for (var entry : fd.getAttributes().entrySet()) {
                field.setAttribute(entry.getKey(), entry.getValue());
            }
            // Ensure name/type are set even if caller's attribute map omitted them.
            if (fd.getName() != null && !fd.getAttributes().containsKey("name")) {
                field.setAttribute("name", fd.getName());
            }
            if (fd.getType() != null && !fd.getAttributes().containsKey("type")) {
                field.setAttribute("type", fd.getType());
            }
            // Assign a stable, never-reused field id if the caller didn't supply one.
            message.appendChild(field);
            if (!field.hasAttribute("id")) {
                field.setAttribute("id", String.valueOf(ModelIdAllocator.nextFieldId(message)));
            }
        }
        messages.appendChild(message);

        ChangeSet.Builder cs = ChangeSet.builder().addModified(messagesXml);
        if (dryRun) return cs.applied(false).build();
        try {
            XmlDomUtils.saveXmlDocument(doc, messagesXml);
        } catch (Exception e) {
            throw new IOException("failed to write " + messagesXml, e);
        }
        return cs.applied(true).build();
    }

    /**
     * Remove the named {@code <message>} from messages.xml.
     */
    public static ChangeSet removeMessage(Path appRoot,
                                          String serviceName,
                                          String messageName,
                                          boolean dryRun) throws IOException {
        Path messagesXml = AppIntrospector.resolveMessagesXmlFile(appRoot, serviceName);
        if (!Files.exists(messagesXml)) {
            throw new IOException("messages.xml not found at " + messagesXml);
        }
        Document doc = loadMessagesDoc(messagesXml);

        Element target = findMessageElement(doc, messageName);
        if (target == null) {
            return ChangeSet.noop("no message named '" + messageName + "' on service " + serviceName);
        }

        XmlDomUtils.removeElement(target);

        ChangeSet.Builder cs = ChangeSet.builder().addModified(messagesXml);
        if (dryRun) return cs.applied(false).build();
        try {
            XmlDomUtils.saveXmlDocument(doc, messagesXml);
        } catch (Exception e) {
            throw new IOException("failed to write " + messagesXml, e);
        }
        return cs.applied(true).build();
    }

    // --- internal -----------------------------------------------------

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
}
