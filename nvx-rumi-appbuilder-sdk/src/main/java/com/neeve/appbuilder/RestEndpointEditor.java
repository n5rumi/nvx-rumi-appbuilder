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
import com.neeve.appbuilder.model.FieldDef;
import com.neeve.appbuilder.model.HandlerDef;
import com.neeve.appbuilder.model.MessageDef;
import com.neeve.appbuilder.model.RestEndpointDef;
import org.w3c.dom.Document;
import org.w3c.dom.Element;
import org.w3c.dom.Node;
import org.w3c.dom.NodeList;

import java.io.IOException;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.HashMap;
import java.util.HashSet;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import java.util.stream.Collectors;

/**
 * Adds REST endpoints to a web service and removes them. An endpoint is an HTTP
 * method and path on the web service that forwards each request to an API
 * operation of a downstream service, through that service's generated client.
 * Every aspect of it is given in the request: the web service that serves it,
 * the method, the path, the downstream service and its operation.
 *
 * <p>Adding an endpoint writes everything the call needs, and removing the last
 * one undoes it:
 *
 * <ol>
 *   <li>the endpoint method, in a resource class of its own for its path under
 *       the web service's {@code resources} package, which the web service's
 *       HTTP server picks up by package scan;
 *   <li>{@code RestEndpointSupport}, shared by every endpoint in the web
 *       service, which reads the request into the request message and renders
 *       the reply;
 *   <li>a client class for the downstream service, extending the
 *       {@code AbstractClient} generated from its api.xml;
 *   <li>a Maven dependency from the web service on the downstream service,
 *       which is where that generated client lives;
 *   <li>in the system {@code config.xml}: the downstream service's
 *       {@code <service>_client_bus}, a {@code CLIENT_BUS_DESCRIPTOR} in every
 *       profile that sets {@code APP_BUS_DESCRIPTOR}, and the client's
 *       {@code connectionDescriptor} in each of those profiles.
 * </ol>
 *
 * <p>Two rules in (5) decide whether a request is ever answered, and neither
 * fails loudly when broken. The client keys its requests with the service name
 * it is given, and the downstream engine subscribes with its own name, which is
 * the service name plus a partition suffix ({@code orders-1}), so the client is
 * always given the engine's name (see {@link #engineName}). And the client bus gets
 * its own descriptor rather than the application bus's: on a loopback bus a
 * second binding from one engine into the same namespace is refused, so the
 * test profile puts every client bus in a {@code <app>-client} namespace of its
 * own. The engine joins its client bus by itself once the bus is declared, so
 * it is not added to the service's {@code <messaging>}.
 *
 * <p>Adds are idempotent on an identical endpoint, and refuse a method and path
 * already taken by a different one. Both operations support {@code dryRun}.
 */
public final class RestEndpointEditor {
    private RestEndpointEditor() {}

    static final List<String> METHODS = Arrays.asList("GET", "POST", "PUT", "PATCH", "DELETE");
    private static final Set<String> BODY_METHODS = new HashSet<>(Arrays.asList("POST", "PUT", "PATCH"));
    private static final Pattern SEGMENT = Pattern.compile("[A-Za-z0-9._~-]+|\\{([A-Za-z_][A-Za-z0-9_]*)\\}");
    private static final String CLIENT_BUS_SUFFIX = "_client_bus";
    private static final String APP_BUS_VAR = "APP_BUS_DESCRIPTOR";
    private static final String CLIENT_BUS_VAR = "CLIENT_BUS_DESCRIPTOR";
    private static final String SNIPPETS = "/templates/maven/snippets/rest/";

