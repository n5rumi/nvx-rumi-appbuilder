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

import com.neeve.appbuilder.model.ConnectorDef;
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
 * Read-only introspection over the connectors snapped into a service. A
 * connector is a {@code <bus descriptor="connector://...">} binding in the
 * system {@code config.xml} whose {@code classname} resolves under the
 * service's {@code connector} subpackage. Symmetric with
 * {@link ConnectorEditor}.
 */
public final class ConnectorIntrospector {
    private ConnectorIntrospector() {}

    /**
     * Return every connector bound to the named service, in document order.
     * Empty if the service has no connectors (or no config.xml).
     */
    public static List<ConnectorDef> listConnectors(Path appRoot, String serviceName) throws IOException {
        ApplicationBuilder.AppParams params = AppIntrospector.loadAppParams(appRoot);
        Map<String, String> tokens = params.getTokenMap();
        Path configPath = ConnectorSupport.configPath(appRoot, tokens);
        if (!Files.exists(configPath)) return Collections.emptyList();

        String serviceKebab = TokenUtils.toKebabCase(serviceName);
        String appPkgName = tokens.get(TokenUtils.toToken("AppPackageName"));
        String connectorPkgPrefix = ConnectorSupport.connectorPackage(appPkgName, serviceKebab) + ".";
        String serviceFullName = ConnectorSupport.serviceFullName(tokens, serviceKebab);

        Document doc;
        try {
            doc = XmlDomUtils.parseXmlDocument(configPath);
        } catch (Exception e) {
            throw new IOException("failed to parse " + configPath, e);
        }

        Element buses = XmlDomUtils.getElementByPath(doc.getDocumentElement(),
            Collections.singletonList("buses"));
        if (buses == null) return Collections.emptyList();

        List<ConnectorDef> out = new ArrayList<>();
        NodeList children = buses.getChildNodes();
        for (int i = 0; i < children.getLength(); i++) {
            Node n = children.item(i);
            if (n.getNodeType() != Node.ELEMENT_NODE || !"bus".equals(n.getLocalName())) continue;
            Element bus = (Element) n;
            String descriptor = bus.getAttribute("descriptor");
            if (descriptor == null || !descriptor.startsWith("connector://")) continue;
            String className = ConnectorSupport.descriptorParam(descriptor, "classname");
            if (className == null || !className.startsWith(connectorPkgPrefix)) continue;

            String busName = bus.getAttribute("name");
            String channel = ConnectorSupport.descriptorParam(descriptor, "inbound_channel");
            String connectorName = ConnectorSupport.connectorNameFromBus(busName, serviceFullName, className);
            Path javaFile = AppIntrospector.resolveMainJavaFile(appRoot, serviceName)
                    .getParent().resolve("connector")
                    .resolve(className.substring(className.lastIndexOf('.') + 1) + ".java");

            out.add(new ConnectorDef(connectorName, className, busName, channel, descriptor, javaFile));
        }
        return out;
    }

    /**
     * Return the named connector, or {@code null} if the service has no
     * connector by that name.
     */
    public static ConnectorDef getConnector(Path appRoot, String serviceName, String connectorName) throws IOException {
        String kebab = TokenUtils.toKebabCase(connectorName);
        for (ConnectorDef c : listConnectors(appRoot, serviceName)) {
            if (kebab.equals(c.getName())) return c;
        }
        return null;
    }
}
