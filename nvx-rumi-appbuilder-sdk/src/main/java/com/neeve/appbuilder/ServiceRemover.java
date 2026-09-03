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
import org.w3c.dom.Document;
import org.w3c.dom.Element;
import org.w3c.dom.Node;
import org.w3c.dom.NodeList;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Comparator;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.stream.Stream;

/**
 * Orchestrates the inverse of
 * {@link ServiceBuilder#createService(ServiceBuilder.ServiceParams)}.
 * Removes a service completely from a Rumi app:
 *
 * <ol>
 *   <li>Snapshot the factory IDs the service owns (so the ChangeSet can
 *       report which ids the removed service held).
 *   <li>Delete the service's module directory (state.xml, messages.xml,
 *       pom.xml, Main.java, templates, any custom files under the
 *       module). The factory IDs are <em>not</em> returned to the pool —
 *       {@link FactoryIdCollector} retains them in its app-global ledger so
 *       they are never reused (a recycled factory id is a wire-compat hazard).
 *   <li>Remove the module entry from the parent POM.
 *   <li>Remove the service dependency block from the system module POM.
 *   <li>Remove config fragments injected for this service from config.xml,
 *       including under each profile: both the app/xvm <em>templates</em>
 *       named after the service, and the app/xvm <em>instances</em> that
 *       reference those templates. Leaving the instances behind is what
 *       RUMI-422 was: the removal reported success, the file stayed valid
 *       XML, and the app failed at first boot with
 *       {@code Template '...' does not exist}, naming the template rather
 *       than the removal that orphaned it.
 *   <li>Remove script fragments injected for this service from the
 *       deployment scripts.
 * </ol>
 *
 * <p>Step (5) and (6) match against the templates that
 * {@link ConfigInjector} and {@link ScriptInjector} use — we re-apply
 * the same tokens and identify fragments by the resulting content.
 *
 * <p>Supports {@code dryRun}: when true, every change is computed and
 * the {@link ChangeSet} lists what would happen, but nothing is written
 * to disk and no files are deleted.
 */
public final class ServiceRemover {
    private ServiceRemover() {}

    /**
     * Remove the named service from the app. Returns a ChangeSet
     * summarising every file touched and every factory ID released.
     * Noop if no service by that name is declared in the parent POM.
     */
    public static ChangeSet removeService(Path appRoot, String serviceName, boolean dryRun) throws IOException {
        ApplicationBuilder.AppParams params = AppIntrospector.loadAppParams(appRoot);
        Map<String, String> tokens = params.getTokenMap();
        String parentArtifactId = tokens.get(TokenUtils.toToken("ParentArtifactId"));
        String systemArtifactId = tokens.get(TokenUtils.toToken("SystemArtifactId"));
        String kebab = TokenUtils.toKebabCase(serviceName);
        String serviceArtifactId = parentArtifactId + "-" + kebab;
        Path moduleDir = appRoot.resolve(serviceArtifactId);

        // Fast-exit if nothing to do: no module in parent POM AND no module dir.
        Path parentPom = appRoot.resolve("pom.xml");
        boolean inParentPom = Files.exists(parentPom)
            && new String(Files.readAllBytes(parentPom), StandardCharsets.UTF_8)
                .contains("<module>" + serviceArtifactId + "</module>");
        boolean moduleDirExists = Files.isDirectory(moduleDir);
        if (!inParentPom && !moduleDirExists) {
            return ChangeSet.noop("service '" + serviceName + "' is not present in " + appRoot);
        }

        // Snapshot factory IDs owned by the service before deleting its files.
        List<Integer> releasedIds = factoryIdsOwnedBy(appRoot, moduleDir);

        ChangeSet.Builder cs = ChangeSet.builder();

        // (1) Delete module directory.
        if (moduleDirExists) {
            for (Integer id : releasedIds) cs.addFactoryIdReleased(id);
            if (dryRun) {
                listFilesRecursive(moduleDir).forEach(cs::addDeleted);
            } else {
                deleteRecursive(moduleDir);
                cs.addDeleted(moduleDir);
            }
        }

        // (2) Remove module entry from parent POM.
        if (inParentPom) {
            if (dryRun) {
                cs.addModified(parentPom);
            } else {
                removeLineContainingFromPom(parentPom,
                    "<module>" + serviceArtifactId + "</module>");
                cs.addModified(parentPom);
            }
        }

        // (3) Remove service dependency from system module POM.
        Path systemPom = appRoot.resolve(systemArtifactId).resolve("pom.xml");
        if (Files.exists(systemPom)) {
            boolean systemPomHasIt = hasDependency(systemPom, serviceArtifactId);
            if (systemPomHasIt) {
                if (dryRun) {
                    cs.addModified(systemPom);
                } else {
                    removeDependencyBlock(systemPom, serviceArtifactId);
                    cs.addModified(systemPom);
                }
            }
        }

        // (4) Remove config fragments scoped by the service's identifiers.
        Path configPath = appRoot.resolve(systemArtifactId).resolve("conf/config.xml");
        if (Files.exists(configPath)) {
            boolean configTouched = removeConfigFragmentsForService(configPath, tokens, serviceName, kebab, dryRun);
            if (configTouched) cs.addModified(configPath);
        }

        // (5) Remove script snippets for this service.
        // The snippets are produced by ScriptInjector from templates at
        // templates/{build}/scripts/{service-type}/* with tokens applied.
        // We re-run the template application to compute the exact snippet
        // lines and then strip them from the live scripts.
        Path scriptsDir = appRoot.resolve(systemArtifactId).resolve("scripts");
        if (Files.isDirectory(scriptsDir)) {
            List<Path> scriptsTouched = removeScriptSnippetsForService(
                appRoot, moduleDir, scriptsDir, tokens, serviceName, kebab, dryRun);
            scriptsTouched.forEach(cs::addModified);
        }

        if (!dryRun) cs.applied(true);
        return cs.build();
    }