    /**
     * Add an endpoint to {@code webService} that serves {@code method path} by
     * calling {@code operation} on {@code service}.
     *
     * @return the files written. When the downstream service has no handler for
     *         the operation's request message, the reason says so: the endpoint
     *         is still added, but every call to it will time out until one is.
     */
    public static ChangeSet addEndpoint(Path appRoot, String webService, String method, String path,
                                        String service, String operation, boolean dryRun) throws IOException {
        String httpMethod = normalizeMethod(method);
        List<String> pathParams = parsePath(path);
        Layout layout = Layout.of(appRoot, webService);
        String targetKebab = requireTarget(appRoot, webService, service);
        if (operation == null || operation.isBlank()) {
            throw new IllegalArgumentException("operation is required");
        }

        ApiOperationDef op = ApiIntrospector.getOperation(appRoot, service, operation);
        if (op == null) {
            throw new IllegalArgumentException("service '" + targetKebab + "' has no API operation '" + operation
                + "'; add it with add_operation first");
        }
        Request request = resolveRequest(appRoot, service, op);
        for (String param : pathParams) {
            if (!request.fields.contains(param)) {
                throw new IllegalArgumentException("path parameter {" + param + "} is not a field of "
                    + request.name + " (fields: " + request.fields + ")");
            }
        }

        RestEndpointDef existing = RestEndpointIntrospector.getEndpoint(appRoot, webService, httpMethod, path);
        if (existing != null) {
            if (existing.getService().equals(targetKebab) && existing.getOperation().equals(operation)) {
                return ChangeSet.noop(httpMethod + " " + path + " already calls " + targetKebab + "." + operation
                    + " on service " + layout.webKebab);
            }
            throw new IllegalArgumentException(httpMethod + " " + path + " on service " + layout.webKebab
                + " already calls " + existing.getService() + "." + existing.getOperation()
                + "; remove it with remove_rest_endpoint first");
        }

        Path configPath = ConnectorSupport.configPath(appRoot, layout.tokens);
        Document doc = loadConfig(configPath);
        String targetEngine = engineName(doc, layout.tokens, targetKebab);

        Map<String, String> tokens = new HashMap<>();
        tokens.put("WebPackageName", layout.webPackage);
        tokens.put("WebTokenName", layout.webFullName);
        tokens.put("TargetServiceName", targetEngine);
        tokens.put("TargetServiceToken", targetKebab);
        tokens.put("TargetPackageName", layout.tokens.get(TokenUtils.toToken("AppPackageName")) + "."
            + TokenUtils.toPackagePath(targetKebab));
        tokens.put("ClientName", layout.webFullName);
        tokens.put("ClientClassName", layout.clientClassName(targetKebab));
        tokens.put("EndpointPath", path);
        tokens.put("HttpMethod", httpMethod);
        tokens.put("JavaMethodName", httpMethod.toLowerCase());
        tokens.put("OperationName", operation);
        tokens.put("OperationMethodName", Character.toLowerCase(operation.charAt(0)) + operation.substring(1));
        tokens.put("RequestClassName", request.className);
        boolean hasBody = BODY_METHODS.contains(httpMethod);
        tokens.put("BodyParameter", hasBody ? ", final String body" : "");
        tokens.put("BodyArgument", hasBody ? "body" : "null");

        ChangeSet.Builder cs = ChangeSet.builder();

        // (1) the endpoint method, in its path's resource class
        Path resourceFile = findResourceFile(layout, path);
        String methodText = render("EndpointMethod.java", tokens);
        if (resourceFile == null) {
            resourceFile = layout.resourcesDir.resolve(uniqueResourceClassName(layout, path) + ".java");
            tokens.put("ResourceClassName", fileStem(resourceFile));
            String content = insertMethod(render("EndpointResource.java", tokens), methodText);
            if (!dryRun) write(resourceFile, content);
            cs.addCreated(resourceFile);
        } else {
            String content = insertMethod(Files.readString(resourceFile), methodText);
            if (!dryRun) Files.writeString(resourceFile, content);
            cs.addModified(resourceFile);
        }

        // (2) the shared support class
        if (!Files.exists(layout.supportFile)) {
            if (!dryRun) write(layout.supportFile, render("RestEndpointSupport.java", tokens));
            cs.addCreated(layout.supportFile);
        }

        // (3) the downstream service's client
        Path clientFile = layout.clientFile(targetKebab);
        if (!Files.exists(clientFile)) {
            if (!dryRun) write(clientFile, render("ServiceClient.java", tokens));
            cs.addCreated(clientFile);
        }

        // (4) the dependency that brings in its generated AbstractClient
        Path pom = layout.webModuleDir.resolve("pom.xml");
        String targetArtifact = artifactId(layout.tokens, targetKebab);
        if (!hasDependency(pom, targetArtifact)) {
            if (!dryRun) addDependency(pom, layout.tokens.get(TokenUtils.toToken("GroupId")), targetArtifact);
            cs.addModified(pom);
        }

        // (5) the client bus and the client's connection
        if (wireClientBus(doc, layout, targetEngine)) {
            if (!dryRun) saveConfig(doc, configPath);
            cs.addModified(configPath);
        }

        if (!hasHandlerFor(appRoot, service, request.name)) {
            cs.reason("service '" + targetKebab + "' has no @EventHandler for " + request.name
                + ", so every call to this endpoint will time out until one replies with sendReply");
        }
        return cs.applied(!dryRun).build();
    }

