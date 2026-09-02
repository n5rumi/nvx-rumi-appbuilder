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
import com.neeve.appbuilder.model.ElementSelector;
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

    /**
     * Enumerate fragments, narrowed to one scope path and/or one selector
     * (RUMI-413).
     *
     * <p>The write side has taken an {@link ElementSelector} since it existed;
     * the read side could only return everything, so answering "what is in this
     * one xvm's env block?" meant fetching the whole config and searching it by
     * hand. Reading a whole generated file to answer a one-line question was the
     * single most expensive tool call in the session that prompted this.
     *
     * <p>⚠️ The read and the write do <b>not</b> cover the same paths, and the
     * gap is dangerous in one direction. {@code removeFragment} navigates an
     * arbitrary path and deletes matching direct children, so
     * {@code scopePath=["xvms"], tag="templates"} deletes the whole
     * {@code <templates>} subtree — while this read only knows the seven
     * enumerated scope shapes and would answer "nothing there". A caller that
     * checked before deleting would be told it was safe. So a scope path this
     * read cannot enumerate is <b>rejected</b> rather than answered with an
     * empty list.
     *
     * <p>When {@code profile} is set, a relative {@code scopePath} matches both
     * the root-level scope and that profile's, which is what
     * {@link #listFragments(Path, String)} already does when no scope path is
     * given. Pass the full {@code profiles/<name>/...} path to restrict to the
     * profile alone.
     *
     * @param scopePath exact scope path to restrict to, or {@code null} for any.
     * @param selector  match on tag name and/or attributes, or {@code null} for
     *        any.
     * @throws IllegalArgumentException if {@code scopePath} is not a scope this
     *         introspector enumerates.
     */
    public static List<ConfigFragment> listFragments(Path appRoot, String profile,
                                                     List<String> scopePath,
                                                     ElementSelector selector) throws IOException {
        return filter(listFragments(appRoot, profile), profile, scopePath, selector);
    }

    /** Overload that takes an already-parsed document. */
    public static List<ConfigFragment> listFragments(Document doc, String profile,
                                                     List<String> scopePath,
                                                     ElementSelector selector) {
        return filter(listFragments(doc, profile), profile, scopePath, selector);
    }

    /** Root-level scope shapes this introspector enumerates. */
    private static final List<List<String>> ROOT_SCOPES = List.of(
        List.of("env"),
        List.of("buses"),
        List.of("apps", "templates"),
        List.of("xvms", "templates"));

    /**
     * Scope shapes enumerated <em>inside</em> a profile — deliberately narrower
     * than {@link #ROOT_SCOPES}: {@code buses} is collected at the root only, so
     * {@code profiles/&lt;name&gt;/buses} is a path this read cannot answer even
     * though {@code buses} on its own is fine.
     */
    private static final List<List<String>> PROFILE_SCOPES = List.of(
        List.of("env"),
        List.of("apps", "templates"),
        List.of("xvms", "templates"));

    private static List<ConfigFragment> filter(List<ConfigFragment> all,
                                               String profile,
                                               List<String> scopePath,
                                               ElementSelector selector) {
        if (scopePath != null) assertEnumerableScope(scopePath);
        if (scopePath == null && selector == null) return all;

        // With a profile set, a relative scope also matches that profile's copy
        // of it — otherwise ?profile=dev&scope_path=xvms/templates would return
        // the ROOT templates and silently exclude every one of dev's, which is a
        // plausible-looking wrong answer rather than an error.
        List<String> profileScoped = null;
        if (profile != null && scopePath != null && !isProfilePath(scopePath)) {
            List<String> p = new ArrayList<>(List.of("profiles", profile));
            p.addAll(scopePath);
            profileScoped = p;
        }

        List<ConfigFragment> out = new ArrayList<>();
        for (ConfigFragment f : all) {
            if (scopePath != null
                && !scopePath.equals(f.getScopePath())
                && !(profileScoped != null && profileScoped.equals(f.getScopePath()))) {
                continue;
            }
            if (selector != null && !selector.matches(f.getElement())) continue;
            out.add(f);
        }
        return out;
    }

    private static boolean isProfilePath(List<String> scopePath) {
        return scopePath.size() > 1 && "profiles".equals(scopePath.get(0));
    }

    /**
     * Refuse a scope path this read cannot enumerate.
     *
     * <p>Returning an empty list would be the dangerous answer: {@code remove}
     * navigates arbitrary paths, so "the read found nothing" must never be
     * mistaken for "there is nothing to remove".
     */
    private static void assertEnumerableScope(List<String> scopePath) {
        boolean inProfile = isProfilePath(scopePath);
        List<String> relative = inProfile
            ? scopePath.subList(2, scopePath.size())   // drop profiles/<name>
            : scopePath;
        if ((inProfile ? PROFILE_SCOPES : ROOT_SCOPES).contains(relative)) return;
        throw new IllegalArgumentException(
            "cannot enumerate the scope path " + String.join("/", scopePath)
            + "; this read covers env, buses, apps/templates and xvms/templates at the"
            + " root, and env, apps/templates and xvms/templates inside a profile."
            + " Refusing rather than reporting it empty, because remove navigates paths"
            + " this read cannot see.");
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
