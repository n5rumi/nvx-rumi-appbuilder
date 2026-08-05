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
import java.util.HashSet;
import java.util.Set;

import static com.neeve.appbuilder.ApiIntrospector.ASML_NAMESPACE;

/**
 * Add, remove and rename request-reply {@code <operation>} declarations in a
 * service's {@code api.xml} (X-ASML). Each operation pairs a request message
 * with a response message; the Rumi {@code asm-generate} goal turns it into a
 * typed client method.
 *
 * <p>Operations carry no wire id, so removing one simply drops a generated
 * client method — there is no id to retire. Adds validate that the
 * {@code inMessage}/{@code outMessage} actually exist (in the service's own
 * message model or the shared ROE model), so a typo can't silently produce a
 * broken client.
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
        Set<String> known = knownMessageNames(appRoot, serviceName);
        if (!known.isEmpty()) {
            if (inMessage != null && !known.contains(inMessage)) {
                throw new IllegalArgumentException("inMessage '" + inMessage + "' is not a known message");
            }
            if (outMessage != null && !known.contains(outMessage)) {
                throw new IllegalArgumentException("outMessage '" + outMessage + "' is not a known message");
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

    /** Message names visible to this service's api: its own model plus the shared ROE model. */
    private static Set<String> knownMessageNames(Path appRoot, String serviceName) throws IOException {
        Set<String> names = new HashSet<>();
        for (MessageDef m : MessageIntrospector.listMessages(appRoot, serviceName)) {
            names.add(m.getName());
        }
        Path roe = AppIntrospector.resolveRoeMessagesXmlFile(appRoot);
        if (Files.exists(roe)) {
            try {
                for (MessageDef m : MessageIntrospector.parseMessages(XmlDomUtils.parseXmlDocument(roe))) {
                    names.add(m.getName());
                }
            } catch (Exception ignored) {
                // best-effort: if ROE can't be read, validate against what we have
            }
        }
        return names;
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