    /**
     * Remove the endpoint at {@code method path} from {@code webService}. Once no
     * endpoint in the web service calls the downstream service, its client,
     * dependency and client configuration go too; once no endpoint anywhere in
     * the app calls it, so does its client bus. No-op when the endpoint is absent.
     */
    public static ChangeSet removeEndpoint(Path appRoot, String webService, String method, String path,
                                           boolean dryRun) throws IOException {
        String httpMethod = normalizeMethod(method);
        if (path == null || path.isBlank()) throw new IllegalArgumentException("path is required");
        Layout layout = Layout.of(appRoot, webService);
        RestEndpointDef endpoint = RestEndpointIntrospector.getEndpoint(appRoot, webService, httpMethod, path);
        if (endpoint == null) {
            return ChangeSet.noop("no " + httpMethod + " " + path + " endpoint on service " + layout.webKebab);
        }
        String targetKebab = endpoint.getService();
        ChangeSet.Builder cs = ChangeSet.builder();

        // (1) the method, and its class once empty
        String remaining = removeMethod(Files.readString(endpoint.getJavaFile()), httpMethod, path);
        if (countMarkers(remaining) == 0) {
            if (!dryRun) Files.delete(endpoint.getJavaFile());
            cs.addDeleted(endpoint.getJavaFile());
        } else {
            if (!dryRun) Files.writeString(endpoint.getJavaFile(), remaining);
            cs.addModified(endpoint.getJavaFile());
        }

        List<RestEndpointDef> others = new ArrayList<>(RestEndpointIntrospector.listEndpoints(appRoot, webService));
        others.removeIf(e -> e.getMethod().equals(httpMethod) && e.getPath().equals(path));

        // (2) the support class, with the web service's last endpoint
        if (others.isEmpty() && Files.exists(layout.supportFile)) {
            if (!dryRun) Files.delete(layout.supportFile);
            cs.addDeleted(layout.supportFile);
        }

        Path configPath = ConnectorSupport.configPath(appRoot, layout.tokens);
        Document doc = loadConfig(configPath);
        boolean configChanged = false;
        String targetFullName = engineName(doc, layout.tokens, targetKebab);

        // (3)-(5) the client, dependency and client configuration, with the last caller in this web service
        if (others.stream().noneMatch(e -> e.getService().equals(targetKebab))) {
            Path clientFile = layout.clientFile(targetKebab);
            if (Files.exists(clientFile)) {
                if (!dryRun) {
                    Files.delete(clientFile);
                    deleteIfEmpty(layout.clientsDir);
                }
                cs.addDeleted(clientFile);
            }
            Path pom = layout.webModuleDir.resolve("pom.xml");
            String targetArtifact = artifactId(layout.tokens, targetKebab);
            if (hasDependency(pom, targetArtifact)) {
                if (!dryRun) removeDependency(pom, targetArtifact);
                cs.addModified(pom);
            }
            configChanged |= removeClientConfig(doc, targetFullName, layout.webFullName);

            // the client bus, with the last caller in the app
            boolean calledElsewhere = RestEndpointIntrospector.listCallers(appRoot, targetKebab).stream()
                .anyMatch(e -> !e.getWebService().equals(layout.webKebab));
            if (!calledElsewhere) {
                configChanged |= removeClientBus(doc, targetFullName);
            }
        }
        if (configChanged) {
            if (!dryRun) saveConfig(doc, configPath);
            cs.addModified(configPath);
        }
        return cs.applied(!dryRun).build();
    }

