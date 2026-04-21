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

import javax.xml.parsers.DocumentBuilder;
import javax.xml.parsers.DocumentBuilderFactory;
import javax.xml.transform.OutputKeys;
import javax.xml.transform.Transformer;
import javax.xml.transform.TransformerFactory;
import javax.xml.transform.dom.DOMSource;
import javax.xml.transform.stream.StreamResult;
import java.io.ByteArrayInputStream;
import java.io.FileOutputStream;
import java.io.OutputStreamWriter;
import java.nio.charset.StandardCharsets;
import java.nio.file.Path;
import java.util.List;

/**
 * Public DOM utilities used across the App Builder SDK. Factored out of
 * {@link ConfigInjector}'s original private helpers so introspectors and
 * editors added in later phases can reuse the same battle-tested logic.
 *
 * <p>Design notes:
 *
 * <ul>
 *   <li>{@link #getOrCreateChild(Element, String)} has a small X-DDL-aware
 *       quirk: when creating a {@code <templates>} element, it inserts the
 *       new element before any existing non-template element sibling. This
 *       preserves the X-DDL rule that {@code <templates>} precedes instance
 *       declarations. Non-{@code templates} tags get simple appendChild
 *       semantics. See the Files.walk ordering bug write-up in
 *       {@code nvx-rumi-appbuilder/PROJECT.md} for the full story.
 *   <li>{@link #nodesAreEquivalent(Element, Element)} is a structural equality
 *       check — tag, attributes, and children (recursive) must match. Used
 *       by config-injection dedup.
 *   <li>{@link #removeEmptyTextNodes(Node)} strips whitespace-only text
 *       nodes before serialization so pretty-printing produces clean output.
 * </ul>
 */
public final class XmlDomUtils {
    private XmlDomUtils() {}

    /** Parse an XML file into a namespace-aware DOM Document. */
    public static Document parseXmlDocument(Path filePath) throws Exception {
        DocumentBuilderFactory factory = DocumentBuilderFactory.newInstance();
        factory.setNamespaceAware(true);
        DocumentBuilder builder = factory.newDocumentBuilder();
        return builder.parse(filePath.toFile());
    }

    /** Parse an XML string into a namespace-aware DOM Document. */
    public static Document parseXmlString(String xml) throws Exception {
        DocumentBuilderFactory factory = DocumentBuilderFactory.newInstance();
        factory.setNamespaceAware(true);
        DocumentBuilder builder = factory.newDocumentBuilder();
        return builder.parse(new ByteArrayInputStream(xml.getBytes(StandardCharsets.UTF_8)));
    }

    /**
     * Write a DOM Document to disk. Pretty-printed with 4-space indent,
     * UTF-8, XML declaration preserved, standalone=yes. Strips
     * whitespace-only text nodes first so pretty-printing is idempotent.
     */
    public static void saveXmlDocument(Document doc, Path filePath) throws Exception {
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
        try (FileOutputStream fos = new FileOutputStream(filePath.toFile());
             OutputStreamWriter writer = new OutputStreamWriter(fos, StandardCharsets.UTF_8)) {
            StreamResult result = new StreamResult(writer);
            transformer.transform(source, result);
        }
    }

    /**
     * Recursively remove whitespace-only text nodes from {@code node}'s
     * subtree. Called automatically by {@link #saveXmlDocument} before
     * serialization.
     */
    public static void removeEmptyTextNodes(Node node) {
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

    /**
     * Return the first direct child element of {@code parent} with the given
     * {@code tag}. If none exists, create one and return it. When creating a
     * {@code <templates>} element, it is inserted before any existing
     * non-template element sibling (X-DDL ordering rule). Other tags get
     * appendChild semantics.
     */
    public static Element getOrCreateChild(Element parent, String tag) {
        NodeList children = parent.getElementsByTagName(tag);
        for (int i = 0; i < children.getLength(); i++) {
            Node node = children.item(i);
            if (node.getParentNode() == parent) {
                return (Element) node;
            }
        }
        Element child = parent.getOwnerDocument().createElement(tag);
        if (tag.equals("templates")) {
            Node refNode = null;
            NodeList siblings = parent.getChildNodes();
            for (int j = 0; j < siblings.getLength(); j++) {
                Node sibling = siblings.item(j);
                if (sibling.getNodeType() == Node.ELEMENT_NODE && !sibling.getNodeName().equals("templates")) {
                    refNode = sibling;
                    break;
                }
            }
            if (refNode != null) {
                parent.insertBefore(child, refNode);
            } else {
                parent.appendChild(child);
            }
        } else {
            parent.appendChild(child);
        }
        return child;
    }

    /**
     * Walk {@code root}'s element hierarchy by tag names, returning the
     * innermost element or {@code null} if any path segment doesn't exist.
     * Each path segment matches the first direct child with that tag.
     */
    public static Element getElementByPath(Element root, List<String> pathParts) {
        Element current = root;
        for (String tag : pathParts) {
            Element next = null;
            NodeList children = current.getChildNodes();
            for (int i = 0; i < children.getLength(); i++) {
                Node child = children.item(i);
                if (child.getNodeType() == Node.ELEMENT_NODE && child.getNodeName().equals(tag)) {
                    next = (Element) child;
                    break;
                }
            }
            if (next == null) return null;
            current = next;
        }
        return current;
    }

    /**
     * Structural equality of two elements: same tag name, same attributes
     * (names and values), same children (recursive). Whitespace in text
     * nodes is trimmed before comparison so pretty-printing differences
     * don't cause false negatives.
     */
    public static boolean nodesAreEquivalent(Element a, Element b) {
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

    /**
     * Remove {@code element} from its parent. No-op if the element has no
     * parent (already detached or a document root).
     */
    public static void removeElement(Element element) {
        Node parent = element.getParentNode();
        if (parent != null) {
            parent.removeChild(element);
        }
    }
}
