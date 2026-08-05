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

import com.neeve.appbuilder.model.ValidationResult;
import com.neeve.appbuilder.model.ValidationResult.ValidationError;
import org.w3c.dom.Document;
import org.w3c.dom.Element;
import org.w3c.dom.Node;
import org.w3c.dom.NodeList;
import org.xml.sax.ErrorHandler;
import org.xml.sax.SAXParseException;

import javax.xml.transform.stream.StreamSource;
import javax.xml.validation.Schema;
import javax.xml.validation.Validator;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * Validate an ADML/ASML model file — {@code messages.xml}, {@code state.xml},
 * {@code api.xml} — before it is written to disk.
 *
 * <h2>Why this exists</h2>
 *
 * A malformed model does not fail where it is created. It fails later, at ADM
 * codegen, inside a Maven run started for an unrelated reason, and the error
 * names a generated Java file rather than the tool call that caused it. For an
 * agent that is close to the worst possible feedback shape: the failure is
 * remote from its cause, buried in a long log, and its obvious reading ("my
 * build is broken") is wrong.
 *
 * <h2>Two layers, because one is not enough</h2>
 *
 * <b>Schema validation</b> against the bundled {@code x-adml.xsd} /
 * {@code x-asml.xsd} (see {@link Schemas}) catches structural damage: unknown
 * elements, misplaced children, non-integer ids, missing required attributes.
 *
 * <p>It does <em>not</em> catch the rules that actually bite, and that is
 * worth stating plainly because it is easy to assume otherwise. The schema
 * declares {@code field/@type} as {@code xs:string} and does no
 * cross-referencing, so all of the following are schema-valid and were
 * verified to validate cleanly:
 *
 * <ul>
 *   <li>{@code type="long"} — lowercase, which the ADM parser does not alias
 *       and silently mis-parses;
 *   <li>an entity used as a message field type without {@code asEmbedded="true"};
 *   <li>{@code type="NoSuchTypeAnywhere"} — a reference to nothing at all.
 * </ul>
 *
 * So a second, semantic layer resolves type references and enforces the three
 * ADML rules learned by running edited models through real codegen. Note the
 * first two are exact inverses of each other, which is precisely why they are
 * worth encoding once here rather than remembering:
 *
 * <ol>
 *   <li>an entity used as a <em>message field type</em> MUST be
 *       {@code asEmbedded="true"} (it is serialized inline into the message);
 *   <li>an entity used as a <em>collection element</em> must NOT be
 *       {@code asEmbedded};
 *   <li>a collection may only contain entity/message types — never scalars.
 * </ol>
 *
 * <h2>Conservative by construction</h2>
 *
 * A false rejection here blocks a legitimate edit, which is worse than the
 * miss it would prevent. So the semantic layer only reports what it can prove
 * from resolved evidence: cross-model checks are skipped entirely when any
 * {@code <import>} could not be read, and fully-qualified type references
 * (which name another namespace) are never flagged as unresolvable. When in
 * doubt it stays silent and lets the schema layer speak.
 */
public final class ModelValidator {

    private ModelValidator() {}

    /**
     * Validate a model file: schema first, then the semantic rules the schema
     * cannot express. Returns a result whose {@link ValidationResult#isOk()}
     * is true only when neither layer found an error.
     *
     * @throws IOException if the file is missing or a bundled schema cannot
     *         be loaded.
     */
    public static ValidationResult validateFile(Path modelFile) throws IOException {
        if (!Files.exists(modelFile)) {
            throw new IOException("model file not found at " + modelFile);
        }
        String namespace = rootNamespaceOf(modelFile);
        Schemas.Kind kind = Schemas.forNamespace(namespace);
        if (kind == null) {
            return failure("not a Rumi model file: root element namespace was "
                + (namespace == null ? "absent" : "'" + namespace + "'")
                + ", expected " + Schemas.Kind.X_ADML.getNamespace()
                + " or " + Schemas.Kind.X_ASML.getNamespace());
        }

        List<ValidationError> errors = new ArrayList<>(schemaErrors(modelFile, kind));

        // Semantic checks apply to ADML models only; ASML operations are
        // checked against the ROE model they point at, which is out of scope
        // for a single-file validation.
        if (kind == Schemas.Kind.X_ADML && errors.isEmpty()) {
            errors.addAll(semanticErrors(modelFile));
        }

        boolean ok = errors.stream().noneMatch(
            e -> e.getSeverity() == ValidationError.Severity.ERROR
              || e.getSeverity() == ValidationError.Severity.FATAL);
        return new ValidationResult(ok, errors);
    }

    /**
     * Validate {@code doc} as it would be written to {@code target}, without
     * touching {@code target}. Used by the editors to validate-then-write, so
     * a rejected edit never leaves a broken file behind — an agent retries
     * far more often than it cleans up.
     */
    public static ValidationResult validatePending(org.w3c.dom.Document doc, Path target)
            throws IOException {
        Path probe = Files.createTempFile("appbuilder-validate-", ".xml");
        try {
            XmlDomUtils.saveXmlDocument(doc, probe);
        } catch (Exception e) {
            Files.deleteIfExists(probe);
            throw new IOException("failed to serialize model for validation: " + target, e);
        }
        try {
            // Resolve <import>s relative to the real destination, not the temp
            // file, or every cross-model reference would look unresolvable.
            return validateSerialized(probe, target);
        } finally {
            Files.deleteIfExists(probe);
        }
    }

    // --- internal -----------------------------------------------------

    private static ValidationResult validateSerialized(Path serialized, Path logicalPath)
            throws IOException {
        String namespace = rootNamespaceOf(serialized);
        Schemas.Kind kind = Schemas.forNamespace(namespace);
        if (kind == null) {
            return failure("not a Rumi model file: root element namespace was "
                + (namespace == null ? "absent" : "'" + namespace + "'")
                + ", expected " + Schemas.Kind.X_ADML.getNamespace()
                + " or " + Schemas.Kind.X_ASML.getNamespace());
        }

        List<ValidationError> errors = new ArrayList<>(schemaErrors(serialized, kind));
        if (kind == Schemas.Kind.X_ADML && errors.isEmpty()) {
            errors.addAll(semanticErrorsOf(serialized, logicalPath));
        }
        boolean ok = errors.stream().noneMatch(
            e -> e.getSeverity() == ValidationError.Severity.ERROR
              || e.getSeverity() == ValidationError.Severity.FATAL);
        return new ValidationResult(ok, errors);
    }

    private static ValidationResult failure(String message) {
        List<ValidationError> errors = new ArrayList<>();
        errors.add(new ValidationError(ValidationError.Severity.ERROR, -1, -1, message));
        return new ValidationResult(false, errors);
    }

    private static String rootNamespaceOf(Path file) throws IOException {
        try {
            Document doc = XmlDomUtils.parseXmlDocument(file);
            Element root = doc.getDocumentElement();
            return root == null ? null : root.getNamespaceURI();
        } catch (Exception e) {
            // A document that will not parse is reported by the schema layer,
            // which produces a far better message than we could here.
            return null;
        }
    }

    private static List<ValidationError> schemaErrors(Path file, Schemas.Kind kind)
            throws IOException {
        Schema schema = Schemas.load(kind);
        Validator validator = schema.newValidator();
        List<ValidationError> errors = new ArrayList<>();
        validator.setErrorHandler(new ErrorHandler() {
            @Override public void warning(SAXParseException ex) {
                errors.add(toError(ValidationError.Severity.WARNING, ex));
            }
            @Override public void error(SAXParseException ex) {
                errors.add(toError(ValidationError.Severity.ERROR, ex));
            }
            @Override public void fatalError(SAXParseException ex) {
                errors.add(toError(ValidationError.Severity.FATAL, ex));
            }
        });
        try {
            validator.validate(new StreamSource(file.toFile()));
        } catch (SAXParseException e) {
            if (errors.stream().noneMatch(err -> err.getMessage().equals(e.getMessage()))) {
                errors.add(toError(ValidationError.Severity.FATAL, e));
            }
        } catch (Exception e) {
            throw new IOException("failed to validate " + file, e);
        }
        return errors;
    }

    private static List<ValidationError> semanticErrors(Path modelFile) {
        return semanticErrorsOf(modelFile, modelFile);
    }

    /**
     * Apply the ADML rules the schema cannot express.
     *
     * @param parseFrom  the file to read the model from
     * @param logicalPath where the model logically lives, used to resolve
     *                    {@code <import>} paths
     */
    private static List<ValidationError> semanticErrorsOf(Path parseFrom, Path logicalPath) {
        List<ValidationError> errors = new ArrayList<>();
        Document doc;
        try {
            doc = XmlDomUtils.parseXmlDocument(parseFrom);
        } catch (Exception e) {
            return errors; // unparseable is the schema layer's story to tell
        }
        Element root = doc.getDocumentElement();
        if (root == null) {
            return errors;
        }

        // Local type inventory: entity name -> asEmbedded, plus message names.
        Map<String, Boolean> entities = new HashMap<>();
        Set<String> messages = new HashSet<>();
        Set<String> collections = new HashSet<>();
        collectTypes(root, entities, messages, collections);

        // Imported models widen the set of names that legitimately resolve.
        // If ANY import cannot be read we lose that knowledge, and flagging an
        // unresolved name would then be a guess — so record the fact and skip
        // the resolvability check entirely.
        Set<String> importedTypes = new HashSet<>();
        boolean allImportsResolved = collectImportedTypes(root, logicalPath, importedTypes);

        for (Element type : childElements(root, "messages", "message")) {
            String owner = "message '" + type.getAttribute("name") + "'";
            for (Element field : directChildren(type, "field")) {
                checkFieldType(field, owner, entities, messages, collections, importedTypes,
                    allImportsResolved, errors);
            }
        }
        for (Element type : childElements(root, "entities", "entity")) {
            String owner = "entity '" + type.getAttribute("name") + "'";
            for (Element field : directChildren(type, "field")) {
                // A field of an entity is serialized the same way a message
                // field is, so the asEmbedded rule applies identically.
                checkFieldType(field, owner, entities, messages, collections, importedTypes,
                    allImportsResolved, errors);
            }
        }
        for (Element collection : childElements(root, "collections", "collection")) {
            checkCollection(collection, entities, messages, importedTypes,
                allImportsResolved, errors);
        }
        return errors;
    }

    private static void checkFieldType(Element field,
                                       String owner,
                                       Map<String, Boolean> entities,
                                       Set<String> messages,
                                       Set<String> collections,
                                       Set<String> importedTypes,
                                       boolean allImportsResolved,
                                       List<ValidationError> errors) {
        String rawType = field.getAttribute("type");
        if (rawType == null || rawType.trim().isEmpty()) {
            return; // required by the schema; already reported there
        }
        String name = field.getAttribute("name");
        String type = AdmTypes.stripArraySuffix(rawType);

        if (AdmTypes.isScalar(type)) {
            // The canonical spelling matters: `long` is not aliased by the ADM
            // parser. Writers normalize, but a model can also arrive by hand.
            String canonical = AdmTypes.normalizeFieldType(type);
            if (!canonical.equals(type)) {
                errors.add(error("field '" + name + "' on " + owner + " has type '" + rawType
                    + "'; ADML scalar types are capitalized — use '" + canonical
                    + "'. The ADM parser does not alias this spelling and will mis-parse it."));
            }
            return;
        }

        Boolean asEmbedded = entities.get(type);
        if (asEmbedded != null) {
            if (!asEmbedded) {
                errors.add(error("field '" + name + "' on " + owner + " has entity type '"
                    + type + "', but entity '" + type + "' is not asEmbedded=\"true\". "
                    + "An entity used as a field type is serialized inline and must be "
                    + "declared asEmbedded."));
            }
            return;
        }
        if (messages.contains(type) || collections.contains(type) || importedTypes.contains(type)) {
            return;
        }
        if (isQualified(type)) {
            return; // names another namespace; not ours to resolve
        }
        if (allImportsResolved) {
            errors.add(error("field '" + name + "' on " + owner + " has type '" + rawType
                + "', which is not an ADML scalar and is not defined in this model or any "
                + "imported model."));
        }
    }

    private static void checkCollection(Element collection,
                                        Map<String, Boolean> entities,
                                        Set<String> messages,
                                        Set<String> importedTypes,
                                        boolean allImportsResolved,
                                        List<ValidationError> errors) {
        String contains = collection.getAttribute("contains");
        if (contains == null || contains.trim().isEmpty()) {
            return;
        }
        String name = collection.getAttribute("name");
        String type = AdmTypes.stripArraySuffix(contains);

        if (AdmTypes.isScalar(type)) {
            errors.add(error("collection '" + name + "' contains '" + contains
                + "', which is a scalar. ADML collections may only contain entity or "
                + "message types."));
            return;
        }
        Boolean asEmbedded = entities.get(type);
        if (asEmbedded != null) {
            if (asEmbedded) {
                errors.add(error("collection '" + name + "' contains entity '" + type
                    + "', which is asEmbedded=\"true\". A collection element must NOT be "
                    + "asEmbedded — that flag is for entities used as a field type, which "
                    + "is the opposite case."));
            }
            return;
        }
        if (messages.contains(type) || importedTypes.contains(type) || isQualified(type)) {
            return;
        }
        if (allImportsResolved) {
            errors.add(error("collection '" + name + "' contains '" + contains
                + "', which is not defined in this model or any imported model."));
        }
    }

    private static void collectTypes(Element root,
                                     Map<String, Boolean> entities,
                                     Set<String> messages,
                                     Set<String> collections) {
        for (Element e : childElements(root, "entities", "entity")) {
            entities.put(e.getAttribute("name"),
                Boolean.parseBoolean(e.getAttribute("asEmbedded")));
        }
        for (Element m : childElements(root, "messages", "message")) {
            messages.add(m.getAttribute("name"));
        }
        for (Element c : childElements(root, "collections", "collection")) {
            collections.add(c.getAttribute("name"));
        }
    }

    /**
     * Read every {@code <import model="..."/>} and add the type names it
     * defines to {@code into}.
     *
     * @return true only if every import was found and parsed. A false return
     *         means the caller must not treat an unresolved name as an error.
     */
    private static boolean collectImportedTypes(Element root, Path logicalPath, Set<String> into) {
        Path modelsRoot = modelsRootOf(logicalPath);
        boolean allResolved = true;
        for (Element imp : directChildren(root, "import")) {
            String rel = imp.getAttribute("model");
            if (rel == null || rel.trim().isEmpty()) {
                continue;
            }
            Path target = modelsRoot == null ? null : modelsRoot.resolve(rel.trim());
            if (target == null || !Files.exists(target)) {
                allResolved = false;
                continue;
            }
            try {
                Document doc = XmlDomUtils.parseXmlDocument(target);
                Element importedRoot = doc.getDocumentElement();
                if (importedRoot == null) {
                    allResolved = false;
                    continue;
                }
                String ns = importedRoot.getAttribute("namespace");
                for (Element e : childElements(importedRoot, "entities", "entity")) {
                    addBothForms(into, ns, e.getAttribute("name"));
                }
                for (Element m : childElements(importedRoot, "messages", "message")) {
                    addBothForms(into, ns, m.getAttribute("name"));
                }
                for (Element c : childElements(importedRoot, "collections", "collection")) {
                    addBothForms(into, ns, c.getAttribute("name"));
                }
            } catch (Exception e) {
                allResolved = false;
            }
        }
        return allResolved;
    }

    private static void addBothForms(Set<String> into, String namespace, String name) {
        if (name == null || name.isEmpty()) {
            return;
        }
        into.add(name);
        if (namespace != null && !namespace.isEmpty()) {
            into.add(namespace + "." + name);
        }
    }

    /**
     * Locate the {@code src/main/models} directory an {@code <import>} path is
     * relative to, by walking up from the model file. Returns null when the
     * file is not under a models root — a temp file in a test, say — which
     * makes imports unresolvable and disables the resolvability check.
     */
    private static Path modelsRootOf(Path modelFile) {
        Path dir = modelFile.getParent();
        while (dir != null) {
            if (dir.getFileName() != null && "models".equals(dir.getFileName().toString())) {
                return dir;
            }
            dir = dir.getParent();
        }
        return null;
    }

    private static boolean isQualified(String type) {
        return type != null && type.contains(".");
    }

    private static List<Element> childElements(Element root, String container, String child) {
        List<Element> out = new ArrayList<>();
        for (Element c : directChildren(root, container)) {
            out.addAll(directChildren(c, child));
        }
        return out;
    }

    private static List<Element> directChildren(Element parent, String localName) {
        List<Element> out = new ArrayList<>();
        NodeList children = parent.getChildNodes();
        for (int i = 0; i < children.getLength(); i++) {
            Node n = children.item(i);
            if (n.getNodeType() == Node.ELEMENT_NODE && localName.equals(n.getLocalName())) {
                out.add((Element) n);
            }
        }
        return out;
    }

    private static ValidationError error(String message) {
        return new ValidationError(ValidationError.Severity.ERROR, -1, -1, message);
    }

    private static ValidationError toError(ValidationError.Severity severity, SAXParseException ex) {
        return new ValidationError(severity, ex.getLineNumber(), ex.getColumnNumber(), ex.getMessage());
    }
}