    // --- validation -----------------------------------------------------

    static String normalizeMethod(String method) {
        String m = method == null ? "" : method.trim().toUpperCase();
        if (!METHODS.contains(m)) {
            throw new IllegalArgumentException("method must be one of " + METHODS + ", got '" + method + "'");
        }
        return m;
    }

    /** Validate the path and return its parameter names, in order. */
    static List<String> parsePath(String path) {
        if (path == null || !path.startsWith("/")) {
            throw new IllegalArgumentException("path must start with '/', got '" + path + "'");
        }
        if (path.length() > 1 && path.endsWith("/")) {
            throw new IllegalArgumentException("path must not end with '/', got '" + path + "'");
        }
        List<String> params = new ArrayList<>();
        if (path.equals("/")) return params;
        for (String segment : path.substring(1).split("/", -1)) {
            Matcher m = SEGMENT.matcher(segment);
            if (!m.matches()) {
                throw new IllegalArgumentException("path segment '" + segment + "' in '" + path
                    + "' must be a literal (letters, digits, . _ ~ -) or a {parameter}");
            }
            if (m.group(1) != null) {
                if (params.contains(m.group(1))) {
                    throw new IllegalArgumentException("path parameter {" + m.group(1) + "} appears twice in '" + path + "'");
                }
                params.add(m.group(1));
            }
        }
        return params;
    }

    private static String requireTarget(Path appRoot, String webService, String service) throws IOException {
        if (AppIntrospector.resolveServiceType(appRoot, webService) != ServiceBuilder.ServiceType.WEBSERVICE) {
            throw new IllegalArgumentException("service '" + webService + "' is not a webservice; REST endpoints are served by a webservice");
        }
        if (service == null || service.isBlank()) {
            throw new IllegalArgumentException("service is required");
        }
        String targetKebab = TokenUtils.toKebabCase(service);
        if (targetKebab.equals(TokenUtils.toKebabCase(webService))) {
            throw new IllegalArgumentException("a webservice cannot call its own operations through a client; "
                + "inject the request into its own engine instead (injectRequestAndWaitForReply)");
        }
        if (!Files.isDirectory(AppIntrospector.resolveServiceModuleDir(appRoot, service))) {
            throw new IllegalArgumentException("service '" + service + "' not found under " + appRoot);
        }
        return targetKebab;
    }

    /** The operation's request message: its class and its field names. */
    private static final class Request {
        final String name;
        final String className;
        final Set<String> fields;

        Request(String name, String className, Set<String> fields) {
            this.name = name;
            this.className = className;
            this.fields = fields;
        }
    }

    /**
     * Find the request message in the model the downstream api.xml names, which
     * is the model its generated client is built from (see
     * {@link ApiOperationEditor}).
     */
    private static Request resolveRequest(Path appRoot, String service, ApiOperationDef op) throws IOException {
        Document api = ApiIntrospector.loadApiXml(appRoot, service);
        Element messages = ApiOperationEditor.firstChild(api.getDocumentElement(), "messages");
        String modelFile = messages == null ? null : messages.getAttribute("modelFile").trim();
        Path model = modelFile == null || modelFile.isEmpty() ? null : ApiOperationEditor.resolveModelFile(appRoot, modelFile);
        if (model == null) {
            throw new IllegalStateException("cannot find the message model named by service '" + service + "' api.xml");
        }
        Document doc;
        try {
            doc = XmlDomUtils.parseXmlDocument(model);
        } catch (Exception e) {
            throw new IOException("failed to parse " + model, e);
        }
        String namespace = doc.getDocumentElement().getAttribute("namespace");
        for (MessageDef m : MessageIntrospector.parseMessages(doc)) {
            if (m.getName().equals(op.getInMessage())) {
                Set<String> fields = m.getFields().stream().map(FieldDef::getName)
                    .collect(Collectors.toCollection(LinkedHashSet::new));
                return new Request(m.getName(), namespace + "." + m.getName(), fields);
            }
        }
        throw new IllegalStateException("request message " + op.getInMessage() + " of operation " + op.getName()
            + " is not in " + modelFile);
    }

