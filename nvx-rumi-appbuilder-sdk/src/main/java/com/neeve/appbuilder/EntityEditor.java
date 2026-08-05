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
import org.w3c.dom.NodeList;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Map;

import static com.neeve.appbuilder.MessageIntrospector.ADML_NAMESPACE;

/**
 * Add and remove whole {@code <entity>} declarations in any X-ADML model that
 * carries an {@code <entities>} container, selected via
 * {@link FieldEditor.ModelScope}:
 *
 * <ul>
 *   <li>{@link FieldEditor.ModelScope#SERVICE_STATE SERVICE_STATE} — a
 *       service's state model ({@code state.xml}); state entities.
 *   <li>{@link FieldEditor.ModelScope#SERVICE_MESSAGES SERVICE_MESSAGES} — a
 *       service's message model; <em>embedded</em> entities used as field types
 *       within that service's messages.
 *   <li>{@link FieldEditor.ModelScope#ROE_MESSAGES ROE_MESSAGES} — the shared
 *       app-wide ROE model; embedded entities usable by every service.
 * </ul>
 *
 * <p>An {@code <entity>} is structurally identical across all three (a named,
 * id-bearing type containing {@code <field>}s), so the same allocation and
 * removal rules apply: a new entity gets a never-reused type id via
 * {@link ModelIdAllocator}, and a removed entity leaves an
 * {@code <!-- id=N reserved -->} tombstone so its id is never re-handed-out.
 * Field-level edits on an entity (add/delete/deprecate/rename) go through
 * {@link FieldEditor}, which already resolves all three scopes.
 *
 * <p>{@link StateEditor} is the state-only facade over this editor.
 */
public final class EntityEditor {
    private EntityEditor() {}

    /**
     * Add an {@code <entity>} to the model named by {@code scope}, with no
     * entity-level attributes. Idempotent on entity name.
     */
    public static ChangeSet addEntity(Path appRoot,
                                      String serviceName,
                                      FieldEditor.ModelScope scope,
                                      String entityName,
                                      List<FieldDef> fields,
                                      boolean dryRun) throws IOException {
        return addEntity(appRoot, serviceName, scope, entityName, Collections.emptyMap(), fields, dryRun);
    }

    /**
     * Add an {@code <entity>} to the model named by {@code scope}, carrying the
     * given entity-level attributes. Idempotent on entity name.
     * {@code serviceName} is ignored for the ROE scope.
     *
     * <p>The most important entity-level attribute is {@code asEmbedded="true"}:
     * an entity used as a {@code <field>} type within a message or another entity
     * <em>must</em> be embedded, or ADM codegen rejects the referencing model.
     * {@code name} and {@code id} in {@code entityAttrs} are ignored — the name
     * comes from {@code entityName} and the id is always allocated by
     * {@link ModelIdAllocator} (never reused).
     *
     * @return a ChangeSet; {@link ChangeSet#isNoop()} if an entity with the same
     *         name already exists in that model.
     */
    public static ChangeSet addEntity(Path appRoot,
                                      String serviceName,
                                      FieldEditor.ModelScope scope,
                                      String entityName,
                                      Map<String, String> entityAttrs,
                                      List<FieldDef> fields,
                                      boolean dryRun) throws IOException {
        Path modelFile = resolveModelFile(appRoot, serviceName, scope);
        Document doc = load(modelFile);

        if (entityExists(doc, entityName)) {
            return ChangeSet.noop("entity '" + entityName + "' already exists in " + scope + " model");
        }

        Element root = doc.getDocumentElement();
        Element entities = XmlDomUtils.getOrCreateChild(root, "entities");

        Element entity = doc.createElementNS(ADML_NAMESPACE, "entity");
        if (entityAttrs != null) {
            for (Map.Entry<String, String> e : entityAttrs.entrySet()) {
                // name and id are owned by the editor — never let callers set them.
                if ("name".equals(e.getKey()) || "id".equals(e.getKey())) continue;
                entity.setAttribute(e.getKey(), e.getValue());
            }
        }
        entity.setAttribute("name", entityName);
        entity.setAttribute("id", String.valueOf(ModelIdAllocator.nextTypeId(doc)));
        ModelTypeWriter.appendFields(doc, entity, fields);
        entities.appendChild(entity);

        return writeBack(doc, modelFile, dryRun);
    }

