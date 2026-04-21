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
import com.neeve.appbuilder.model.MessageDef;
import org.w3c.dom.Document;
import org.w3c.dom.Element;
import org.w3c.dom.Node;
import org.w3c.dom.NodeList;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * Read-only introspection over a service's {@code messages.xml}. Returns
 * the {@code <message>} declarations with their fields, attributes, and
 * optional ids.
 *
 * <p>If the file doesn't exist (which shouldn't happen for a well-formed
 * service — every service type scaffolds a messages.xml), both methods
 * return empty. Callers that want existence assertion can check
 * {@link Files#exists} on the result of
 * {@link AppIntrospector#resolveMessagesXmlFile}.
 */
public final class MessageIntrospector {
    static final String ADML_NAMESPACE = "http://www.neeveresearch.com/schema/x-adml";

    private MessageIntrospector() {}

    /**
     * Return every {@code <message>} declaration in the service's
     * messages.xml, in document order.
     */
    public static List<MessageDef> listMessages(Path appRoot, String serviceName) throws IOException {
        Path messagesXml = AppIntrospector.resolveMessagesXmlFile(appRoot, serviceName);
        if (!Files.exists(messagesXml)) return Collections.emptyList();
        Document doc;
        try {
            doc = XmlDomUtils.parseXmlDocument(messagesXml);
        } catch (Exception e) {
            throw new IOException("failed to parse " + messagesXml, e);
        }
        return parseMessages(doc);
    }

    /**
     * Return the named {@code <message>} declaration, or {@code null} if
     * no message with that name exists in the service's messages.xml.
     */
    public static MessageDef getMessage(Path appRoot, String serviceName, String messageName) throws IOException {
        for (MessageDef m : listMessages(appRoot, serviceName)) {
            if (messageName.equals(m.getName())) return m;
        }
        return null;
    }

    static List<MessageDef> parseMessages(Document doc) {
        Element root = doc.getDocumentElement();
        if (!ADML_NAMESPACE.equals(root.getNamespaceURI())) {
            return Collections.emptyList();
        }
        Element messagesContainer = firstDirectChildByLocalName(root, "messages");
        if (messagesContainer == null) return Collections.emptyList();

        List<MessageDef> result = new ArrayList<>();
        NodeList children = messagesContainer.getChildNodes();
        for (int i = 0; i < children.getLength(); i++) {
            Node n = children.item(i);
            if (n.getNodeType() == Node.ELEMENT_NODE && "message".equals(n.getLocalName())) {
                result.add(parseMessageElement((Element) n));
            }
        }
        return result;
    }

    private static MessageDef parseMessageElement(Element elem) {
        Map<String, String> attrs = attributesOf(elem);
        String name = attrs.get("name");
        Integer id = parseIntOrNull(attrs.get("id"));
        List<FieldDef> fields = parseFields(elem);
        return new MessageDef(name, id, attrs, fields);
    }

    // --- package-visible helpers shared with StateIntrospector --------

    static List<FieldDef> parseFields(Element parent) {
        List<FieldDef> fields = new ArrayList<>();
        NodeList children = parent.getChildNodes();
        for (int i = 0; i < children.getLength(); i++) {
            Node n = children.item(i);
            if (n.getNodeType() == Node.ELEMENT_NODE && "field".equals(n.getLocalName())) {
                Element fieldElem = (Element) n;
                Map<String, String> attrs = attributesOf(fieldElem);
                fields.add(new FieldDef(attrs.get("name"), attrs.get("type"), attrs));
            }
        }
        return fields;
    }

    static Map<String, String> attributesOf(Element elem) {
        Map<String, String> out = new LinkedHashMap<>();
        org.w3c.dom.NamedNodeMap attrs = elem.getAttributes();
        for (int i = 0; i < attrs.getLength(); i++) {
            Node a = attrs.item(i);
            out.put(a.getNodeName(), a.getNodeValue());
        }
        return out;
    }

    static Integer parseIntOrNull(String s) {
        if (s == null || s.isBlank()) return null;
        try {
            return Integer.parseInt(s);
        } catch (NumberFormatException e) {
            return null;
        }
    }

    static Element firstDirectChildByLocalName(Element parent, String localName) {
        NodeList children = parent.getChildNodes();
        for (int i = 0; i < children.getLength(); i++) {
            Node n = children.item(i);
            if (n.getNodeType() == Node.ELEMENT_NODE && localName.equals(n.getLocalName())) {
                return (Element) n;
            }
        }
        return null;
    }
}