    private static boolean hasHandlerFor(Path appRoot, String service, String messageName) {
        try {
            for (HandlerDef h : HandlerIntrospector.listHandlers(appRoot, service)) {
                if (messageName.equals(h.getMessageType())) return true;
            }
            return false;
        } catch (Exception e) {
            // a service whose Main.java cannot be read is reported elsewhere; do not warn on a guess
            return true;
        }
    }

    // --- generated Java -------------------------------------------------

    private static String render(String snippet, Map<String, String> tokens) throws IOException {
        String text;
        try (InputStream in = RestEndpointEditor.class.getResourceAsStream(SNIPPETS + snippet)) {
            if (in == null) throw new IOException("REST endpoint snippet not found: " + snippet);
            text = new String(in.readAllBytes(), StandardCharsets.UTF_8);
        }
        for (Map.Entry<String, String> t : tokens.entrySet()) {
            text = text.replace(TokenUtils.toToken(t.getKey()), t.getValue());
        }
        return text;
    }

    /** The generated resource class already serving {@code path}, or null. */
    private static Path findResourceFile(Layout layout, String path) throws IOException {
        if (!Files.isDirectory(layout.resourcesDir)) return null;
        String annotation = "@Path(\"" + path + "\")";
        try (var files = Files.list(layout.resourcesDir)) {
            for (Path file : (Iterable<Path>) files.sorted()::iterator) {
                if (!file.toString().endsWith(".java")) continue;
                String text = Files.readString(file);
                if (countMarkers(text) > 0 && text.lines().anyMatch(l -> l.trim().equals(annotation))) {
                    return file;
                }
            }
        }
        return null;
    }

    /** {@code /contracts/{id}/lines} becomes {@code ContractsByIdLinesResource}. */
    static String resourceClassName(String path) {
        StringBuilder sb = new StringBuilder();
        for (String segment : path.split("/")) {
            if (segment.isEmpty()) continue;
            boolean param = segment.startsWith("{");
            String word = param ? segment.substring(1, segment.length() - 1) : segment;
            if (param) sb.append("By");
            for (String part : word.split("[^A-Za-z0-9]+")) {
                if (part.isEmpty()) continue;
                sb.append(Character.toUpperCase(part.charAt(0))).append(part.substring(1));
            }
        }
        if (sb.length() == 0) sb.append("Root");
        if (!Character.isJavaIdentifierStart(sb.charAt(0))) sb.insert(0, "Path");
        return sb.append("Resource").toString();
    }

    private static String uniqueResourceClassName(Layout layout, String path) {
        String base = resourceClassName(path);
        String name = base;
        for (int i = 2; Files.exists(layout.resourcesDir.resolve(name + ".java")); i++) {
            name = base + i;
        }
        return name;
    }

    /** Insert {@code method} before the class's closing brace. */
    private static String insertMethod(String classText, String method) {
        int close = classText.lastIndexOf('}');
        String before = classText.substring(0, close);
        String body = before.stripTrailing();
        String separator = body.endsWith("{") ? "\n" : "\n\n";
        return body + separator + method.stripTrailing() + "\n" + classText.substring(close);
    }

    /** Remove the marked method for {@code method path}, with the blank line before it. */
    private static String removeMethod(String classText, String method, String path) {
        List<String> lines = new ArrayList<>(Arrays.asList(classText.split("\n", -1)));
        for (int i = 0; i < lines.size(); i++) {
            Matcher m = RestEndpointIntrospector.MARKER.matcher(lines.get(i));
            if (m.matches() && m.group(1).equals(method) && m.group(2).equals(path)) {
                int end = i;
                while (end < lines.size() && !lines.get(end).trim().equals(RestEndpointIntrospector.END_MARKER)) end++;
                int start = i;
                if (start > 0 && lines.get(start - 1).isBlank()) start--;
                lines.subList(start, Math.min(end + 1, lines.size())).clear();
                break;
            }
        }
        return String.join("\n", lines);
    }

