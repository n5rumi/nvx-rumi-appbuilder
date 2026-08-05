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
import com.neeve.appbuilder.model.MessageDef;
import org.w3c.dom.Document;
import org.w3c.dom.Element;
import org.w3c.dom.Node;
import org.w3c.dom.NodeList;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Set;
import java.util.stream.Stream;

import static com.neeve.appbuilder.ApiIntrospector.ASML_NAMESPACE;

/**
 * Add, remove and rename request-reply {@code <operation>} declarations in a
 * service's {@code api.xml} (X-ASML). Each operation pairs a request message
 * with a response message; the Rumi {@code asm-generate} goal turns it into a
 * typed client method.
 *
 * <p>Operations carry no wire id, so removing one simply drops a generated
 * client method — there is no id to retire. Adds validate that
 * {@code inMessage}/{@code outMessage} resolve against the single message model
 * this service's api.xml names, which is what the generator does, so a message
 * the client cannot be built from is rejected here rather than at codegen.
 */
public final class ApiOperationEditor {
    private ApiOperationEditor() {}

    /** Add an {@code <operation>}. Idempotent on operation name. */
    public static ChangeSet addOperation(Path appRoot, String serviceName, String name,
                                         String inMessage, String outMessage,
                                         String restPath, String restMethod,
                                         boolean dryRun) throws IOException {
        Path apiXml = AppIntrospector.resolveApiXmlFile(appRoot, serviceName);
        if (!Files.exists(apiXml)) throw new IOException("api.xml not found at " + apiXml);
        Document doc = load(apiXml);

        if (findOperation(doc, name) != null) {
            return ChangeSet.noop("operation '" + name + "' already exists on service " + serviceName);
        }
        ResolutionScope scope = resolutionScope(doc, appRoot);
        if (scope != null) {
            if (inMessage != null && !scope.messageNames.contains(inMessage)) {
                throw new IllegalArgumentException(unresolvable("inMessage", inMessage, scope));
            }
            if (outMessage != null && !scope.messageNames.contains(outMessage)) {
                throw new IllegalArgumentException(unresolvable("outMessage", outMessage, scope));
            }
        }

        Element operations = XmlDomUtils.getOrCreateChild(doc.getDocumentElement(), "operations");
        Element op = doc.createElementNS(ASML_NAMESPACE, "operation");
        op.setAttribute("name", name);
        if (inMessage != null) op.setAttribute("inMessage", inMessage);
        if (outMessage != null) op.setAttribute("outMessage", outMessage);
        if (restPath != null && !restPath.isBlank()) op.setAttribute("RESTPath", restPath);
        if (restMethod != null && !restMethod.isBlank()) op.setAttribute("RESTMethod", restMethod);
        operations.appendChild(op);

        return writeBack(doc, apiXml, dryRun);
    }

    /** Remove the named operation. No-op if absent. */
    public static ChangeSet removeOperation(Path appRoot, String serviceName, String name, boolean dryRun) throws IOException {
        Path apiXml = AppIntrospector.resolveApiXmlFile(appRoot, serviceName);
        if (!Files.exists(apiXml)) throw new IOException("api.xml not found at " + apiXml);
        Document doc = load(apiXml);

        Element op = findOperation(doc, name);
        if (op == null) {
            return ChangeSet.noop("no operation named '" + name + "' on service " + serviceName);
        }
        XmlDomUtils.removeElement(op);
        return writeBack(doc, apiXml, dryRun);
    }

    /** Rename an operation. No-op if old name absent; fails if new name exists. */
    public static ChangeSet renameOperation(Path appRoot, String serviceName, String oldName, String newName, boolean dryRun) throws IOException {
        Path apiXml = AppIntrospector.resolveApiXmlFile(appRoot, serviceName);
        if (!Files.exists(apiXml)) throw new IOException("api.xml not found at " + apiXml);
        Document doc = load(apiXml);

        Element op = findOperation(doc, oldName);
        if (op == null) return ChangeSet.noop("no operation named '" + oldName + "' on service " + serviceName);
        if (findOperation(doc, newName) != null) {
            throw new IllegalArgumentException("operation '" + newName + "' already exists on service " + serviceName);
        }
        op.setAttribute("name", newName);
        return writeBack(doc, apiXml, dryRun);
    }

    // --- internal -----------------------------------------------------

    private static Document load(Path apiXml) throws IOException {
        try {
            return XmlDomUtils.parseXmlDocument(apiXml);
        } catch (Exception e) {
            throw new IOException("failed to parse " + apiXml, e);
        }
    }

    private static Element findOperation(Document doc, String name) {
        NodeList ops = doc.getElementsByTagNameNS(ASML_NAMESPACE, "operation");
        for (int i = 0; i < ops.getLength(); i++) {
            Element e = (Element) ops.item(i);
            if (name.equals(e.getAttribute("name"))) return e;
        }
        return null;
    }

