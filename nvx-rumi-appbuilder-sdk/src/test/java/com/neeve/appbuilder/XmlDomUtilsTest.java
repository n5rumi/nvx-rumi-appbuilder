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

import org.junit.After;
import org.junit.Before;
import org.junit.Test;
import org.w3c.dom.Document;
import org.w3c.dom.Element;
import org.w3c.dom.Node;
import org.w3c.dom.NodeList;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.Arrays;
import java.util.Collections;
import java.util.List;
import java.util.stream.Stream;

import static org.junit.Assert.*;

public class XmlDomUtilsTest {

    private Path tempDir;

    @Before
    public void setUp() throws IOException {
        tempDir = Files.createTempDirectory("xmldomutils-test-");
    }

    @After
    public void tearDown() throws IOException {
        if (tempDir != null && Files.exists(tempDir)) {
            try (Stream<Path> walk = Files.walk(tempDir)) {
                walk.sorted((a, b) -> b.getNameCount() - a.getNameCount())
                    .forEach(p -> { try { Files.deleteIfExists(p); } catch (IOException ignored) {} });
            }
        }
    }

    // ---- parseXmlDocument / parseXmlString ----------------------------

    @Test
    public void parseXmlString_returnsDocumentWithNamedRoot() throws Exception {
        Document doc = XmlDomUtils.parseXmlString("<root><child/></root>");
        assertEquals("root", doc.getDocumentElement().getTagName());
        assertEquals(1, directChildElements(doc.getDocumentElement(), "child").size());
    }

    @Test
    public void parseXmlDocument_readsFromDisk() throws Exception {
        Path file = tempDir.resolve("sample.xml");
        Files.writeString(file, "<config><env><key>value</key></env></config>");
        Document doc = XmlDomUtils.parseXmlDocument(file);
        assertEquals("config", doc.getDocumentElement().getTagName());
    }

    // ---- saveXmlDocument round-trip ------------------------------------

    @Test
    public void saveXmlDocument_roundTripPreservesStructure() throws Exception {
        String original = "<config><env><k>v</k></env><apps><templates/></apps></config>";
        Document doc = XmlDomUtils.parseXmlString(original);
        Path file = tempDir.resolve("round-trip.xml");
        XmlDomUtils.saveXmlDocument(doc, file);

        Document reread = XmlDomUtils.parseXmlDocument(file);
        Element config = reread.getDocumentElement();
        assertEquals("config", config.getTagName());
        assertEquals(1, directChildElements(config, "env").size());
        assertEquals(1, directChildElements(config, "apps").size());

        Element apps = directChildElements(config, "apps").get(0);
        assertEquals(1, directChildElements(apps, "templates").size());
    }

    @Test
    public void saveXmlDocument_writesUtf8WithDeclaration() throws Exception {
        Document doc = XmlDomUtils.parseXmlString("<root/>");
        Path file = tempDir.resolve("out.xml");
        XmlDomUtils.saveXmlDocument(doc, file);
        String written = Files.readString(file);
        assertTrue("declaration present", written.contains("<?xml"));
        assertTrue("utf-8 encoding", written.contains("UTF-8"));
    }

    // ---- removeEmptyTextNodes ------------------------------------------

    @Test
    public void removeEmptyTextNodes_stripsWhitespaceOnlyNodesRecursively() throws Exception {
        Document doc = XmlDomUtils.parseXmlString("<a>\n  <b>  </b>\n  <c>x</c>\n</a>");
        XmlDomUtils.removeEmptyTextNodes(doc);

        Element a = doc.getDocumentElement();
        // Only element children should remain at the top level — no empty text nodes between them.
        int elementCount = 0;
        int emptyTextCount = 0;
        NodeList children = a.getChildNodes();
        for (int i = 0; i < children.getLength(); i++) {
            Node n = children.item(i);
            if (n.getNodeType() == Node.ELEMENT_NODE) elementCount++;
            if (n.getNodeType() == Node.TEXT_NODE && n.getTextContent().trim().isEmpty()) emptyTextCount++;
        }
        assertEquals(2, elementCount);
        assertEquals(0, emptyTextCount);

        // Text node with non-whitespace content preserved.
        Element c = directChildElements(a, "c").get(0);
        assertEquals("x", c.getTextContent());
    }

