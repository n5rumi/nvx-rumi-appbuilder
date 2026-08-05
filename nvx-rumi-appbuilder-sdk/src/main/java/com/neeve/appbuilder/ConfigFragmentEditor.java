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
import com.neeve.appbuilder.model.ElementSelector;
import org.w3c.dom.Document;
import org.w3c.dom.Element;
import org.w3c.dom.Node;
import org.w3c.dom.NodeList;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Objects;

/**
 * Inject and remove X-DDL fragments in an app's config.xml at a target
 * scope path. Complements the existing {@link ConfigInjector} (which
 * handles service-specific injection from templates) with a
 * public-surface primitive usable by callers that want to manage
 * fragments directly — config-level env vars, custom profiles, bus
 * overrides, anything outside the service-template path.
 *
 * <p>Dedup on add is equivalent to {@link ConfigInjector}'s: if an
 * existing direct-child of the target parent is structurally equivalent
 * (same tag, same attributes, same children — see
 * {@link XmlDomUtils#nodesAreEquivalent}), the add is a noop.
 *
 * <p>Remove takes an {@link ElementSelector} which matches by tag name
 * and/or attribute values. If nothing matches the selector, remove is a
 * noop. If multiple direct children match, all are removed — use a more
 * specific selector to target one.
 *
 * <p>Target path convention mirrors the directory layout in the service
 * config templates: each segment is a tag name, except that segments
 * under a {@code "profiles"} segment are matched by {@code name}
 * attribute value (the X-DDL convention). Example paths:
 *
 * <ul>
 *   <li>{@code ["env"]} — the root {@code <env>} element
 *   <li>{@code ["apps", "templates"]} — app templates at root</li>
 *   <li>{@code ["profiles", "cloud", "apps", "templates"]} — app
 *       templates under the cloud profile</li>
 * </ul>
 */
public final class ConfigFragmentEditor {
    private ConfigFragmentEditor() {}

    /**
     * Inject an X-DDL fragment at {@code targetPath}, creating scope
     * containers as needed. Noop if an equivalent direct-child already
     * exists at the target parent.
     */
    public static ChangeSet addFragment(Path appRoot,
                                        List<String> targetPath,
                                        String xmlFragment,
                                        boolean dryRun) throws IOException {
        Objects.requireNonNull(targetPath, "targetPath");
        Objects.requireNonNull(xmlFragment, "xmlFragment");

        Path configPath = resolveConfigPath(appRoot);
        if (!Files.exists(configPath)) {
            throw new IOException("config.xml not found at " + configPath);
        }
        Document doc;
        Document fragmentDoc;
        try {
            doc = XmlDomUtils.parseXmlDocument(configPath);
            // Parse the fragment inside an X-DDL-namespaced wrapper so the
            // fragment subtree inherits the x-ddl default namespace. Parsed
            // bare, it lands under the x-ddl document carrying xmlns="", which
            // the production launcher tolerates but a validating parser — and
            // EmbeddedXVM — rejects. ConfigInjector has done this since that
            // bug was found; this editor was added later and never got it.
            fragmentDoc = XmlDomUtils.parseXmlString(
                "<nv-x-ddl-fragment xmlns=\"" + ConfigInjector.XDDL_NAMESPACE + "\">"
                + xmlFragment + "</nv-x-ddl-fragment>");
        } catch (Exception e) {
            throw new IOException("failed to parse config or fragment", e);
        }

        Element parent = navigateOrCreate(doc.getDocumentElement(), targetPath);
        Element newNode = firstChildElement(fragmentDoc.getDocumentElement());
        if (newNode == null) {
            throw new IOException("config fragment contained no element: " + xmlFragment);
        }

        NodeList existing = parent.getChildNodes();
        for (int i = 0; i < existing.getLength(); i++) {
            Node child = existing.item(i);
            if (child.getNodeType() == Node.ELEMENT_NODE
                    && XmlDomUtils.nodesAreEquivalent((Element) child, newNode)) {
                return ChangeSet.noop("equivalent fragment already present at "
                    + String.join("/", targetPath));
            }
        }

        Node imported = doc.importNode(newNode, true);
        parent.appendChild(imported);

        ChangeSet.Builder cs = ChangeSet.builder().addModified(configPath);
        if (dryRun) return cs.applied(false).build();
        try {
            XmlDomUtils.saveXmlDocument(doc, configPath);
        } catch (Exception e) {
            throw new IOException("failed to write " + configPath, e);
        }
        return cs.applied(true).build();
    }

