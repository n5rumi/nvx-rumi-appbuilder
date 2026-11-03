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

import com.neeve.appbuilder.RestEndpointIntrospector.Layout;
import com.neeve.appbuilder.model.ApiOperationDef;
import com.neeve.appbuilder.model.ChangeSet;
import com.neeve.appbuilder.model.ServiceClientDef;
import org.w3c.dom.Document;
import org.w3c.dom.Element;
import org.w3c.dom.Node;
import org.w3c.dom.NodeList;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Collections;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import java.util.stream.Stream;

/**
 * Prepares a web service for request-reply with another service, and undoes it.
 * This is the prerequisite for a REST endpoint whose body calls that service:
 * message passing needs only the shared message model, but request-reply needs a
 * client in the code and configuration for its bus. Adding one writes:
 *
 * <ol>
 *   <li>a client class in the web service's {@code rest} package, extending the
 *       {@code AbstractClient} generated from the target's api.xml, with a method
 *       per operation;
 *   <li>a Maven dependency from the web service on the target, which is where that
 *       generated client lives;
 *   <li>in the system {@code config.xml}: the target's {@code <engine>_client_bus},
 *       a {@code REST_CLIENT_BUS_DESCRIPTOR} in every profile that sets
 *       {@code APP_BUS_DESCRIPTOR}, and the client's {@code connectionDescriptor}
 *       in each of those profiles. A client bus the developer already declared is
 *       reused as declared, with its own descriptor and any requests key it pins,
 *       and is never removed.
 * </ol>
 *
 * <p>Two rules in (3) decide whether a request is ever answered, and neither
 * fails loudly when broken. The client keys its requests with the service name it
 * is given, and the engine subscribes with its own name, which is the service
 * name plus a partition suffix ({@code orders-1}), so the client is always given
 * the engine's name (see {@link #engineName}). And the client bus gets its own
 * descriptor rather than the application bus's: on a loopback bus a second
 * binding from one engine into the same namespace is refused, so the test profile
 * puts client buses in an {@code <app>-client} namespace of their own. The engine
 * joins its client bus by itself once the bus is declared, so it is not added to
 * the service's {@code <messaging>}.
 *
 * <p>Adds are idempotent. A client cannot be removed while code in the web
 * service still uses it, unless forced. Both support {@code dryRun}.
 */
public final class ServiceClientEditor {
    private ServiceClientEditor() {}

    private static final String CLIENT_BUS_SUFFIX = "_client_bus";
    private static final String APP_BUS_VAR = "APP_BUS_DESCRIPTOR";
    // a name of its own, so a developer's CLIENT_BUS_DESCRIPTOR is never mistaken for ours
    private static final String CLIENT_BUS_VAR = "REST_CLIENT_BUS_DESCRIPTOR";
    static final String CLIENT_MARKER = "// @rest-endpoint-client";
    private static final String GENERATED_BUS_DESCRIPTOR = "${" + CLIENT_BUS_VAR + "}";
    private static final String DEPENDENCY_MARKER = "<!-- the service called by the generated REST endpoints, for its generated client -->";