    // --- internal -----------------------------------------------------

    /** Scan factory IDs referenced under the service's module directory. */
    private static List<Integer> factoryIdsOwnedBy(Path appRoot, Path moduleDir) throws IOException {
        if (!Files.isDirectory(moduleDir)) return List.of();
        Map<Integer, String> used = FactoryIdCollector.listUsedIds(appRoot);
        String modulePrefix = appRoot.relativize(moduleDir).toString();
        List<Integer> out = new ArrayList<>();
        for (Map.Entry<Integer, String> e : used.entrySet()) {
            // Owner descriptions start with the relative XML path.
            if (e.getValue().startsWith(modulePrefix)) out.add(e.getKey());
        }
        return out;
    }

    private static List<Path> listFilesRecursive(Path root) throws IOException {
        try (Stream<Path> walk = Files.walk(root)) {
            List<Path> out = new ArrayList<>();
            walk.filter(Files::isRegularFile).forEach(out::add);
            return out;
        }
    }

    private static void deleteRecursive(Path root) throws IOException {
        try (Stream<Path> walk = Files.walk(root)) {
            walk.sorted(Comparator.reverseOrder()).forEach(p -> {
                try { Files.deleteIfExists(p); } catch (IOException ignored) {}
            });
        }
    }

    private static void removeLineContainingFromPom(Path pom, String needle) throws IOException {
        List<String> lines = Files.readAllLines(pom);
        List<String> kept = new ArrayList<>(lines.size());
        for (String line : lines) {
            if (line.contains(needle)) continue;
            kept.add(line);
        }
        Files.write(pom, kept);
    }

    private static boolean hasDependency(Path pom, String artifactId) throws IOException {
        String text = new String(Files.readAllBytes(pom), StandardCharsets.UTF_8);
        return text.contains("<artifactId>" + artifactId + "</artifactId>");
    }

