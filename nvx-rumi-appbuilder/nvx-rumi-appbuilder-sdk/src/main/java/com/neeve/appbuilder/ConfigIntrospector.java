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

import com.neeve.appbuilder.model.ConfigFragment;
import org.w3c.dom.Document;
import org.w3c.dom.Element;
import org.w3c.dom.Node;
import org.w3c.dom.NodeList;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.List;
import java.util.Map;
import java.util.Objects;

/**
 * Read-only introspection over an app's {@code config.xml}. Loads the DOM
 * document and enumerates fragments in the scopes that matter for
 * service-config injection and general X-DDL inspection.
 *
 * <p>Fragments are enumerated at these scope paths:
 *
 * <ul>
 *   <li>{@code env} — env-var overrides</li>
 *   <li>{@code buses} — message bus definitions</li>
 *   <li>{@code apps/templates} — app templates (root-level)</li>
 *   <li>{@code xvms/templates} — xvm templates (root-level)</li>
 *   <li>{@code profiles/{profile-name}/env}</li>
 *   <li>{@code profiles/{profile-name}/apps/templates}</li>
 *   <li>{@code profiles/{profile-name}/xvms/templates}</li>
 * </ul>
 *
 * If a specific profile name is passed, only that profile's scopes (plus
 * the root-level scopes) are returned. Pass {@code null} to enumerate
 * everything.
 */
public final class ConfigIntrospector {
    private ConfigIntrospector() {}

    /**
     * Return the parsed config.xml document, or {@code null} if the file
     * doesn't exist.
     */
    public static Document getConfig(Path appRoot) throws IOException {
        Path configPath = resolveConfigPath(appRoot);
        if (!Files.exists(configPath)) return null;
        try {
            return XmlDomUtils.parseXmlDocument(configPath);
        } catch (Exception e) {
            throw new IOException("failed to parse " + configPath, e);
        }
    }

    /**
     * Enumerate every fragment in the app's config.xml, scoped by path.
     * Pass {@code null} for {@code profile} to include every profile;
     * pass a profile name to restrict profile-scoped results to that
     * profile (root-level scopes are always included).
     */
    public static List<ConfigFragment> listFragments(Path appRoot, String profile) throws IOException {
        Document doc = getConfig(appRoot);
        if (doc == null) return Collections.emptyList();
        return listFragments(doc, profile);
    }

    /** Overload that takes an already-parsed document. */
    public static List<ConfigFragment> listFragments(Document doc, String profile) {
        Element root = doc.getDocumentElement();
        List<ConfigFragment> out = new ArrayList<>();

        // Root-level scopes
        collectChildrenOf(root, "env", List.of("env"), out);
        collectChildrenOf(root, "buses", List.of("buses"), out);
        collectTemplateChildren(root, List.of("apps", "templates"), out);
        collectTemplateChildren(root, List.of("xvms", "templates"), out);

        // Profile-scoped
        Element profilesElem = firstChild(root, "profiles");
        if (profilesElem != null) {
            NodeList profiles = profilesElem.getChildNodes();
            for (int i = 0; i < profiles.getLength(); i++) {
                Node pn = profiles.item(i);
                if (pn.getNodeType() != Node.ELEMENT_NODE) continue;
                if (!"profile".equals(pn.getLocalName())) continue;
                Element profileElem = (Element) pn;
                String profileName = profileElem.getAttribute("name");
                if (profile != null && !profile.equals(profileName)) continue;
                List<String> base = List.of("profiles", profileName);
                collectChildrenOf(profileElem, "env", concat(base, "env"), out);
                collectTemplateChildren(profileElem, concat(base, "apps", "templates"), out);
                collectTemplateChildren(profileElem, concat(base, "xvms", "templates"), out);
            }
        }
        return out;
    }

    // --- internal -----------------------------------------------------

    private static Path resolveConfigPath(Path appRoot) throws IOException {
        ApplicationBuilder.AppParams params = AppIntrospector.loadAppParams(appRoot);
        String systemArtifactId = params.getTokenMap().get(TokenUtils.toToken("SystemArtifactId"));
        return appRoot.resolve(systemArtifactId).resolve("conf/config.xml");
    }

    private static void collectChildrenOf(Element parent, String containerLocalName,
                                          List<String> scopePath, List<ConfigFragment> out) {
        Element container = firstChild(parent, containerLocalName);
        if (container == null) return;
        // Every direct element child becomes a fragment under this scope.
        NodeList kids = container.getChildNodes();
        for (int i = 0; i < kids.getLength(); i++) {
            Node n = kids.item(i);
            if (n.getNodeType() != Node.ELEMENT_NODE) continue;
            Element e = (Element) n;
            out.add(new ConfigFragment(scopePath, e.getLocalName(), nullIfEmpty(e.getAttribute("name")), e));
        }
    }

    private static void collectTemplateChildren(Element ancestor, List<String> scopePath,
                                                List<ConfigFragment> out) {
        // scopePath is either [apps, templates] / [xvms, templates] (ancestor = root), or
        // [profiles, <name>, apps, templates] / [profiles, <name>, xvms, templates] (ancestor = profile elem).
        // Either way, we navigate the last two segments (apps|xvms, templates) inside ancestor.
        Objects.requireNonNull(scopePath);
        if (scopePath.size() < 2 || !"templates".equals(scopePath.get(scopePath.size() - 1))) {
            throw new IllegalArgumentException("scopePath must end in 'templates': " + scopePath);
        }
        Element parent = firstChild(ancestor, scopePath.get(scopePath.size() - 2));
        if (parent == null) return;
        Element templates = firstChild(parent, "templates");
        if (templates == null) return;
        NodeList kids = templates.getChildNodes();
        for (int i = 0; i < kids.getLength(); i++) {
            Node n = kids.item(i);
            if (n.getNodeType() != Node.ELEMENT_NODE) continue;
            Element e = (Element) n;
            out.add(new ConfigFragment(scopePath, e.getLocalName(), nullIfEmpty(e.getAttribute("name")), e));
        }
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

    private static String nullIfEmpty(String s) {
        return (s == null || s.isEmpty()) ? null : s;
    }

    private static List<String> concat(List<String> base, String... more) {
        List<String> out = new ArrayList<>(base.size() + more.length);
        out.addAll(base);
        out.addAll(Arrays.asList(more));
        return Collections.unmodifiableList(out);
    }
}