    /**
     * Give {@code webService} a client for request-reply with {@code service}.
     *
     * @return the files written; the reason names the client class and how to call it.
     */
    public static ChangeSet addServiceClient(Path appRoot, String webService, String service, boolean dryRun)
            throws IOException {
        Layout layout = Layout.of(appRoot, webService);
        String targetKebab = requireTarget(appRoot, webService, service);
        Path configPath = ConnectorSupport.configPath(appRoot, layout.tokens);
        Document doc = loadConfig(configPath);
        String engine = engineName(doc, layout.tokens, targetKebab);

        // everything that can refuse runs before anything is written
        Path clientFile = layout.clientFile(targetKebab);
        boolean clientExists = Files.exists(clientFile);
        if (clientExists && !Files.readString(clientFile).contains(CLIENT_MARKER)) {
            throw new IllegalStateException(clientFile + " exists and was not generated by App Builder;"
                + " move it out of the " + layout.webPackage + ".rest package, which holds generated code");
        }
        Path pom = layout.webModuleDir.resolve("pom.xml");
        String targetArtifact = artifactId(layout.tokens, targetKebab);
        boolean needDependency = !hasDependency(pom, targetArtifact);
        if (needDependency && projectDependenciesEnd(Files.readAllLines(pom)) < 0) {
            throw new IllegalStateException("no project <dependencies> block in " + pom);
        }
        boolean configChanged = wireClientBus(doc, layout, engine);

        String usage = usage(appRoot, layout, targetKebab);
        if (clientExists && !needDependency && !configChanged) {
            return ChangeSet.noop(layout.webKebab + " already has a client for " + targetKebab + ". " + usage);
        }

        ChangeSet.Builder cs = ChangeSet.builder();
        if (!clientExists) {
            Map<String, String> tokens = new HashMap<>();
            tokens.put("WebPackageName", layout.webPackage);
            tokens.put("TargetServiceName", engine);
            tokens.put("TargetServiceToken", targetKebab);
            tokens.put("TargetPackageName", layout.tokens.get(TokenUtils.toToken("AppPackageName")) + "."
                + TokenUtils.toPackagePath(targetKebab));
            tokens.put("ClientName", layout.clientName());
            tokens.put("ClientClassName", layout.clientClassName(targetKebab));
            if (!dryRun) RestEndpointEditor.write(clientFile, RestEndpointEditor.render("ServiceClient.java", tokens));
            cs.addCreated(clientFile);
        }
        if (needDependency) {
            if (!dryRun) addDependency(pom, layout.tokens.get(TokenUtils.toToken("GroupId")), targetArtifact);
            cs.addModified(pom);
        }
        if (configChanged) {
            if (!dryRun) saveConfig(doc, configPath);
            cs.addModified(configPath);
        }
        return cs.reason(usage).applied(!dryRun).build();
    }

    /**
     * Remove {@code webService}'s client for {@code service}: the client class,
     * the dependency this editor added, the client's configuration and, once no
     * web service in the app has a client for that service, its generated client
     * bus. Refused while code in the web service uses the client, unless
     * {@code force}. No-op when there is no such client.
     */
    public static ChangeSet removeServiceClient(Path appRoot, String webService, String service, boolean force,
                                                boolean dryRun) throws IOException {
        Layout layout = Layout.of(appRoot, webService);
        String targetKebab = TokenUtils.toKebabCase(service);
        Path clientFile = layout.clientFile(targetKebab);
        if (!Files.exists(clientFile) || !Files.readString(clientFile).contains(CLIENT_MARKER)) {
            return ChangeSet.noop(layout.webKebab + " has no client for " + targetKebab);
        }
        if (!force) {
            List<String> users = usersOf(layout, layout.clientClassName(targetKebab), clientFile);
            if (!users.isEmpty()) {
                throw new IllegalStateException("cannot remove " + layout.webKebab + "'s client for " + targetKebab
                    + ": still used by " + String.join(", ", users)
                    + " (change those first, or force the removal)");
            }
        }
        String engine = generatedEngineName(clientFile);

        ChangeSet.Builder cs = ChangeSet.builder();
        if (!dryRun) {
            Files.delete(clientFile);
            RestEndpointEditor.deleteIfEmpty(layout.clientsDir);
        }
        cs.addDeleted(clientFile);
        Path pom = layout.webModuleDir.resolve("pom.xml");
        String targetArtifact = artifactId(layout.tokens, targetKebab);
        if (hasGeneratedDependency(pom, targetArtifact)) {
            if (!dryRun) removeDependency(pom, targetArtifact);
            cs.addModified(pom);
        }
        Path configPath = ConnectorSupport.configPath(appRoot, layout.tokens);
        Document doc = loadConfig(configPath);
        boolean configChanged = false;
        if (engine != null) {
            configChanged |= removeClientConfig(doc, engine, layout.clientName());
            boolean heldElsewhere = ServiceClientIntrospector.listClientsOf(appRoot, targetKebab).stream()
                .anyMatch(c -> !c.getWebService().equals(layout.webKebab));
            if (!heldElsewhere) configChanged |= removeClientBus(doc, engine);
        }
        if (configChanged) {
            if (!dryRun) saveConfig(doc, configPath);
            cs.addModified(configPath);
        }
        return cs.applied(!dryRun).build();
    }

