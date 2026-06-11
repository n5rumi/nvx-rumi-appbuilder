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

import com.neeve.appbuilder.model.ApiOperationDef;
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

/**
 * Read-only introspection over a service's {@code api.xml} (X-ASML): the
 * request-reply {@code <operation>} declarations. Symmetric with
 * {@link ApiOperationEditor}.
 */
public final class ApiIntrospector {
    private ApiIntrospector() {}

    static final String ASML_NAMESPACE = "http://www.neeveresearch.com/schema/x-asml";

    /** Return every {@code <operation>} declared in the service's api.xml, in document order. */
    public static List<ApiOperationDef> listOperations(Path appRoot, String serviceName) throws IOException {
        Document doc = loadApiXml(appRoot, serviceName);
        if (doc == null) return Collections.emptyList();
        return parseOperations(doc);
    }

    /** Return the named operation, or {@code null} if absent. */
    public static ApiOperationDef getOperation(Path appRoot, String serviceName, String name) throws IOException {
        for (ApiOperationDef op : listOperations(appRoot, serviceName)) {
            if (name.equals(op.getName())) return op;
        }
        return null;
    }

    // --- internal -----------------------------------------------------

    static Document loadApiXml(Path appRoot, String serviceName) throws IOException {
        Path apiXml = AppIntrospector.resolveApiXmlFile(appRoot, serviceName);
        if (!Files.exists(apiXml)) return null;
        try {
            return XmlDomUtils.parseXmlDocument(apiXml);
        } catch (Exception e) {
            throw new IOException("failed to parse " + apiXml, e);
        }
    }

    static List<ApiOperationDef> parseOperations(Document doc) {
        Element root = doc.getDocumentElement();
        if (!ASML_NAMESPACE.equals(root.getNamespaceURI())) return Collections.emptyList();
        Element container = MessageIntrospector.firstDirectChildByLocalName(root, "operations");
        if (container == null) return Collections.emptyList();

        List<ApiOperationDef> result = new ArrayList<>();
        NodeList kids = container.getChildNodes();
        for (int i = 0; i < kids.getLength(); i++) {
            Node n = kids.item(i);
            if (n.getNodeType() == Node.ELEMENT_NODE && "operation".equals(n.getLocalName())) {
                Element e = (Element) n;
                Map<String, String> attrs = MessageIntrospector.attributesOf(e);
                result.add(new ApiOperationDef(
                    attrs.get("name"), attrs.get("inMessage"), attrs.get("outMessage"), attrs));
            }
        }
        return result;
    }
}