    /**
     * Remove a {@code <dependency>} block whose artifactId matches. The
     * block format matches what {@link ServiceBuilder#updateSystemModulePom}
     * writes: four lines — {@code <dependency>}, groupId, artifactId,
     * version, {@code </dependency>}.
     */
    private static void removeDependencyBlock(Path pom, String artifactId) throws IOException {
        List<String> lines = Files.readAllLines(pom);
        String marker = "<artifactId>" + artifactId + "</artifactId>";
        // Find the artifactId line; walk backward to the opening <dependency>
        // and forward to the closing </dependency>, then strip that range.
        int artifactLine = -1;
        for (int i = 0; i < lines.size(); i++) {
            if (lines.get(i).contains(marker)) { artifactLine = i; break; }
        }
        if (artifactLine < 0) return;
        int openIdx = artifactLine;
        while (openIdx >= 0 && !lines.get(openIdx).contains("<dependency>")) openIdx--;
        int closeIdx = artifactLine;
        while (closeIdx < lines.size() && !lines.get(closeIdx).contains("</dependency>")) closeIdx++;
        if (openIdx < 0 || closeIdx >= lines.size()) return;

        List<String> kept = new ArrayList<>(lines.size());
        for (int i = 0; i < lines.size(); i++) {
            if (i >= openIdx && i <= closeIdx) continue;
            kept.add(lines.get(i));
        }
        Files.write(pom, kept);
    }

    /**
     * Walk config.xml and remove every fragment whose {@code name} attribute
     * references the service's ServiceName token (app and xvm templates
     * created by the scaffolder are named {@code {ServiceName}-*}).
     *
     * @return true if at least one fragment was removed (or would be, on dry run).
     */
    private static boolean removeConfigFragmentsForService(Path configPath,
                                                           Map<String, String> tokens,
                                                           String serviceName,
                                                           String serviceKebab,
                                                           boolean dryRun) throws IOException {
        Document doc;
        try {
            doc = XmlDomUtils.parseXmlDocument(configPath);
        } catch (Exception e) {
            throw new IOException("failed to parse " + configPath, e);
        }
        // Service name in config fragments is {AppTokenName}-{ServiceKebab}, per ServiceBuilder.toTokenMap.
        String appToken = tokens.get(TokenUtils.toToken("AppTokenName"));
        String fullServiceName = appToken + "-" + serviceKebab;
        String namePrefix = fullServiceName + "-";  // e.g. "trading-order-processor-"

        List<Element> toRemove = new ArrayList<>();
        collectFragmentsToRemove(doc.getDocumentElement(), namePrefix, fullServiceName, toRemove);

        // The templates are only half of it. Every app/xvm INSTANCE that points
        // at one of them has to go too, or the app stops booting -- see the
        // class javadoc. Collect the template names first, then sweep the
        // instances that reference them.
        Set<String> removedTemplateNames = new HashSet<>();
        for (Element e : toRemove) {
            String n = e.getAttribute("name");
            if (n != null && !n.isEmpty()) removedTemplateNames.add(n);
        }
        collectInstancesToRemove(doc.getDocumentElement(), namePrefix, fullServiceName,
                                 removedTemplateNames, toRemove);

        if (toRemove.isEmpty()) return false;
        if (dryRun) return true;

        for (Element e : toRemove) XmlDomUtils.removeElement(e);
        try {
            XmlDomUtils.saveXmlDocument(doc, configPath);
        } catch (Exception e) {
            throw new IOException("failed to write " + configPath, e);
        }
        return true;
    }

    /** Recurse through all apps/templates, xvms/templates (root and profile) looking for service-named fragments. */
    private static void collectFragmentsToRemove(Element root, String namePrefix, String exactName, List<Element> out) {
        // Root scopes.
        collectFragmentsUnder(root, Arrays.asList("apps", "templates"), namePrefix, exactName, out);
        collectFragmentsUnder(root, Arrays.asList("xvms", "templates"), namePrefix, exactName, out);
        // Profile scopes.
        Element profiles = firstChild(root, "profiles");
        if (profiles != null) {
            NodeList kids = profiles.getChildNodes();
            for (int i = 0; i < kids.getLength(); i++) {
                Node n = kids.item(i);
                if (n.getNodeType() != Node.ELEMENT_NODE || !"profile".equals(n.getLocalName())) continue;
                collectFragmentsUnder((Element) n, Arrays.asList("apps", "templates"), namePrefix, exactName, out);
                collectFragmentsUnder((Element) n, Arrays.asList("xvms", "templates"), namePrefix, exactName, out);
            }
        }
    }