    // --- validation and helpers -------------------------------------------

    private static String requireTarget(Path appRoot, String webService, String service) throws IOException {
        if (AppIntrospector.resolveServiceType(appRoot, webService) != ServiceBuilder.ServiceType.WEBSERVICE) {
            throw new IllegalArgumentException("service '" + webService + "' is not a webservice; service clients are"
                + " held by a webservice, whose HTTP threads may block for a reply");
        }
        if (service == null || service.isBlank()) {
            throw new IllegalArgumentException("service is required");
        }
        String targetKebab = TokenUtils.toKebabCase(service);
        if (targetKebab.equals(TokenUtils.toKebabCase(webService))) {
            throw new IllegalArgumentException("a webservice needs no client for its own service; an endpoint body"
                + " calls its own engine with injectRequestAndWaitForReply");
        }
        if (!Files.isDirectory(AppIntrospector.resolveServiceModuleDir(appRoot, service))) {
            throw new IllegalArgumentException("service '" + service + "' not found under " + appRoot);
        }
        if (!Files.exists(AppIntrospector.resolveApiXmlFile(appRoot, service))) {
            throw new IllegalArgumentException("service '" + service + "' has no api.xml, so there is no client to generate");
        }
        return targetKebab;
    }

    /** How to call the client, for the add's reason. */
    private static String usage(Path appRoot, Layout layout, String targetKebab) throws IOException {
        List<String> methods = ServiceClientIntrospector.methods(appRoot, targetKebab);
        String cls = layout.clientClassName(targetKebab);
        return "Call it from an endpoint body as " + cls + ".get().<operation>(request)"
            + (methods.isEmpty() ? "; " + targetKebab + " has no api.xml operations yet (add_operation)"
                                 : ": " + String.join(", ", methods));
    }

    /** Java files in the web service, other than the client itself, that name the client class. */
    static List<String> usersOf(Layout layout, String className, Path clientFile) throws IOException {
        List<String> users = new ArrayList<>();
        Pattern word = Pattern.compile("\\b" + Pattern.quote(className) + "\\b");
        Path src = layout.webModuleDir.resolve("src");
        if (!Files.isDirectory(src)) return users;
        try (Stream<Path> walk = Files.walk(src)) {
            for (Path p : (Iterable<Path>) walk.filter(f -> f.toString().endsWith(".java"))::iterator) {
                if (p.equals(clientFile)) continue;
                if (word.matcher(Files.readString(p)).find()) users.add(p.getFileName().toString());
            }
        }
        return users;
    }

    /** The engine name a generated client was built for, or null. */
    static String generatedEngineName(Path clientFile) throws IOException {
        if (!Files.exists(clientFile)) return null;
        Matcher m = Pattern.compile("SERVICE_NAME = \"([^\"]+)\"").matcher(Files.readString(clientFile));
        return m.find() ? m.group(1) : null;
    }

    // --- pom ------------------------------------------------------------

    private static String artifactId(Map<String, String> tokens, String serviceKebab) {
        return tokens.get(TokenUtils.toToken("ParentArtifactId")) + "-" + serviceKebab;
    }

    /**
     * Whether the project itself depends on {@code artifactId}: a dependency in
     * its own {@code <dependencies>}, not one in {@code <dependencyManagement>},
     * a plugin's dependencies, a profile, or an {@code <exclusion>}, none of which
     * puts the target's generated client on the compile classpath.
     */
    private static boolean hasDependency(Path pom, String artifactId) throws IOException {
        int nested = 0;
        int exclusions = 0;
        for (String line : Files.readAllLines(pom)) {
            if (line.contains("<dependencyManagement>") || line.contains("<build>") || line.contains("<profiles>")) nested++;
            if (line.contains("</dependencyManagement>") || line.contains("</build>") || line.contains("</profiles>")) nested--;
            if (line.contains("<exclusions>")) exclusions++;
            if (line.contains("</exclusions>")) exclusions--;
            if (nested == 0 && exclusions == 0 && line.contains("<artifactId>" + artifactId + "</artifactId>")) return true;
        }
        return false;
    }

