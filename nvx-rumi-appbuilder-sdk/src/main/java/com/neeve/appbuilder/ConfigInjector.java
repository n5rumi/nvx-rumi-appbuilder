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

import org.w3c.dom.*;

import java.nio.file.*;
import java.util.*;

class ConfigInjector {
    static void injectServiceConfig(Path appRoot, ServiceBuilder.ServiceParams params) throws Exception {
        // get the config file to inject into
        Path configPath = appRoot
                .resolve(params.getTokenMap().get(TokenUtils.toToken("SystemArtifactId")))
                .resolve("conf/config.xml");

        // convert to DOM model
        Document doc = XmlDomUtils.parseXmlDocument(configPath);

        // extract config templates
        String templateRoot = String.format("templates/%s/config/%s",
                                            params.getTokenMap().get(TokenUtils.toToken("BuildTool")),
                                            params.getServiceType().getName());
        if (params.getServiceHAModel() != null) {
            templateRoot = templateRoot + "/" + params.getServiceHAModel().getName();
        }
        Path configTemplatesDir = TemplateProcessor.extractTemplateDirectory("rumi-service-config-template", templateRoot, true);

        // inject if template path was found
        if (configTemplatesDir != null) {
            try {
                int numInstances = params.getNumPartitions() * (params.isClustered() ? 2 : 1);
                for (int i = 0 ; i < numInstances ; i++) {
                    params.getTokenMap().put(TokenUtils.toToken("ServicePartition"), String.valueOf((params.isClustered() ? i/2 : i) + 1));
                    params.getTokenMap().put(TokenUtils.toToken("ServiceInstance"), String.valueOf(i+1));
                    Files.walk(configTemplatesDir)
                            .filter(p -> p.getFileName().toString().equals("config.xml"))
                            .forEach(templatePath -> {
                                try {
                                    String injectionContent = Files.readString(templatePath);
                                    String processedContent = TemplateProcessor.applyTokens(injectionContent, params.getTokenMap());
                                    List<String> pathParts = getRelativeConfigPath(configTemplatesDir, templatePath);
                                    injectIntoDOM(doc, pathParts, processedContent);
                                } catch (Exception e) {
                                    throw new RuntimeException("Failed to inject config block from: " + templatePath, e);
                                }
                            });
                }
            }
            finally {
                params.getTokenMap().remove(TokenUtils.toToken("ServicePartition"));
                params.getTokenMap().remove(TokenUtils.toToken("ServiceInstance"));
            }

            // write back to file with formatting preserved
            XmlDomUtils.saveXmlDocument(doc, configPath);
        }
    }

    private static List<String> getRelativeConfigPath(Path base, Path file) {
        Path relative = base.relativize(file.getParent());
        List<String> parts = new ArrayList<>();
        for (Path p : relative) {
            parts.add(p.toString());
        }
        return parts;
    }

    /**
     * Navigate (creating as needed) to the target path inside the config
     * document, parse the fragment XML, and append it under the parent
     * element unless an equivalent child already exists. Profile segments
     * (children of a {@code <profiles>} element) are matched by
     * {@code name=} attribute via {@link #getOrCreateProfile}.
     */
    private static void injectIntoDOM(Document doc, List<String> pathParts, String blockXml) throws Exception {
        Element root = doc.getDocumentElement();
        Element parent = root;

        for (String part : pathParts) {
            if (part.equals("profiles")) {
                parent = XmlDomUtils.getOrCreateChild(parent, "profiles");
            } else if (parent.getNodeName().equals("profiles")) {
                parent = getOrCreateProfile(parent, part);
            } else {
                parent = XmlDomUtils.getOrCreateChild(parent, part);
            }
        }

        // Parse the fragment as its own document and take the root element.
        Document fragmentDoc = XmlDomUtils.parseXmlString(blockXml);
        Node newNode = fragmentDoc.getDocumentElement();

        // Dedup against existing direct children.
        NodeList children = parent.getChildNodes();
        for (int i = 0; i < children.getLength(); i++) {
            Node child = children.item(i);
            if (child.getNodeType() == Node.ELEMENT_NODE
                    && XmlDomUtils.nodesAreEquivalent((Element) child, (Element) newNode)) {
                return; // duplicate; skip injection
            }
        }

        Node importedNode = doc.importNode(newNode, true);
        parent.appendChild(importedNode);
    }

    /**
     * X-DDL {@code <profile>} elements are named: siblings under
     * {@code <profiles>} are matched by their {@code name} attribute rather
     * than by tag alone. Kept in {@code ConfigInjector} (rather than
     * {@link XmlDomUtils}) because this matching convention is specific to
     * X-DDL config structure.
     */
    private static Element getOrCreateProfile(Element profiles, String profileName) {
        NodeList children = profiles.getElementsByTagName("profile");
        for (int i = 0; i < children.getLength(); i++) {
            Element elem = (Element) children.item(i);
            if (profileName.equals(elem.getAttribute("name"))) {
                return elem;
            }
        }
        Element profile = profiles.getOwnerDocument().createElement("profile");
        profile.setAttribute("name", profileName);
        profiles.appendChild(profile);
        return profile;
    }
}
