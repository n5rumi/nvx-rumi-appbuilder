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
 * Add and remove {@code <entity>} declarations in a service's state.xml.
 * Symmetric with {@link MessageEditor}. Only processor and webservice
 * services have a state.xml; calling on a driver or connector throws because
 * there's nothing to edit.
 *
 * <p>Entity IDs are local to the service's state factory — same
 * allocation behaviour as MessageEditor's message IDs.
 */
public final class StateEditor {
    private StateEditor() {}

    /**
     * Add an {@code <entity>} declaration to the service's state.xml.
     *
     * @return a ChangeSet; {@link ChangeSet#isNoop()} if an entity with
     *         the same name already exists.
     */
    public static ChangeSet addStateEntity(Path appRoot,
                                           String serviceName,
                                           String entityName,
                                           List<FieldDef> fields,
                                           boolean dryRun) throws IOException {
        Path stateXml = AppIntrospector.resolveStateXmlFile(appRoot, serviceName);
        if (!Files.exists(stateXml)) {
            throw new IOException("state.xml not found at " + stateXml
                + " (is this a processor service?)");
        }
        Document doc = loadStateDoc(stateXml);

        if (StateIntrospector.parseEntities(doc).stream().anyMatch(e -> entityName.equals(e.getName()))) {
            return ChangeSet.noop("entity '" + entityName + "' already exists on service " + serviceName);
        }

        Element root = doc.getDocumentElement();
        Element entities = XmlDomUtils.getOrCreateChild(root, "entities");

        Element entity = doc.createElementNS(ADML_NAMESPACE, "entity");
        entity.setAttribute("name", entityName);
        entity.setAttribute("id", String.valueOf(nextEntityId(doc)));
        for (FieldDef fd : fields) {
            Element field = doc.createElementNS(ADML_NAMESPACE, "field");
            for (var entry : fd.getAttributes().entrySet()) {
                field.setAttribute(entry.getKey(), entry.getValue());
            }
            if (fd.getName() != null && !fd.getAttributes().containsKey("name")) {
                field.setAttribute("name", fd.getName());
            }
            if (fd.getType() != null && !fd.getAttributes().containsKey("type")) {
                field.setAttribute("type", fd.getType());
            }
            entity.appendChild(field);
        }
        entities.appendChild(entity);

        ChangeSet.Builder cs = ChangeSet.builder().addModified(stateXml);
        if (dryRun) return cs.applied(false).build();
        try {
            XmlDomUtils.saveXmlDocument(doc, stateXml);
        } catch (Exception e) {
            throw new IOException("failed to write " + stateXml, e);
        }
        return cs.applied(true).build();
    }

    /**
     * Remove the named {@code <entity>} from state.xml.
     */
    public static ChangeSet removeStateEntity(Path appRoot,
                                              String serviceName,
                                              String entityName,
                                              boolean dryRun) throws IOException {
        Path stateXml = AppIntrospector.resolveStateXmlFile(appRoot, serviceName);
        if (!Files.exists(stateXml)) {
            throw new IOException("state.xml not found at " + stateXml);
        }
        Document doc = loadStateDoc(stateXml);

        Element target = findEntityElement(doc, entityName);
        if (target == null) {
            return ChangeSet.noop("no entity named '" + entityName + "' on service " + serviceName);
        }

        XmlDomUtils.removeElement(target);

        ChangeSet.Builder cs = ChangeSet.builder().addModified(stateXml);
        if (dryRun) return cs.applied(false).build();
        try {
            XmlDomUtils.saveXmlDocument(doc, stateXml);
        } catch (Exception e) {
            throw new IOException("failed to write " + stateXml, e);
        }
        return cs.applied(true).build();
    }

    // --- internal -----------------------------------------------------

    private static Document loadStateDoc(Path stateXml) throws IOException {
        try {
            return XmlDomUtils.parseXmlDocument(stateXml);
        } catch (Exception e) {
            throw new IOException("failed to parse " + stateXml, e);
        }
    }

    private static int nextEntityId(Document doc) {
        var entities = StateIntrospector.parseEntities(doc);
        java.util.Set<Integer> used = new java.util.HashSet<>();
        for (var e : entities) {
            if (e.getId() != null) used.add(e.getId());
        }
        int candidate = 1;
        while (used.contains(candidate)) candidate++;
        return candidate;
    }

    private static Element findEntityElement(Document doc, String entityName) {
        Element root = doc.getDocumentElement();
        if (!ADML_NAMESPACE.equals(root.getNamespaceURI())) return null;
        org.w3c.dom.NodeList entities = doc.getElementsByTagNameNS(ADML_NAMESPACE, "entity");
        for (int i = 0; i < entities.getLength(); i++) {
            Element e = (Element) entities.item(i);
            if (entityName.equals(e.getAttribute("name"))) return e;
        }
        return null;
    }
}