    private static boolean hasGeneratedDependency(Path pom, String artifactId) throws IOException {
        List<String> lines = Files.readAllLines(pom);
        for (int i = 0; i < lines.size(); i++) {
            if (lines.get(i).contains("<artifactId>" + artifactId + "</artifactId>")) {
                int open = i;
                while (open >= 0 && !lines.get(open).contains("<dependency>")) open--;
                return open > 0 && lines.get(open - 1).contains(DEPENDENCY_MARKER);
            }
        }
        return false;
    }

    /**
     * The line of the {@code </dependencies>} that closes the project's own
     * dependencies, skipping the ones inside {@code <dependencyManagement>},
     * {@code <build>} (plugin dependencies) and {@code <profiles>}; -1 if none.
     */
    static int projectDependenciesEnd(List<String> lines) {
        int nested = 0;
        for (int i = 0; i < lines.size(); i++) {
            String line = lines.get(i);
            if (line.contains("<dependencyManagement>") || line.contains("<build>") || line.contains("<profiles>")) nested++;
            if (line.contains("</dependencyManagement>") || line.contains("</build>") || line.contains("</profiles>")) nested--;
            if (nested == 0 && line.contains("</dependencies>")) return i;
        }
        return -1;
    }

    /** Add the dependency at the end of the project's own {@code <dependencies>}. */
    private static void addDependency(Path pom, String groupId, String artifactId) throws IOException {
        List<String> lines = new ArrayList<>(Files.readAllLines(pom));
        int i = projectDependenciesEnd(lines);
        if (i < 0) throw new IOException("no project <dependencies> block in " + pom);
        lines.add(i, String.format(
            "        " + DEPENDENCY_MARKER + "\n" +
            "        <dependency>\n" +
            "            <groupId>%s</groupId>\n" +
            "            <artifactId>%s</artifactId>\n" +
            "            <version>${project.version}</version>\n" +
            "        </dependency>", groupId, artifactId));
        Files.write(pom, lines);
    }

    private static void removeDependency(Path pom, String artifactId) throws IOException {
        List<String> lines = new ArrayList<>(Files.readAllLines(pom));
        int artifact = -1;
        for (int i = 0; i < lines.size(); i++) {
            if (lines.get(i).contains("<artifactId>" + artifactId + "</artifactId>")) { artifact = i; break; }
        }
        if (artifact < 0) return;
        int open = artifact;
        while (open >= 0 && !lines.get(open).contains("<dependency>")) open--;
        int close = artifact;
        while (close < lines.size() && !lines.get(close).contains("</dependency>")) close++;
        if (open < 0 || close >= lines.size()) return;
        if (open > 0 && lines.get(open - 1).contains(DEPENDENCY_MARKER)) open--;
        lines.subList(open, close + 1).clear();
        Files.write(pom, lines);
    }

    // --- config.xml -----------------------------------------------------

    private static Document loadConfig(Path configPath) throws IOException {
        if (!Files.exists(configPath)) throw new IOException("config.xml not found at " + configPath);
        try {
            return XmlDomUtils.parseXmlDocument(configPath);
        } catch (Exception e) {
            throw new IOException("failed to parse " + configPath, e);
        }
    }

    private static void saveConfig(Document doc, Path configPath) throws IOException {
        try {
            XmlDomUtils.saveXmlDocument(doc, configPath);
        } catch (Exception e) {
            throw new IOException("failed to write " + configPath, e);
        }
    }

