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
import org.w3c.dom.Node;
import org.w3c.dom.NodeList;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.Map;

import static com.neeve.appbuilder.MessageIntrospector.ADML_NAMESPACE;

/**
 * Add, delete, deprecate and rename {@code <field>} declarations on a
 * {@code <message>} or {@code <entity>} in any X-ADML model (a service's
 * private message model, the shared ROE message model, or a service's state
 * model).
 *
 * <p>The operations honor Rumi's wire-format backward-compatibility rules:
 *
 * <ul>
 *   <li><b>add</b> assigns a never-reused field id via {@link ModelIdAllocator}.
 *       Idempotent on field name.
 *   <li><b>delete</b> physically removes the field and leaves an
 *       {@code <!-- id=N reserved (removed name) -->} tombstone so the id is
 *       never re-handed-out. A field's <em>type</em> can never change on the
 *       wire, so there is deliberately no "retype" — change a field by
 *       delete + add (which mints a new id).
 *   <li><b>deprecate</b> is distinct from delete: it keeps the field (and its
 *       accessors, marked {@code @Deprecated}) and only sets
 *       {@code deprecated="true"}.
 *   <li><b>rename</b> changes only the {@code name}; the id is unchanged, so it
 *       is wire-safe (callers must still fix any hand-written Java that
 *       referenced the old accessor name).
 * </ul>
 */
public final class FieldEditor {
    private FieldEditor() {}

    /** Which X-ADML model a field lives in. */
    public enum ModelScope {
        SERVICE_MESSAGES,
        SERVICE_STATE,
        ROE_MESSAGES
    }

    /**
     * Add a {@code <field>} to the named {@code <message>}/{@code <entity>}.
     * Idempotent on field name. Extra attributes (e.g. {@code isKey},
     * {@code length}) are passed through.
     */
    public static ChangeSet addField(Path appRoot, String serviceName, ModelScope scope,
                                     String typeName, String fieldName, String fieldType,
                                     Map<String, String> extraAttrs, boolean dryRun) throws IOException {
        Path modelFile = resolveModelFile(appRoot, serviceName, scope);
        Document doc = load(modelFile);
        Element type = requireType(doc, typeName, modelFile);

        if (findField(type, fieldName) != null) {
            return ChangeSet.noop("field '" + fieldName + "' already exists on " + typeName);
        }

        Element field = doc.createElementNS(ADML_NAMESPACE, "field");
        field.setAttribute("name", fieldName);
        if (fieldType != null) field.setAttribute("type", AdmTypes.normalizeFieldType(fieldType));
        if (extraAttrs != null) {
            for (Map.Entry<String, String> e : extraAttrs.entrySet()) {
                field.setAttribute(e.getKey(), e.getValue());
            }
        }
        type.appendChild(field);
        if (!field.hasAttribute("id")) {
            field.setAttribute("id", String.valueOf(ModelIdAllocator.nextFieldId(type)));
        }
        return writeBack(doc, modelFile, dryRun);
    }

    /**
     * Add several {@code <field>}s to one type in a single load-mutate-write
     * (RUMI-412).
     *
     * <p>The point is the round trip, not the payload. Adding fields one at a
     * time costs a full model turn each — 41 of them in the session behind this
     * ticket, 19 in one 70-second run — and each turn re-reads the entire
     * conversation. The XML this writes is identical either way.
     *
     * <p>Atomic by construction: one document is loaded, every field is added to
     * it, and it is validated and written once. A rejected field means nothing
     * is written, so a batch cannot leave a type half-populated.
     *
     * <p>Idempotent per field, matching {@link #addField}: a field that already
     * exists is skipped rather than duplicated or treated as an error, so
     * re-applying a model is safe. A batch in which every field already exists
     * is a no-op.
     *
     * @param fields in declaration order; each may carry extra attributes
     *        ({@code isKey}, {@code length}) exactly as {@link #addField} does.
     */
    public static ChangeSet addFields(Path appRoot, String serviceName, ModelScope scope,
                                      String typeName, List<FieldDef> fields,
                                      boolean dryRun) throws IOException {
        if (fields == null || fields.isEmpty()) {
            return ChangeSet.noop("no fields supplied for " + typeName);
        }
        Path modelFile = resolveModelFile(appRoot, serviceName, scope);
        Document doc = load(modelFile);
        Element type = requireType(doc, typeName, modelFile);

        List<String> added = new java.util.ArrayList<>();
        List<String> skipped = new java.util.ArrayList<>();
        for (FieldDef f : fields) {
            if (findField(type, f.getName()) != null) {
                skipped.add(f.getName());
                continue;
            }
            Element field = doc.createElementNS(ADML_NAMESPACE, "field");
            field.setAttribute("name", f.getName());
            if (f.getType() != null) field.setAttribute("type", AdmTypes.normalizeFieldType(f.getType()));
            if (f.getAttributes() != null) {
                for (Map.Entry<String, String> e : f.getAttributes().entrySet()) {
                    field.setAttribute(e.getKey(), e.getValue());
                }
            }
            type.appendChild(field);
            if (!field.hasAttribute("id")) {
                // Allocated against the live element, so ids stay unique within
                // the batch as well as across it.
                field.setAttribute("id", String.valueOf(ModelIdAllocator.nextFieldId(type)));
            }
            added.add(f.getName());
        }

        if (added.isEmpty()) {
            return ChangeSet.noop("every field already exists on " + typeName + ": " + String.join(", ", skipped));
        }
        ChangeSet cs = writeBack(doc, modelFile, dryRun);
        return skipped.isEmpty() ? cs : withReason(cs, "skipped existing: " + String.join(", ", skipped));
    }