    /**
     * Collect the app/xvm <em>instances</em> belonging to this service, at the
     * root and under every profile.
     *
     * <p>Matched two ways, deliberately. An instance is taken if its
     * {@code template} attribute names one of the templates being removed —
     * which is the relationship that actually breaks — or if its own name
     * follows the scaffolder's {@code {service}-N} convention. The first catches
     * an instance somebody renamed; the second catches one whose template
     * attribute was already dangling before this removal.
     */
    private static void collectInstancesToRemove(Element root, String namePrefix, String exactName,
                                                 Set<String> removedTemplateNames, List<Element> out) {
        collectInstancesUnder(root, "apps", namePrefix, exactName, removedTemplateNames, out);
        collectInstancesUnder(root, "xvms", namePrefix, exactName, removedTemplateNames, out);
        Element profiles = firstChild(root, "profiles");
        if (profiles != null) {
            NodeList kids = profiles.getChildNodes();
            for (int i = 0; i < kids.getLength(); i++) {
                Node n = kids.item(i);
                if (n.getNodeType() != Node.ELEMENT_NODE || !"profile".equals(n.getLocalName())) continue;
                collectInstancesUnder((Element) n, "apps", namePrefix, exactName, removedTemplateNames, out);
                collectInstancesUnder((Element) n, "xvms", namePrefix, exactName, removedTemplateNames, out);
            }
        }
    }

    private static void collectInstancesUnder(Element ancestor, String container,
                                              String namePrefix, String exactName,
                                              Set<String> removedTemplateNames, List<Element> out) {
        Element cur = firstChild(ancestor, container);
        if (cur == null) return;
        NodeList kids = cur.getChildNodes();
        for (int i = 0; i < kids.getLength(); i++) {
            Node n = kids.item(i);
            if (n.getNodeType() != Node.ELEMENT_NODE) continue;
            Element e = (Element) n;
            // The nested <templates> block is handled by collectFragmentsUnder;
            // stepping into it here would double-remove.
            if ("templates".equals(e.getLocalName())) continue;
            String template = e.getAttribute("template");
            String name = e.getAttribute("name");
            boolean byTemplate = template != null && !template.isEmpty()
                                 && removedTemplateNames.contains(template);
            boolean byName = name != null
                             && (name.equals(exactName) || name.startsWith(namePrefix));
            if (byTemplate || byName) out.add(e);
        }
    }