    /**
     * Declare the downstream service's client bus, give every profile that sets
     * the application bus a client bus descriptor, and point the client at it.
     *
     * @return true if anything changed.
     */
    private static boolean wireClientBus(Document doc, Layout layout, String targetFullName) {
        Element root = doc.getDocumentElement();
        boolean changed = false;

        Element buses = XmlDomUtils.getOrCreateChild(root, "buses");
        String busName = targetFullName + CLIENT_BUS_SUFFIX;
        Element bus = childNamed(buses, "bus", busName);
        boolean generatedBus = bus == null || GENERATED_BUS_DESCRIPTOR.equals(bus.getAttribute("descriptor"));
        if (bus == null) {
            bus = doc.createElementNS(root.getNamespaceURI(), "bus");
            bus.setAttribute("name", busName);
            bus.setAttribute("descriptor", GENERATED_BUS_DESCRIPTOR);
            buses.appendChild(bus);
            changed = true;
        }
        // A client bus the developer declared is used exactly as declared: the
        // client connects with the bus's own descriptor and, if the bus pins its
        // requests channel key, sends with that key. Anything else would put the
        // client on a different bus or key from the engine, and nothing reports it.
        String descriptor = generatedBus ? GENERATED_BUS_DESCRIPTOR : bus.getAttribute("descriptor");
        String requestsKey = generatedBus ? null : pinnedRequestsKey(bus);

        List<Element> envs = profileEnvsWithAppBus(doc);
        if (envs.isEmpty()) {
            throw new IllegalStateException("no profile in config.xml sets " + APP_BUS_VAR
                + ", so there is nothing to derive the client bus descriptor from");
        }
        String appToken = layout.tokens.get(TokenUtils.toToken("AppTokenName"));
        for (Element env : envs) {
            if (generatedBus && directChild(env, CLIENT_BUS_VAR) == null) {
                Element appBus = directChild(env, APP_BUS_VAR);
                String appDescriptor = appBus.getTextContent().trim();
                Element clientBus = doc.createElementNS(env.getNamespaceURI(), CLIENT_BUS_VAR);
                // a loopback namespace takes one binding per engine, so client buses get one of their own
                clientBus.setTextContent(appDescriptor.startsWith("loopback://")
                    ? "loopback://" + appToken + "-client" : appDescriptor);
                env.insertBefore(clientBus, appBus.getNextSibling());
                changed = true;
            }
            Element client = XmlDomUtils.getOrCreateChild(
                XmlDomUtils.getOrCreateChild(XmlDomUtils.getOrCreateChild(env, targetFullName), "client"),
                layout.clientName());
            if (directChild(client, "connectionDescriptor") == null) {
                Element connection = doc.createElementNS(env.getNamespaceURI(), "connectionDescriptor");
                connection.setTextContent(descriptor);
                client.appendChild(connection);
                changed = true;
            }
            if (requestsKey != null && directChild(client, "requestsChannelKey") == null) {
                Element key = doc.createElementNS(env.getNamespaceURI(), "requestsChannelKey");
                key.setTextContent(requestsKey);
                client.appendChild(key);
                changed = true;
            }
        }
        return changed;
    }

    private static boolean removeClientConfig(Document doc, String targetFullName, String clientName) {
        boolean changed = false;
        for (Element env : profileEnvsWithAppBus(doc)) {
            Element service = directChild(env, targetFullName);
            Element clients = service == null ? null : directChild(service, "client");
            Element client = clients == null ? null : directChild(clients, clientName);
            if (client == null) continue;
            XmlDomUtils.removeElement(client);
            if (!hasElementChildren(clients)) XmlDomUtils.removeElement(clients);
            if (!hasElementChildren(service)) XmlDomUtils.removeElement(service);
            changed = true;
        }
        return changed;
    }