    /** Carry a note about skipped fields without losing the change set. */
    private static ChangeSet withReason(ChangeSet cs, String reason) {
        ChangeSet.Builder b = ChangeSet.builder()
            .applied(cs.isApplied())
            .noop(cs.isNoop())
            .reason(reason)
            .filesCreated(cs.getFilesCreated())
            .filesModified(cs.getFilesModified())
            .filesDeleted(cs.getFilesDeleted());
        return b.build();
    }

    /**
     * Physically remove a {@code <field>} and leave an id-reserved tombstone
     * comment so the id is never reused. No-op if the field is absent.
     */
    public static ChangeSet deleteField(Path appRoot, String serviceName, ModelScope scope,
                                        String typeName, String fieldName, boolean dryRun) throws IOException {
        Path modelFile = resolveModelFile(appRoot, serviceName, scope);
        Document doc = load(modelFile);
        Element type = requireType(doc, typeName, modelFile);

        Element field = findField(type, fieldName);
        if (field == null) {
            return ChangeSet.noop("no field '" + fieldName + "' on " + typeName);
        }

        // Leave a tombstone at the field's position so the id stays reserved.
        Comment tombstone = ModelIdAllocator.reservedTombstone(doc, field.getAttribute("id"), fieldName);
        if (tombstone != null) {
            type.insertBefore(tombstone, field);
        }
        type.removeChild(field);
        return writeBack(doc, modelFile, dryRun);
    }

    /**
     * Mark a {@code <field>} deprecated (keeps it; accessors become
     * {@code @Deprecated}). No-op if absent or already deprecated.
     */
    public static ChangeSet deprecateField(Path appRoot, String serviceName, ModelScope scope,
                                           String typeName, String fieldName, boolean dryRun) throws IOException {
        Path modelFile = resolveModelFile(appRoot, serviceName, scope);
        Document doc = load(modelFile);
        Element type = requireType(doc, typeName, modelFile);

        Element field = findField(type, fieldName);
        if (field == null) {
            return ChangeSet.noop("no field '" + fieldName + "' on " + typeName);
        }
        if ("true".equals(field.getAttribute("deprecated"))) {
            return ChangeSet.noop("field '" + fieldName + "' is already deprecated");
        }
        field.setAttribute("deprecated", "true");
        return writeBack(doc, modelFile, dryRun);
    }

    /**
     * Rename a {@code <field>} (id unchanged — wire-safe). No-op if the old
     * name is absent; fails if the new name already exists.
     */
    public static ChangeSet renameField(Path appRoot, String serviceName, ModelScope scope,
                                        String typeName, String oldName, String newName, boolean dryRun) throws IOException {
        Path modelFile = resolveModelFile(appRoot, serviceName, scope);
        Document doc = load(modelFile);
        Element type = requireType(doc, typeName, modelFile);

        Element field = findField(type, oldName);
        if (field == null) {
            return ChangeSet.noop("no field '" + oldName + "' on " + typeName);
        }
        if (findField(type, newName) != null) {
            throw new IllegalArgumentException("field '" + newName + "' already exists on " + typeName);
        }
        field.setAttribute("name", newName);
        return writeBack(doc, modelFile, dryRun);
    }

    // --- internal -----------------------------------------------------

    /**
     * Resolve the X-ADML model file backing a {@link ModelScope}. Package-visible
     * so the type-level editors ({@link MessageEditor}, {@link EntityEditor}) and
     * {@link MessageIntrospector} resolve the same files the field editor does.
     * For {@link ModelScope#ROE_MESSAGES} the {@code serviceName} is ignored — the
     * ROE model is app-level and shared by every service.
     */
    static Path resolveModelFile(Path appRoot, String serviceName, ModelScope scope) throws IOException {
        switch (scope) {
            case SERVICE_MESSAGES: return AppIntrospector.resolveMessagesXmlFile(appRoot, serviceName);
            case SERVICE_STATE:    return AppIntrospector.resolveStateXmlFile(appRoot, serviceName);
            case ROE_MESSAGES:     return AppIntrospector.resolveRoeMessagesXmlFile(appRoot);
            default: throw new IllegalArgumentException("unknown model scope: " + scope);
        }
    }

    private static Document load(Path modelFile) throws IOException {
        if (!Files.exists(modelFile)) {
            throw new IOException("model file not found at " + modelFile);
        }
        try {
            return XmlDomUtils.parseXmlDocument(modelFile);
        } catch (Exception e) {
            throw new IOException("failed to parse " + modelFile, e);
        }
    }

    /** Locate a {@code <message>} or {@code <entity>} by name. */
    private static Element requireType(Document doc, String typeName, Path modelFile) throws IOException {
        for (String tag : new String[]{"message", "entity"}) {
            NodeList nodes = doc.getElementsByTagNameNS(ADML_NAMESPACE, tag);
            for (int i = 0; i < nodes.getLength(); i++) {
                Element e = (Element) nodes.item(i);
                if (typeName.equals(e.getAttribute("name"))) return e;
            }
        }
        throw new IOException("no message or entity named '" + typeName + "' in " + modelFile);
    }

    private static Element findField(Element type, String fieldName) {
        NodeList kids = type.getChildNodes();
        for (int i = 0; i < kids.getLength(); i++) {
            Node n = kids.item(i);
            if (n.getNodeType() == Node.ELEMENT_NODE
                    && "field".equals(n.getLocalName())
                    && fieldName.equals(((Element) n).getAttribute("name"))) {
                return (Element) n;
            }
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
