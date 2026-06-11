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
import org.w3c.dom.Comment;
import org.w3c.dom.Document;
import org.w3c.dom.Element;
import org.w3c.dom.NodeList;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Map;

import static com.neeve.appbuilder.MessageIntrospector.ADML_NAMESPACE;

/**
 * Add and remove {@code <collection>} declarations in a model's
 * {@code <collections>} container. A collection is a low-level X-ADML
 * structure — {@code <collection name=… is=StringMap|IntMap|…|Queue
 * contains=Elem id=…>} — so this editor is deliberately low-level too: the
 * caller supplies {@code is} (the collection kind) and {@code contains} (the
 * element type) verbatim. Collections live in a service's state model in
 * practice; the editor is scope-parametrized for symmetry with
 * {@link EntityEditor} but most callers use {@link #addCollection(Path, String,
 * String, String, String, boolean)} (state).
 *
 * <p>Allocation and removal follow the same never-reuse rules as messages and
 * entities — collections share the model's type-id space (see
 * {@link ModelIdAllocator}) and a removed collection leaves an
 * {@code <!-- id=N reserved -->} tombstone.
 */
public final class CollectionEditor {
    private CollectionEditor() {}

    /** Add a {@code <collection>} to the service's state model. */
    public static ChangeSet addCollection(Path appRoot, String serviceName,
                                          String name, String is, String contains,
                                          boolean dryRun) throws IOException {
        return addCollection(appRoot, serviceName, FieldEditor.ModelScope.SERVICE_STATE,
            name, is, contains, null, dryRun);
    }

    /**
     * Add a {@code <collection>} to the model named by {@code scope}. Idempotent
     * on collection name. {@code is} and {@code contains} are required (the X-ADML
     * schema marks them mandatory); extra attributes pass through. {@code name}
     * and {@code id} in {@code extraAttrs} are ignored.
     *
     * @return a ChangeSet; {@link ChangeSet#isNoop()} if a collection with the
     *         same name already exists.
     */
    public static ChangeSet addCollection(Path appRoot, String serviceName,
                                          FieldEditor.ModelScope scope,
                                          String name, String is, String contains,
                                          Map<String, String> extraAttrs,
                                          boolean dryRun) throws IOException {
        if (name == null || name.isBlank()) throw new IllegalArgumentException("collection name is required");
        if (is == null || is.isBlank()) throw new IllegalArgumentException("collection 'is' (kind) is required");
        if (contains == null || contains.isBlank()) throw new IllegalArgumentException("collection 'contains' (element type) is required");

        Path modelFile = resolveModelFile(appRoot, serviceName, scope);
        Document doc = load(modelFile);

        if (findCollection(doc, name) != null) {
            return ChangeSet.noop("collection '" + name + "' already exists in " + scope + " model");
        }

        Element root = doc.getDocumentElement();
        Element collections = XmlDomUtils.getOrCreateChild(root, "collections");

        Element collection = doc.createElementNS(ADML_NAMESPACE, "collection");
        if (extraAttrs != null) {
            for (Map.Entry<String, String> e : extraAttrs.entrySet()) {
                if ("name".equals(e.getKey()) || "id".equals(e.getKey())) continue;
                collection.setAttribute(e.getKey(), e.getValue());
            }
        }
        collection.setAttribute("name", name);
        collection.setAttribute("is", is);
        collection.setAttribute("contains", AdmTypes.normalizeFieldType(contains));
        collection.setAttribute("id", String.valueOf(ModelIdAllocator.nextTypeId(doc)));
        collections.appendChild(collection);

        return writeBack(doc, modelFile, dryRun);
    }

    /** Remove a {@code <collection>} from the service's state model. */
    public static ChangeSet removeCollection(Path appRoot, String serviceName,
                                             String name, boolean dryRun) throws IOException {
        return removeCollection(appRoot, serviceName, FieldEditor.ModelScope.SERVICE_STATE, name, dryRun);
    }

    /**
     * Remove a {@code <collection>} from the model named by {@code scope}. Its id
     * is retired via a tombstone so it is never reused. No-op if absent.
     */
    public static ChangeSet removeCollection(Path appRoot, String serviceName,
                                             FieldEditor.ModelScope scope,
                                             String name, boolean dryRun) throws IOException {
        Path modelFile = resolveModelFile(appRoot, serviceName, scope);
        Document doc = load(modelFile);

        Element target = findCollection(doc, name);
        if (target == null) {
            return ChangeSet.noop("no collection named '" + name + "' in " + scope + " model");
        }

        Comment tombstone = ModelIdAllocator.reservedTombstone(doc, target.getAttribute("id"), name);
        if (tombstone != null) {
            target.getParentNode().insertBefore(tombstone, target);
        }
        XmlDomUtils.removeElement(target);

        return writeBack(doc, modelFile, dryRun);
    }

    // --- internal -----------------------------------------------------

    private static Path resolveModelFile(Path appRoot, String serviceName,
                                         FieldEditor.ModelScope scope) throws IOException {
        Path modelFile = FieldEditor.resolveModelFile(appRoot, serviceName, scope);
        if (!Files.exists(modelFile)) {
            throw new IOException("model file not found at " + modelFile
                + (scope == FieldEditor.ModelScope.SERVICE_STATE ? " (is this a processor service?)" : ""));
        }
        return modelFile;
    }

    private static Document load(Path modelFile) throws IOException {
        try {
            return XmlDomUtils.parseXmlDocument(modelFile);
        } catch (Exception e) {
            throw new IOException("failed to parse " + modelFile, e);
        }
    }

    private static Element findCollection(Document doc, String name) {
        Element root = doc.getDocumentElement();
        if (!ADML_NAMESPACE.equals(root.getNamespaceURI())) return null;
        NodeList collections = doc.getElementsByTagNameNS(ADML_NAMESPACE, "collection");
        for (int i = 0; i < collections.getLength(); i++) {
            Element c = (Element) collections.item(i);
            if (name.equals(c.getAttribute("name"))) return c;
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
