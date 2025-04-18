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
import javax.xml.parsers.*;
import javax.xml.transform.*;
import javax.xml.transform.dom.DOMSource;
import javax.xml.transform.stream.StreamResult;
import java.io.*;
import java.nio.file.*;
import java.util.*;

class ConfigInjector {
    static void injectServiceConfig(Path appRoot, ServiceBuilder.ServiceParams params) throws Exception {
        // get the config file to inject into
        Path configPath = appRoot
                .resolve(params.getTokenMap().get(TokenUtils.toToken("SystemArtifactId")))
                .resolve("conf/config.xml");

        // convert to DOM model
        DocumentBuilderFactory factory = DocumentBuilderFactory.newInstance();
        factory.setNamespaceAware(true);
        DocumentBuilder builder = factory.newDocumentBuilder();
        Document doc = builder.parse(configPath.toFile());

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
            removeEmptyTextNodes(doc);
            TransformerFactory transformerFactory = TransformerFactory.newInstance();
            Transformer transformer = transformerFactory.newTransformer();
            transformer.setOutputProperty(OutputKeys.INDENT, "yes");
            transformer.setOutputProperty(OutputKeys.METHOD, "xml");
            transformer.setOutputProperty(OutputKeys.ENCODING, "UTF-8");
            transformer.setOutputProperty("{http://xml.apache.org/xslt}indent-amount", "4");
            transformer.setOutputProperty(OutputKeys.OMIT_XML_DECLARATION, "no");
            transformer.setOutputProperty(OutputKeys.STANDALONE, "yes");
            DOMSource source = new DOMSource(doc);
            StreamResult result = new StreamResult(new OutputStreamWriter(new FileOutputStream(configPath.toFile()), "UTF-8"));
            transformer.transform(source, result);
        }
    }

    private static void removeEmptyTextNodes(Node node) {
        NodeList children = node.getChildNodes();
        for (int i = children.getLength() - 1; i >= 0; i--) {
            Node child = children.item(i);
            if (child.getNodeType() == Node.TEXT_NODE && child.getTextContent().trim().isEmpty()) {
                node.removeChild(child);
            } else if (child.hasChildNodes()) {
                removeEmptyTextNodes(child);
            }
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

    private static void injectIntoDOM(Document doc, List<String> pathParts, String blockXml) throws Exception {
        Element root = doc.getDocumentElement();
        Element parent = root;

        for (String part : pathParts) {
            if (part.equals("profiles")) {
                parent = getOrCreateChild(parent, "profiles");
            } else if (parent.getNodeName().equals("profiles")) {
                parent = getOrCreateProfile(parent, part);
            } else {
                parent = getOrCreateChild(parent, part);
            }
        }

        // Parse block XML into a temporary DOM fragment
        DocumentBuilder builder = DocumentBuilderFactory.newInstance().newDocumentBuilder();
        Document fragmentDoc = builder.parse(new ByteArrayInputStream(blockXml.getBytes()));
        Node newNode = fragmentDoc.getDocumentElement();

        // Check for duplication
        NodeList children = parent.getChildNodes();
        for (int i = 0; i < children.getLength(); i++) {
            Node child = children.item(i);
            if (child.getNodeType() == Node.ELEMENT_NODE) {
                if (nodesAreEquivalent((Element) child, (Element) newNode)) {
                    return; // duplicate found; skip injection
                }
            }
        }

        // No duplicate found; inject new node
        Node importedNode = doc.importNode(newNode, true);
        parent.appendChild(importedNode);
    }

    private static boolean nodesAreEquivalent(Element a, Element b) {
        if (!a.getTagName().equals(b.getTagName())) return false;

        NamedNodeMap attrsA = a.getAttributes();
        NamedNodeMap attrsB = b.getAttributes();
        if (attrsA.getLength() != attrsB.getLength()) return false;

        for (int i = 0; i < attrsA.getLength(); i++) {
            Node attrA = attrsA.item(i);
            Node attrB = attrsB.getNamedItem(attrA.getNodeName());
            if (attrB == null || !attrA.getNodeValue().equals(attrB.getNodeValue())) {
                return false;
            }
        }

        NodeList childrenA = a.getChildNodes();
        NodeList childrenB = b.getChildNodes();
        if (childrenA.getLength() != childrenB.getLength()) return false;

        for (int i = 0; i < childrenA.getLength(); i++) {
            Node childA = childrenA.item(i);
            Node childB = childrenB.item(i);

            if (childA.getNodeType() != childB.getNodeType()) return false;
            if (childA.getNodeType() == Node.TEXT_NODE) {
                if (!childA.getTextContent().trim().equals(childB.getTextContent().trim())) {
                    return false;
                }
            } else if (childA.getNodeType() == Node.ELEMENT_NODE) {
                if (!nodesAreEquivalent((Element) childA, (Element) childB)) {
                    return false;
                }
            }
        }

        return true;
    }

    private static Element getOrCreateChild(Element parent, String tag) {
        NodeList children = parent.getElementsByTagName(tag);
        for (int i = 0; i < children.getLength(); i++) {
            Node node = children.item(i);
            if (node.getParentNode() == parent) {
                return (Element) node;
            }
        }
        Element child = parent.getOwnerDocument().createElement(tag);
        parent.appendChild(child);
        return child;
    }

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