    // ---- getOrCreateChild ----------------------------------------------

    @Test
    public void getOrCreateChild_reusesExisting() throws Exception {
        Document doc = XmlDomUtils.parseXmlString("<root><env/></root>");
        Element root = doc.getDocumentElement();
        Element env1 = XmlDomUtils.getOrCreateChild(root, "env");
        Element env2 = XmlDomUtils.getOrCreateChild(root, "env");
        assertSame("same element returned on repeated calls", env1, env2);
        assertEquals(1, directChildElements(root, "env").size());
    }

    @Test
    public void getOrCreateChild_createsWhenAbsent() throws Exception {
        Document doc = XmlDomUtils.parseXmlString("<root/>");
        Element root = doc.getDocumentElement();
        Element apps = XmlDomUtils.getOrCreateChild(root, "apps");
        assertEquals("apps", apps.getTagName());
        assertEquals(1, directChildElements(root, "apps").size());
    }

    @Test
    public void getOrCreateChild_onlyMatchesDirectChildren() throws Exception {
        // env exists as a grandchild but not as a direct child; a new direct child should be created.
        Document doc = XmlDomUtils.parseXmlString("<root><apps><env/></apps></root>");
        Element root = doc.getDocumentElement();
        Element env = XmlDomUtils.getOrCreateChild(root, "env");
        assertSame(root, env.getParentNode());
        assertEquals(2, directChildElements(root, "apps").size() + directChildElements(root, "env").size());
    }

    @Test
    public void getOrCreateChild_templatesInsertedBeforeNonTemplatesSibling() throws Exception {
        // A non-templates sibling was inserted first (Files.walk non-determinism). Creating <templates>
        // should land it before the existing <app> sibling so X-DDL ordering is preserved.
        Document doc = XmlDomUtils.parseXmlString("<apps><app name=\"x\"/></apps>");
        Element apps = doc.getDocumentElement();

        Element templates = XmlDomUtils.getOrCreateChild(apps, "templates");

        NodeList kids = apps.getChildNodes();
        assertEquals(2, elementCount(kids));
        // First element child should be templates, second should be app
        Element first = firstElementChild(apps);
        assertNotNull(first);
        assertEquals("templates", first.getTagName());
        assertSame(templates, first);
    }

    @Test
    public void getOrCreateChild_templatesAppendedWhenNoSiblings() throws Exception {
        Document doc = XmlDomUtils.parseXmlString("<apps/>");
        Element apps = doc.getDocumentElement();
        Element templates = XmlDomUtils.getOrCreateChild(apps, "templates");
        assertSame(apps, templates.getParentNode());
        assertEquals(1, elementCount(apps.getChildNodes()));
    }

    // ---- getElementByPath ----------------------------------------------

    @Test
    public void getElementByPath_walksNestedHierarchy() throws Exception {
        Document doc = XmlDomUtils.parseXmlString(
            "<root><profiles><profile name=\"cloud\"><env><k>v</k></env></profile></profiles></root>");
        Element target = XmlDomUtils.getElementByPath(
            doc.getDocumentElement(), Arrays.asList("profiles", "profile", "env", "k"));
        assertNotNull(target);
        assertEquals("k", target.getTagName());
        assertEquals("v", target.getTextContent());
    }

    @Test
    public void getElementByPath_returnsNullWhenSegmentMissing() throws Exception {
        Document doc = XmlDomUtils.parseXmlString("<root><a><b/></a></root>");
        assertNull(XmlDomUtils.getElementByPath(doc.getDocumentElement(), Arrays.asList("a", "c")));
        assertNull(XmlDomUtils.getElementByPath(doc.getDocumentElement(), Arrays.asList("x")));
    }

    @Test
    public void getElementByPath_emptyPathReturnsRoot() throws Exception {
        Document doc = XmlDomUtils.parseXmlString("<root/>");
        Element root = doc.getDocumentElement();
        assertSame(root, XmlDomUtils.getElementByPath(root, Collections.emptyList()));
    }

    // ---- nodesAreEquivalent --------------------------------------------