    private static int countMarkers(String text) {
        return (int) text.lines().filter(l -> RestEndpointIntrospector.MARKER.matcher(l).matches()).count();
    }

    private static String fileStem(Path file) {
        String name = file.getFileName().toString();
        return name.substring(0, name.length() - ".java".length());
    }

    private static void write(Path file, String content) throws IOException {
        Files.createDirectories(file.getParent());
        Files.writeString(file, content);
    }

    private static void deleteIfEmpty(Path dir) throws IOException {
        if (Files.isDirectory(dir)) {
            try (var s = Files.list(dir)) {
                if (s.findAny().isEmpty()) Files.delete(dir);
            }
        }
    }

    // --- pom ------------------------------------------------------------

    private static String artifactId(Map<String, String> tokens, String serviceKebab) {
        return tokens.get(TokenUtils.toToken("ParentArtifactId")) + "-" + serviceKebab;
    }

    private static boolean hasDependency(Path pom, String artifactId) throws IOException {
        return Files.readString(pom).contains("<artifactId>" + artifactId + "</artifactId>");
    }

    /** Add the dependency at the end of the project's own {@code <dependencies>}, the first in the file. */
    private static void addDependency(Path pom, String groupId, String artifactId) throws IOException {
        List<String> lines = new ArrayList<>(Files.readAllLines(pom));
        for (int i = 0; i < lines.size(); i++) {
            if (lines.get(i).contains("</dependencies>")) {
                lines.add(i, String.format(
                    "        <!-- the service called by the generated REST endpoints, for its generated client -->\n" +
                    "        <dependency>\n" +
                    "            <groupId>%s</groupId>\n" +
                    "            <artifactId>%s</artifactId>\n" +
                    "            <version>${project.version}</version>\n" +
                    "        </dependency>", groupId, artifactId));
                Files.write(pom, lines);
                return;
            }
        }
        throw new IOException("no <dependencies> block in " + pom);
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
        if (open > 0 && lines.get(open - 1).contains("generated REST endpoints")) open--;
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
        if (childNamed(buses, "bus", busName) == null) {
            Element bus = doc.createElementNS(root.getNamespaceURI(), "bus");
            bus.setAttribute("name", busName);
            bus.setAttribute("descriptor", "${" + CLIENT_BUS_VAR + "}");
            buses.appendChild(bus);
            changed = true;
        }

        List<Element> envs = profileEnvsWithAppBus(doc);
        if (envs.isEmpty()) {
            throw new IllegalStateException("no profile in config.xml sets " + APP_BUS_VAR
                + ", so there is nothing to derive the client bus descriptor from");
        }
        String appToken = layout.tokens.get(TokenUtils.toToken("AppTokenName"));
        for (Element env : envs) {
            if (directChild(env, CLIENT_BUS_VAR) == null) {
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
                layout.webFullName);
            if (directChild(client, "connectionDescriptor") == null) {
                Element descriptor = doc.createElementNS(env.getNamespaceURI(), "connectionDescriptor");
                descriptor.setTextContent("${" + CLIENT_BUS_VAR + "}");
                client.appendChild(descriptor);
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

    /** Remove the client bus, and the profiles' client bus descriptor once no client bus is left. */
    private static boolean removeClientBus(Document doc, String targetFullName) {
        Element buses = XmlDomUtils.getElementByPath(doc.getDocumentElement(), Collections.singletonList("buses"));
        if (buses == null) return false;
        Element bus = childNamed(buses, "bus", targetFullName + CLIENT_BUS_SUFFIX);
        if (bus == null) return false;
        XmlDomUtils.removeElement(bus);
        boolean anyLeft = false;
        for (Element b : elementChildren(buses)) {
            if (b.getAttribute("name").endsWith(CLIENT_BUS_SUFFIX)) anyLeft = true;
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

    private static Element directChild(Element parent, String localName) {
        for (Element e : elementChildren(parent)) {
            if (localName.equals(e.getLocalName())) return e;
        }
        return null;
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