    /**
     * Remove the client bus if this editor created it, and the profiles' client
     * bus descriptor once no generated client bus is left. A client bus the
     * developer declared stays: other callers may depend on it.
     */
    private static boolean removeClientBus(Document doc, String targetFullName) {
        Element buses = XmlDomUtils.getElementByPath(doc.getDocumentElement(), Collections.singletonList("buses"));
        if (buses == null) return false;
        Element bus = childNamed(buses, "bus", targetFullName + CLIENT_BUS_SUFFIX);
        if (bus == null || !GENERATED_BUS_DESCRIPTOR.equals(bus.getAttribute("descriptor"))) return false;
        XmlDomUtils.removeElement(bus);
        boolean anyLeft = false;
        for (Element b : elementChildren(buses)) {
            if (GENERATED_BUS_DESCRIPTOR.equals(b.getAttribute("descriptor"))) anyLeft = true;
        }
        if (!anyLeft) {
            for (Element env : profileEnvsWithAppBus(doc)) {
                Element clientBus = directChild(env, CLIENT_BUS_VAR);
                if (clientBus != null) XmlDomUtils.removeElement(clientBus);
            }
        }
        return true;
    }

    /**
     * The name of the engine that serves {@code serviceKebab}: its
     * {@code <app>} instance, which the scaffolder names
     * {@code <service>-<partition>}. That name, not the service's, is what the
     * engine subscribes to its client bus with and what it names that bus after,
     * so it is the name the client must be given. A client keyed with the bare
     * service name reaches a bus nothing listens on, and nothing on either side
     * reports it.
     */
    static String engineName(Document doc, Map<String, String> tokens, String serviceKebab) {
        String serviceName = ConnectorSupport.serviceFullName(tokens, serviceKebab);
        String template = serviceName + "-template";
        Set<String> engines = new java.util.TreeSet<>();
        NodeList apps = doc.getElementsByTagNameNS("*", "app");
        for (int i = 0; i < apps.getLength(); i++) {
            Element app = (Element) apps.item(i);
            if (template.equals(app.getAttribute("template")) && !app.getAttribute("name").isEmpty()) {
                engines.add(app.getAttribute("name"));
            }
        }
        if (engines.isEmpty()) {
            throw new IllegalStateException("config.xml declares no app instance of service '" + serviceKebab
                + "' (no <app template=\"" + template + "\">)");
        }
        if (engines.size() > 1) {
            throw new IllegalArgumentException("service '" + serviceKebab + "' runs as " + engines.size()
                + " partitions " + engines + ", and a REST endpoint's client calls a single engine;"
                + " endpoints for a partitioned service are not supported yet");
        }
        return engines.iterator().next();
    }

    /** The key a declared client bus pins on its requests channel, or null. */
    private static String pinnedRequestsKey(Element bus) {
        Element channels = directChild(bus, "channels");
        if (channels == null) return null;
        for (Element channel : elementChildren(channels)) {
            if ("requests".equals(channel.getAttribute("name"))) {
                Element key = directChild(channel, "key");
                String text = key == null ? "" : key.getTextContent().trim();
                return text.isEmpty() ? null : text;
            }
        }
        return null;
    }

    private static List<Element> profileEnvsWithAppBus(Document doc) {
        List<Element> out = new ArrayList<>();
        Element profiles = XmlDomUtils.getElementByPath(doc.getDocumentElement(), Collections.singletonList("profiles"));
        if (profiles == null) return out;
        for (Element profile : elementChildren(profiles)) {
            Element env = directChild(profile, "env");
            if (env != null && directChild(env, APP_BUS_VAR) != null) out.add(env);
        }
        return out;
    }

    private static Element childNamed(Element parent, String localName, String nameAttribute) {
        for (Element e : elementChildren(parent)) {
            if (localName.equals(e.getLocalName()) && nameAttribute.equals(e.getAttribute("name"))) return e;
        }
        return null;
    }

    static Element directChild(Element parent, String localName) {
        return ApiOperationEditor.firstChild(parent, localName);
    }

    private static boolean hasElementChildren(Element parent) {
        return !elementChildren(parent).isEmpty();
    }

    private static List<Element> elementChildren(Element parent) {
        List<Element> out = new ArrayList<>();
        NodeList kids = parent.getChildNodes();
        for (int i = 0; i < kids.getLength(); i++) {
            Node n = kids.item(i);
            if (n.getNodeType() == Node.ELEMENT_NODE) out.add((Element) n);
        }
        return out;
    }
}