    /** The single model an api.xml's operations resolve against, and its message names. */
    private static final class ResolutionScope {
        final String modelFile;      // as written in the api.xml
        final Set<String> messageNames;

        ResolutionScope(String modelFile, Set<String> messageNames) {
            this.modelFile = modelFile;
            this.messageNames = messageNames;
        }
    }

    /**
     * Resolve the ONE message model this api.xml's operations are checked
     * against: the model named by its {@code <messages modelFile="..."/>}.
     *
     * <p>Two things this deliberately does not do, both of which were how an
     * earlier version got it wrong. It does not assume the model is the shared
     * ROE model — that is a scaffolding convention, and the api.xml belongs to
     * the user, who may point it anywhere. And it does not union the service's
     * own message model in: {@code AsmModel.resolveMessage} does a local lookup
     * on the named model and does not follow that model's imports, so a
     * validator that accepted more than the generator resolves would let
     * exactly the bad edit through that this check exists to catch.
     *
     * <p>The rule is to mirror the generator. Broader lets a bad edit through;
     * narrower rejects a legitimate one.
     *
     * @return null when the scope cannot be established (no {@code <messages>}
     *         element, or the model file is missing or unparseable), in which
     *         case the caller must skip the check rather than guess — the same
     *         conservative posture {@link ModelValidator} takes.
     */
    private static ResolutionScope resolutionScope(Document apiDoc, Path appRoot) {
        Element messages = firstChild(apiDoc.getDocumentElement(), "messages");
        if (messages == null) {
            return null;
        }
        String modelFile = messages.getAttribute("modelFile");
        if (modelFile == null || modelFile.trim().isEmpty()) {
            return null;
        }
        modelFile = modelFile.trim();

        Path target = resolveModelFile(appRoot, modelFile);
        if (target == null) {
            return null;
        }
        Set<String> names = new HashSet<>();
        try {
            for (MessageDef m : MessageIntrospector.parseMessages(XmlDomUtils.parseXmlDocument(target))) {
                names.add(m.getName());
            }
        } catch (Exception e) {
            return null;
        }
        return new ResolutionScope(modelFile, names);
    }

    /**
     * Locate the model named by an api.xml's {@code modelFile}, which is a
     * package-style path relative to a models root.
     *
     * <p>It is resolved against EVERY module's {@code src/main/models}, not
     * just the service's own, because the shared ROE model — the one a
     * scaffolded api.xml names — lives in a different Maven module. At build
     * time the generator finds it on the classpath, where module boundaries
     * have already been flattened; here they have not.
     *
     * @return the model file, or null if no module has it.
     */
    private static Path resolveModelFile(Path appRoot, String modelFile) {
        List<Path> roots = new ArrayList<>();
        try (Stream<Path> modules = Files.list(appRoot)) {
            modules.filter(Files::isDirectory)
                   .map(m -> m.resolve("src").resolve("main").resolve("models"))
                   .filter(Files::isDirectory)
                   .forEach(roots::add);
        } catch (IOException e) {
            return null;
        }
        for (Path root : roots) {
            Path candidate = root.resolve(modelFile);
            if (Files.exists(candidate)) {
                return candidate;
            }
        }
        return null;
    }

    private static String unresolvable(String attribute, String message, ResolutionScope scope) {
        return attribute + " '" + message + "' is not defined in '" + scope.modelFile
            + "', the message model this service's api.xml resolves operations against. "
            + "A message named by an operation is part of the service's public contract, "
            + "so it belongs in that model — for a scaffolded app that is the shared ROE "
            + "model (add it with scope \"roe\"). A service's own message model is for "
            + "messages that never leave the service.";
    }

    private static Element firstChild(Element parent, String localName) {
        if (parent == null) {
            return null;
        }
        NodeList children = parent.getChildNodes();
        for (int i = 0; i < children.getLength(); i++) {
            Node n = children.item(i);
            if (n instanceof Element && localName.equals(n.getLocalName())) {
                return (Element) n;
            }
        }
        return null;
    }

    private static ChangeSet writeBack(Document doc, Path apiXml, boolean dryRun) throws IOException {
        ChangeSet.Builder cs = ChangeSet.builder().addModified(apiXml);
        try {
            // Dry runs validate too, so a "safe" dry run cannot be followed
            // by a rejected write.
            ModelWriter.saveValidated(doc, apiXml, dryRun);
        } catch (ModelValidationException e) {
            throw e; // a rejected edit is the answer, not a write failure
        } catch (Exception e) {
            throw new IOException("failed to write " + apiXml, e);
        }
        return cs.applied(!dryRun).build();
    }
}