    /**
     * Remove direct children of the target scope that match the
     * {@link ElementSelector}. If the target parent doesn't exist or no
     * children match, the call is a noop. If multiple children match,
     * all are removed.
     */
    public static ChangeSet removeFragment(Path appRoot,
                                           List<String> targetPath,
                                           ElementSelector selector,
                                           boolean dryRun) throws IOException {
        Objects.requireNonNull(targetPath, "targetPath");
        Objects.requireNonNull(selector, "selector");

        Path configPath = resolveConfigPath(appRoot);
        if (!Files.exists(configPath)) {
            throw new IOException("config.xml not found at " + configPath);
        }
        Document doc;
        try {
            doc = XmlDomUtils.parseXmlDocument(configPath);
        } catch (Exception e) {
            throw new IOException("failed to parse " + configPath, e);
        }

        Element parent = navigate(doc.getDocumentElement(), targetPath);
        if (parent == null) {
            return ChangeSet.noop("scope path does not exist: " + String.join("/", targetPath));
        }

        List<Element> matches = new ArrayList<>();
        NodeList kids = parent.getChildNodes();
        for (int i = 0; i < kids.getLength(); i++) {
            Node n = kids.item(i);
            if (n.getNodeType() == Node.ELEMENT_NODE && selector.matches((Element) n)) {
                matches.add((Element) n);
            }
        }
        if (matches.isEmpty()) {
            return ChangeSet.noop("no fragment matched " + selector + " at "
                + String.join("/", targetPath));
        }
        for (Element match : matches) {
            XmlDomUtils.removeElement(match);
        }

        ChangeSet.Builder cs = ChangeSet.builder().addModified(configPath);
        if (dryRun) return cs.applied(false).build();
        try {
            XmlDomUtils.saveXmlDocument(doc, configPath);
        } catch (Exception e) {
            throw new IOException("failed to write " + configPath, e);
        }
        return cs.applied(true).build();
    }

    // --- internal -----------------------------------------------------

    static Path resolveConfigPath(Path appRoot) throws IOException {
        ApplicationBuilder.AppParams params = AppIntrospector.loadAppParams(appRoot);
        String systemArtifactId = params.getTokenMap().get(TokenUtils.toToken("SystemArtifactId"));
        return appRoot.resolve(systemArtifactId).resolve("conf/config.xml");
    }

    /**
     * Walk the target path from {@code root}, creating elements as
     * needed. Profile segments (the second segment when the first is
     * {@code "profiles"}) are matched by {@code name} attribute per
     * X-DDL convention.
     */
    static Element navigateOrCreate(Element root, List<String> path) {
        Element current = root;
        for (int i = 0; i < path.size(); i++) {
            String segment = path.get(i);
            if ("profiles".equals(segment)) {
                current = XmlDomUtils.getOrCreateChild(current, "profiles");
            } else if (i > 0 && "profiles".equals(path.get(i - 1))) {
                current = getOrCreateProfile(current, segment);
            } else {
                current = XmlDomUtils.getOrCreateChild(current, segment);
            }
        }
        return current;
    }

    /**
     * Walk the target path from {@code root}. Returns null at the first
     * missing segment. Profile segments match by {@code name}.
     */
    static Element navigate(Element root, List<String> path) {
        Element current = root;
        for (int i = 0; i < path.size(); i++) {
            String segment = path.get(i);
            Element next;
            if ("profiles".equals(segment)) {
                next = firstChild(current, "profiles");
            } else if (i > 0 && "profiles".equals(path.get(i - 1))) {
                next = findProfile(current, segment);
            } else {
                next = firstChild(current, segment);
            }
            if (next == null) return null;
            current = next;
        }
        return current;
    }

    private static Element getOrCreateProfile(Element profiles, String name) {
        Element existing = findProfile(profiles, name);
        if (existing != null) return existing;
        Element profile = profiles.getOwnerDocument().createElement("profile");
        profile.setAttribute("name", name);
        profiles.appendChild(profile);
        return profile;
    }

    private static Element findProfile(Element profiles, String name) {
        NodeList kids = profiles.getChildNodes();
        for (int i = 0; i < kids.getLength(); i++) {
            Node n = kids.item(i);
            if (n.getNodeType() == Node.ELEMENT_NODE
                    && "profile".equals(n.getLocalName())
                    && name.equals(((Element) n).getAttribute("name"))) {
                return (Element) n;
            }
        }
        return null;
    }

    private static Element firstChild(Element parent, String localName) {
        NodeList kids = parent.getChildNodes();
        for (int i = 0; i < kids.getLength(); i++) {
            Node n = kids.item(i);
            if (n.getNodeType() == Node.ELEMENT_NODE && localName.equals(n.getLocalName())) {
                return (Element) n;
            }
        }
        return null;
    }

    /** First element child of {@code parent}, or null if it has none. */
    private static Element firstChildElement(Element parent) {
        NodeList children = parent.getChildNodes();
        for (int i = 0; i < children.getLength(); i++) {
            Node n = children.item(i);
            if (n.getNodeType() == Node.ELEMENT_NODE) {
                return (Element) n;
            }
        }
        return null;
    }

}