    @Test
    public void nodesAreEquivalent_trueForIdenticalSubtrees() throws Exception {
        Document a = XmlDomUtils.parseXmlString("<app name=\"x\"><storage enabled=\"true\"/></app>");
        Document b = XmlDomUtils.parseXmlString("<app name=\"x\"><storage enabled=\"true\"/></app>");
        assertTrue(XmlDomUtils.nodesAreEquivalent(a.getDocumentElement(), b.getDocumentElement()));
    }

    @Test
    public void nodesAreEquivalent_falseForDifferentTag() throws Exception {
        Document a = XmlDomUtils.parseXmlString("<app/>");
        Document b = XmlDomUtils.parseXmlString("<xvm/>");
        assertFalse(XmlDomUtils.nodesAreEquivalent(a.getDocumentElement(), b.getDocumentElement()));
    }

    @Test
    public void nodesAreEquivalent_falseForDifferentAttributeValue() throws Exception {
        Document a = XmlDomUtils.parseXmlString("<app name=\"x\"/>");
        Document b = XmlDomUtils.parseXmlString("<app name=\"y\"/>");
        assertFalse(XmlDomUtils.nodesAreEquivalent(a.getDocumentElement(), b.getDocumentElement()));
    }

    @Test
    public void nodesAreEquivalent_trueAcrossWhitespaceInTextNodes() throws Exception {
        Document a = XmlDomUtils.parseXmlString("<p>hello</p>");
        Document b = XmlDomUtils.parseXmlString("<p>  hello  </p>");
        assertTrue(XmlDomUtils.nodesAreEquivalent(a.getDocumentElement(), b.getDocumentElement()));
    }

    @Test
    public void nodesAreEquivalent_falseForDifferentChildCount() throws Exception {
        Document a = XmlDomUtils.parseXmlString("<app><storage/></app>");
        Document b = XmlDomUtils.parseXmlString("<app><storage/><messaging/></app>");
        assertFalse(XmlDomUtils.nodesAreEquivalent(a.getDocumentElement(), b.getDocumentElement()));
    }

    @Test
    public void nodesAreEquivalent_recursesIntoChildren() throws Exception {
        Document a = XmlDomUtils.parseXmlString(
            "<app><storage><persistence enabled=\"yes\"/></storage></app>");
        Document b = XmlDomUtils.parseXmlString(
            "<app><storage><persistence enabled=\"no\"/></storage></app>");
        assertFalse(XmlDomUtils.nodesAreEquivalent(a.getDocumentElement(), b.getDocumentElement()));
    }

    // ---- removeElement -------------------------------------------------

    @Test
    public void removeElement_detachesFromParent() throws Exception {
        Document doc = XmlDomUtils.parseXmlString("<root><a/><b/></root>");
        Element root = doc.getDocumentElement();
        Element a = directChildElements(root, "a").get(0);
        XmlDomUtils.removeElement(a);

        assertNull(a.getParentNode());
        assertEquals(0, directChildElements(root, "a").size());
        assertEquals(1, directChildElements(root, "b").size());
    }

    @Test
    public void removeElement_noopWhenAlreadyDetached() throws Exception {
        Document doc = XmlDomUtils.parseXmlString("<root><a/></root>");
        Element a = directChildElements(doc.getDocumentElement(), "a").get(0);
        XmlDomUtils.removeElement(a);
        // Calling again on a detached element should be a no-op — not throw.
        XmlDomUtils.removeElement(a);
    }

    // ---- helpers -------------------------------------------------------

    private static List<Element> directChildElements(Element parent, String tag) {
        NodeList nl = parent.getChildNodes();
        java.util.List<Element> out = new java.util.ArrayList<>();
        for (int i = 0; i < nl.getLength(); i++) {
            Node n = nl.item(i);
            if (n.getNodeType() == Node.ELEMENT_NODE && n.getNodeName().equals(tag)) {
                out.add((Element) n);
            }
        }
        return out;
    }

    private static int elementCount(NodeList nl) {
        int n = 0;
        for (int i = 0; i < nl.getLength(); i++) {
            if (nl.item(i).getNodeType() == Node.ELEMENT_NODE) n++;
        }
        return n;
    }

    private static Element firstElementChild(Element parent) {
        NodeList nl = parent.getChildNodes();
        for (int i = 0; i < nl.getLength(); i++) {
            Node n = nl.item(i);
            if (n.getNodeType() == Node.ELEMENT_NODE) return (Element) n;
        }
        return null;
    }
}