    private static void collectFragmentsUnder(Element ancestor, List<String> path,
                                              String namePrefix, String exactName, List<Element> out) {
        Element cur = ancestor;
        for (String seg : path) {
            cur = firstChild(cur, seg);
            if (cur == null) return;
        }
        NodeList kids = cur.getChildNodes();
        for (int i = 0; i < kids.getLength(); i++) {
            Node n = kids.item(i);
            if (n.getNodeType() != Node.ELEMENT_NODE) continue;
            Element e = (Element) n;
            String name = e.getAttribute("name");
            if (name == null) continue;
            if (name.equals(exactName) || name.startsWith(namePrefix)) {
                out.add(e);
            }
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

    /**
     * Re-derive script snippets the way {@link ScriptInjector} originally
     * injected them, and strip matching lines from each deployment script.
     *
     * <p>Because ScriptInjector's template-per-service-type layout means
     * we need to know the service type to pick the right template, we
     * infer it from the on-disk service module <em>before</em> the
     * module is deleted — or, if the module was already gone, we simply
     * try every service type's templates and remove whichever matches.
     * The latter is rare and only happens when the caller has already
     * partially cleaned up.
     */
    private static List<Path> removeScriptSnippetsForService(Path appRoot,
                                                             Path moduleDir,
                                                             Path scriptsDir,
                                                             Map<String, String> tokens,
                                                             String serviceName,
                                                             String serviceKebab,
                                                             boolean dryRun) throws IOException {
        // Attempt to find the service type's script templates. If the module dir was already deleted
        // in step (1) above (which it is, on non-dry-run), we can't call resolveServiceType. Instead
        // try every type's template dir and process any that match.
        List<String> candidateTypes = Arrays.asList("driver", "processor", "connector", "webservice");
        List<Path> touched = new ArrayList<>();
        String buildTool = tokens.get(TokenUtils.toToken("BuildTool"));

        for (String type : candidateTypes) {
            String templateRoot = String.format("templates/%s/scripts/%s", buildTool, type);
            Path snippetDir;
            try {
                snippetDir = TemplateProcessor.extractTemplateDirectory(
                    "rumi-service-script-template-" + type, templateRoot, true);
            } catch (IOException e) {
                continue;  // template dir not found for this type
            }
            if (snippetDir == null) continue;

            Map<String, String> localTokens = new java.util.HashMap<>(tokens);
            localTokens.put(TokenUtils.toToken("ServiceDisplayName"), TokenUtils.forDisplay(serviceName));
            localTokens.put(TokenUtils.toToken("ServiceTokenName"), serviceKebab);
            localTokens.put(TokenUtils.toToken("ServiceName"),
                tokens.get(TokenUtils.toToken("AppTokenName")) + "-" + serviceKebab);
            String svcPkgPath = TokenUtils.toSlashCase(serviceKebab);
            String svcPkgName = TokenUtils.toPackagePath(serviceKebab);
            localTokens.put(TokenUtils.toToken("ServicePackageName"), svcPkgName);
            localTokens.put(TokenUtils.toToken("ServicePackagePath"), svcPkgPath);

            // ScriptInjector adds a single instance worth; that's the stable identity even for
            // clustered/partitioned services because the snippet itself is literal text that
            // doesn't vary by partition for the main lifecycle scripts in the standard templates.
            // If a template depends on ServicePartition/ServiceInstance, callers should use
            // higher-level remove() operations that re-run all instances — v1 keeps this simple.
            localTokens.put(TokenUtils.toToken("ServicePartition"), "1");
            localTokens.put(TokenUtils.toToken("ServiceInstance"), "1");

            try (Stream<Path> walk = Files.walk(snippetDir)) {
                for (Path snippetPath : (Iterable<Path>) walk::iterator) {
                    if (!Files.isRegularFile(snippetPath)) continue;
                    Path relative = snippetDir.relativize(snippetPath);
                    Path scriptFile = scriptsDir.resolve(relative);
                    if (!Files.exists(scriptFile)) continue;

                    String snippetTemplate = Files.readString(snippetPath);
                    String snippet = TemplateProcessor.applyTokens(snippetTemplate, localTokens);
                    List<String> snippetLines = Arrays.asList(snippet.split("\\r?\\n"));
                    List<String> existing = Files.readAllLines(scriptFile);
                    if (!existing.containsAll(snippetLines)) continue;

                    List<String> kept = stripLines(existing, snippetLines);
                    if (kept.size() == existing.size()) continue;
                    if (!dryRun) Files.write(scriptFile, kept);
                    if (!touched.contains(scriptFile)) touched.add(scriptFile);
                }
            }
        }
        return touched;
    }

    /**
     * Remove the first contiguous occurrence of {@code toStrip} from
     * {@code source}. If the block doesn't appear contiguously, falls back
     * to removing every line that appears in {@code toStrip} (best effort).
     */
    private static List<String> stripLines(List<String> source, List<String> toStrip) {
        if (toStrip.isEmpty()) return new ArrayList<>(source);
        // Try contiguous match first.
        for (int i = 0; i <= source.size() - toStrip.size(); i++) {
            boolean match = true;
            for (int j = 0; j < toStrip.size(); j++) {
                if (!source.get(i + j).equals(toStrip.get(j))) { match = false; break; }
            }
            if (match) {
                List<String> kept = new ArrayList<>(source.size() - toStrip.size());
                kept.addAll(source.subList(0, i));
                kept.addAll(source.subList(i + toStrip.size(), source.size()));
                return kept;
            }
        }
        // Fallback — non-contiguous.
        List<String> kept = new ArrayList<>(source.size());
        java.util.Set<String> toStripSet = new java.util.HashSet<>(toStrip);
        for (String line : source) {
            if (toStripSet.contains(line)) {
                toStripSet.remove(line);  // remove each instance once, in case lines repeat
                continue;
            }
            kept.add(line);
        }
        return kept;
    }
}
