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

import com.neeve.appbuilder.model.CollectionDef;
import com.neeve.appbuilder.model.EntityDef;
import com.neeve.appbuilder.model.FieldDef;
import org.w3c.dom.Document;
import org.w3c.dom.Element;
import org.w3c.dom.Node;
import org.w3c.dom.NodeList;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Map;

import static com.neeve.appbuilder.MessageIntrospector.ADML_NAMESPACE;
import static com.neeve.appbuilder.MessageIntrospector.attributesOf;
import static com.neeve.appbuilder.MessageIntrospector.firstDirectChildByLocalName;
import static com.neeve.appbuilder.MessageIntrospector.parseFields;
import static com.neeve.appbuilder.MessageIntrospector.parseIntOrNull;

/**
 * Read-only introspection over a service's {@code state.xml}. Returns the
 * {@code <entity>} and {@code <collection>} declarations.
 *
 * <p>Only PROCESSOR services have a state.xml. For DRIVER and CSVWRITER
 * services (and for processors whose state.xml has been deleted), both
 * methods return empty.
 */
public final class StateIntrospector {
    private StateIntrospector() {}

    /**
     * Return every {@code <entity>} declaration in the service's state.xml,
     * in document order.
     */
    public static List<EntityDef> listStateEntities(Path appRoot, String serviceName) throws IOException {
        Document doc = loadStateXml(appRoot, serviceName);
        if (doc == null) return Collections.emptyList();
        return parseEntities(doc);
    }

    /**
     * Return the named {@code <entity>} declaration, or {@code null} if
     * no entity with that name exists in the service's state.xml.
     */
    public static EntityDef getStateEntity(Path appRoot, String serviceName, String entityName) throws IOException {
        for (EntityDef e : listStateEntities(appRoot, serviceName)) {
            if (entityName.equals(e.getName())) return e;
        }
        return null;
    }

    /**
     * Return every {@code <collection>} declaration in the service's
     * state.xml, in document order.
     */
    public static List<CollectionDef> listCollections(Path appRoot, String serviceName) throws IOException {
        Document doc = loadStateXml(appRoot, serviceName);
        if (doc == null) return Collections.emptyList();
        return parseCollections(doc);
    }

    // --- internal -----------------------------------------------------

    private static Document loadStateXml(Path appRoot, String serviceName) throws IOException {
        Path stateXml = AppIntrospector.resolveStateXmlFile(appRoot, serviceName);
        if (!Files.exists(stateXml)) return null;
        try {
            return XmlDomUtils.parseXmlDocument(stateXml);
        } catch (Exception e) {
            throw new IOException("failed to parse " + stateXml, e);
        }
    }

    static List<EntityDef> parseEntities(Document doc) {
        Element root = doc.getDocumentElement();
        if (!ADML_NAMESPACE.equals(root.getNamespaceURI())) return Collections.emptyList();
        Element container = firstDirectChildByLocalName(root, "entities");
        if (container == null) return Collections.emptyList();

        List<EntityDef> result = new ArrayList<>();
        NodeList children = container.getChildNodes();
        for (int i = 0; i < children.getLength(); i++) {
            Node n = children.item(i);
            if (n.getNodeType() == Node.ELEMENT_NODE && "entity".equals(n.getLocalName())) {
                Element e = (Element) n;
                Map<String, String> attrs = attributesOf(e);
                List<FieldDef> fields = parseFields(e);
                result.add(new EntityDef(attrs.get("name"), parseIntOrNull(attrs.get("id")), attrs, fields));
            }
        }
        return result;
    }

    static List<CollectionDef> parseCollections(Document doc) {
        Element root = doc.getDocumentElement();
        if (!ADML_NAMESPACE.equals(root.getNamespaceURI())) return Collections.emptyList();
        Element container = firstDirectChildByLocalName(root, "collections");
        if (container == null) return Collections.emptyList();

        List<CollectionDef> result = new ArrayList<>();
        NodeList children = container.getChildNodes();
        for (int i = 0; i < children.getLength(); i++) {
            Node n = children.item(i);
            if (n.getNodeType() == Node.ELEMENT_NODE && "collection".equals(n.getLocalName())) {
                Element e = (Element) n;
                Map<String, String> attrs = attributesOf(e);
                result.add(new CollectionDef(attrs.get("name"), parseIntOrNull(attrs.get("id")), attrs));
            }
        }
        return result;
    }
}