    /**
     * Remove an {@code <entity>} from the model named by {@code scope}, with
     * referential safety enforced (see the {@code force} overload).
     */
    public static ChangeSet removeEntity(Path appRoot,
                                         String serviceName,
                                         FieldEditor.ModelScope scope,
                                         String entityName,
                                         boolean dryRun) throws IOException {
        return removeEntity(appRoot, serviceName, scope, entityName, dryRun, false);
    }

    /**
     * Remove an {@code <entity>} from the model named by {@code scope}. The
     * entity's id is retired via an {@code <!-- id=N reserved -->} tombstone so
     * it is never reused. No-op if the entity is absent.
     *
     * <p>Unless {@code force} is true, removal is blocked when the entity is
     * still referenced within the same model — by a {@code <field type=…>} on
     * any message/entity, or by a {@code <collection contains=…>} — and an
     * {@link IllegalStateException} naming the referrers is thrown. Remove the
     * references first, or pass {@code force=true} to remove anyway (which leaves
     * the model dangling until the references are fixed).
     */
    public static ChangeSet removeEntity(Path appRoot,
                                         String serviceName,
                                         FieldEditor.ModelScope scope,
                                         String entityName,
                                         boolean dryRun,
                                         boolean force) throws IOException {
        Path modelFile = resolveModelFile(appRoot, serviceName, scope);
        Document doc = load(modelFile);

        Element target = findEntityElement(doc, entityName);
        if (target == null) {
            return ChangeSet.noop("no entity named '" + entityName + "' in " + scope + " model");
        }

        if (!force) {
            List<String> refs = entityReferences(doc, entityName);
            if (!refs.isEmpty()) {
                throw new IllegalStateException("cannot remove entity '" + entityName
                    + "': still referenced by " + String.join(", ", refs)
                    + " (remove the references first, or force the removal)");
            }
        }

        Comment tombstone = ModelIdAllocator.reservedTombstone(doc, target.getAttribute("id"), entityName);
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
            throw new IOException("model file not found at " + modelFile);
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

    private static boolean entityExists(Document doc, String entityName) {
        return findEntityElement(doc, entityName) != null;
    }

    /**
     * Find in-model references to {@code entityName}: {@code <field type=…>} on
     * any message/entity, and {@code <collection contains=…>}. Returns
     * human-readable descriptions for the error message.
     */
    private static List<String> entityReferences(Document doc, String entityName) {
        List<String> refs = new ArrayList<>();
        NodeList fields = doc.getElementsByTagNameNS(ADML_NAMESPACE, "field");
        for (int i = 0; i < fields.getLength(); i++) {
            Element f = (Element) fields.item(i);
            if (entityName.equals(f.getAttribute("type"))) {
                String owner = "";
                org.w3c.dom.Node parent = f.getParentNode();
                if (parent instanceof Element) {
                    Element p = (Element) parent;
                    owner = " on " + p.getLocalName() + " '" + p.getAttribute("name") + "'";
                }
                refs.add("field '" + f.getAttribute("name") + "'" + owner);
            }
        }
        NodeList collections = doc.getElementsByTagNameNS(ADML_NAMESPACE, "collection");
        for (int i = 0; i < collections.getLength(); i++) {
            Element c = (Element) collections.item(i);
            if (entityName.equals(c.getAttribute("contains"))) {
                refs.add("collection '" + c.getAttribute("name") + "'");
            }
        }
        return refs;
    }

    private static Element findEntityElement(Document doc, String entityName) {
        Element root = doc.getDocumentElement();
        if (!ADML_NAMESPACE.equals(root.getNamespaceURI())) return null;
        NodeList entities = doc.getElementsByTagNameNS(ADML_NAMESPACE, "entity");
        for (int i = 0; i < entities.getLength(); i++) {
            Element e = (Element) entities.item(i);
            if (entityName.equals(e.getAttribute("name"))) return e;
        }
        return null;
    }

    private static ChangeSet writeBack(Document doc, Path modelFile, boolean dryRun) throws IOException {
        ChangeSet.Builder cs = ChangeSet.builder().addModified(modelFile);
        try {
            // Dry runs validate too, so a "safe" dry run cannot be followed
            // by a rejected write.
            ModelWriter.saveValidated(doc, modelFile, dryRun);
        } catch (ModelValidationException e) {
            throw e; // a rejected edit is the answer, not a write failure
        } catch (Exception e) {
            throw new IOException("failed to write " + modelFile, e);
        }
        return cs.applied(!dryRun).build();
    }
}
