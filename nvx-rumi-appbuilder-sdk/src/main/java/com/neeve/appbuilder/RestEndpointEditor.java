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
import com.neeve.appbuilder.model.ChangeSet;
import com.neeve.appbuilder.model.RestEndpointDef;

import java.io.IOException;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import java.util.stream.Stream;

/**
 * Adds, updates and removes REST endpoints on a web service. An endpoint is an
 * HTTP method and path whose body is the caller's Java code, the way a message
 * handler's is: this editor writes the JAX-RS method around it, and the caller
 * decides what it does.
 *
 * <p>A body talks to Rumi in one of three ways. Request-reply with its own
 * service needs nothing extra: the engine is injected into every resource
 * ({@code _engineProvider.getEngine().injectRequestAndWaitForReply(...)}). A
 * one-way message to a downstream service needs only the shared message model.
 * Request-reply with another service needs a client in the code and
 * configuration for its bus, which {@link ServiceClientEditor#addServiceClient}
 * adds as a separate, explicit step; a body that names a service's client before
 * that step is refused, naming the step.
 *
 * <p>Each path gets its own resource class under the web service's
 * {@code resources} package, which its HTTP server picks up by package scan; the
 * path is at class level because JAX-RS picks a root resource by its longest
 * literal prefix and does not backtrack, so a shared {@code @Path("/")} class
 * would be shadowed by {@code WebMain}'s. Method signature: path parameters as
 * {@code @PathParam String} arguments, {@code UriInfo uri} for the query, and the
 * request body as {@code String body} for POST, PUT and PATCH; it returns a JAX-RS
 * {@code Response}. The first endpoint also adds {@code rest.RestJson}, which reads
 * a request into a message and renders a reply.
 *
 * <p>Adds are idempotent on an identical body and refuse a route already taken.
 * All three operations support {@code dryRun}.
 */
public final class RestEndpointEditor {
    private RestEndpointEditor() {}

    static final List<String> METHODS = Arrays.asList("GET", "POST", "PUT", "PATCH", "DELETE");
    private static final Set<String> BODY_METHODS = new HashSet<>(Arrays.asList("POST", "PUT", "PATCH"));
    private static final Set<String> RESERVED_PARAMS = new HashSet<>(Arrays.asList("uri", "body", "_engineProvider"));
    // a path parameter becomes a Java argument name, so it must be a legal, unshadowing one
    private static final Set<String> JAVA_KEYWORDS = new HashSet<>(Arrays.asList(
        "abstract", "assert", "boolean", "break", "byte", "case", "catch", "char", "class", "const", "continue",
        "default", "do", "double", "else", "enum", "extends", "final", "finally", "float", "for", "goto", "if",
        "implements", "import", "instanceof", "int", "interface", "long", "native", "new", "package", "private",
        "protected", "public", "return", "short", "static", "strictfp", "super", "switch", "synchronized", "this",
        "throw", "throws", "transient", "try", "void", "volatile", "while", "true", "false", "null", "var",
        "record", "yield", "_"));
    private static final Pattern SEGMENT = Pattern.compile("[A-Za-z0-9._~-]+|\\{([A-Za-z_][A-Za-z0-9_]*)\\}");
    private static final Pattern IMPORT = Pattern.compile("[A-Za-z_][A-Za-z0-9_]*(\\.[A-Za-z_][A-Za-z0-9_]*)*(\\.\\*)?");
    private static final String SNIPPETS = "/templates/maven/snippets/rest/";
    private static final Pattern CLASS_PATH = Pattern.compile("(?m)^@Path\\(\"([^\"]*)\"\\)\\s*$");
    // comments and string literals, which a client check must not read as code
    private static final Pattern COMMENTS_AND_STRINGS =
        Pattern.compile("//[^\\n]*|/\\*.*?\\*/|\"(?:\\\\.|[^\"\\\\])*\"|'(?:\\\\.|[^'\\\\])*'", Pattern.DOTALL);

    /**
     * Add an endpoint serving {@code method path} on {@code webService}, whose
     * method body is {@code body}.
     *
     * @param imports fully qualified types (or {@code pkg.*}) the body uses, added
     *        to the resource class; the {@code rest} package (clients and
     *        {@code RestJson}) is always imported
     */
    public static ChangeSet addEndpoint(Path appRoot, String webService, String method, String path,
                                        String body, List<String> imports, boolean dryRun) throws IOException {
        String httpMethod = normalizeMethod(method);
        List<String> pathParams = parsePath(path);
        Layout layout = requireWebService(appRoot, webService);
        String code = requireBody(body);
        List<String> importList = checkImports(imports);

        RestEndpointDef existing = RestEndpointIntrospector.getEndpoint(appRoot, webService, httpMethod, path);
        if (existing != null) {
            if (!existing.getBody().strip().equals(code.strip())) {
                throw new IllegalArgumentException(httpMethod + " " + path + " already exists on service "
                    + layout.webKebab + "; change it with update_rest_endpoint");
            }
            // the same body again may still bring imports a failed compile asked for
            String text = Files.readString(existing.getJavaFile());
            String withImports = addImports(text, importList);
            if (withImports.equals(text)) {
                return ChangeSet.noop(httpMethod + " " + path + " already exists on service " + layout.webKebab
                    + " with this body");
            }
            if (!dryRun) Files.writeString(existing.getJavaFile(), withImports);
            return ChangeSet.builder().addModified(existing.getJavaFile()).applied(!dryRun).build();
        }
        Map<Path, String> resources = readResources(layout);
        requireNoEquivalentRoute(layout, appRoot, webService, path, resources);
        requireClientsFor(appRoot, layout, code);

        Map<String, String> tokens = new HashMap<>();
        tokens.put("WebPackageName", layout.webPackage);
        tokens.put("EndpointPath", path);
        tokens.put("HttpMethod", httpMethod);
        tokens.put("JavaMethodName", httpMethod.toLowerCase());
        tokens.put("Parameters", parameters(httpMethod, pathParams));
        tokens.put("Body", indent(code));

        ChangeSet.Builder cs = ChangeSet.builder();
        Path resourceFile = findResourceFile(path, resources);
        String methodText = render("EndpointMethod.java", tokens);
        if (resourceFile == null) {
            resourceFile = layout.resourcesDir.resolve(uniqueResourceClassName(layout, path) + ".java");
            tokens.put("ResourceClassName", fileStem(resourceFile));
            String content = addImports(insertMethod(render("EndpointResource.java", tokens), methodText), importList);
            if (!dryRun) write(resourceFile, content);
            cs.addCreated(resourceFile);
        } else {
            String content = addImports(insertMethod(Files.readString(resourceFile), methodText), importList);
            if (!dryRun) Files.writeString(resourceFile, content);
            cs.addModified(resourceFile);
        }
        if (!Files.exists(layout.supportFile)) {
            if (!dryRun) write(layout.supportFile, render("RestJson.java", tokens));
            cs.addCreated(layout.supportFile);
        }
        return cs.applied(!dryRun).build();
    }

    /** Replace the body of the endpoint at {@code method path}, leaving its signature alone. */
    public static ChangeSet updateEndpoint(Path appRoot, String webService, String method, String path,
                                           String body, List<String> imports, boolean dryRun) throws IOException {
        String httpMethod = normalizeMethod(method);
        Layout layout = requireWebService(appRoot, webService);
        String code = requireBody(body);
        List<String> importList = checkImports(imports);
        RestEndpointDef existing = RestEndpointIntrospector.getEndpoint(appRoot, webService, httpMethod, path);
        if (existing == null) {
            throw new IllegalArgumentException("no " + httpMethod + " " + path + " endpoint on service "
                + layout.webKebab + "; add it with add_rest_endpoint");
        }
        requireClientsFor(appRoot, layout, code);
        List<String> lines = new ArrayList<>(Arrays.asList(Files.readString(existing.getJavaFile()).split("\n", -1)));
        int[] span = RestEndpointIntrospector.bodySpan(lines, httpMethod, path);
        lines.subList(span[0], span[1]).clear();
        lines.addAll(span[0], Arrays.asList(indent(code).split("\n", -1)));
        String content = addImports(String.join("\n", lines), importList);
        if (content.equals(Files.readString(existing.getJavaFile()))) {
            return ChangeSet.noop(httpMethod + " " + path + " already has this body");
        }
        if (!dryRun) Files.writeString(existing.getJavaFile(), content);
        return ChangeSet.builder().addModified(existing.getJavaFile()).applied(!dryRun).build();
    }

    /**
     * Remove the endpoint at {@code method path}, its class once empty, and
     * {@code RestJson} with the web service's last endpoint if nothing else uses
     * it. Service clients stay: they are removed with remove_service_client. No-op
     * when the endpoint is absent.
     */
    public static ChangeSet removeEndpoint(Path appRoot, String webService, String method, String path,
                                           boolean dryRun) throws IOException {
        String httpMethod = normalizeMethod(method);
        if (path == null || path.isBlank()) throw new IllegalArgumentException("path is required");
        Layout layout = requireWebService(appRoot, webService);
        RestEndpointDef endpoint = RestEndpointIntrospector.getEndpoint(appRoot, webService, httpMethod, path);
        if (endpoint == null) {
            return ChangeSet.noop("no " + httpMethod + " " + path + " endpoint on service " + layout.webKebab);
        }
        ChangeSet.Builder cs = ChangeSet.builder();
        List<String> lines = new ArrayList<>(Arrays.asList(Files.readString(endpoint.getJavaFile()).split("\n", -1)));
        int[] span = RestEndpointIntrospector.bodySpan(lines, httpMethod, path);
        int start = span[2];
        if (start > 0 && lines.get(start - 1).isBlank()) start--;
        lines.subList(start, span[3] + 1).clear();
        String remaining = String.join("\n", lines);
        if (countMarkers(remaining) == 0) {
            if (!dryRun) Files.delete(endpoint.getJavaFile());
            cs.addDeleted(endpoint.getJavaFile());
        } else {
            if (!dryRun) Files.writeString(endpoint.getJavaFile(), remaining);
            cs.addModified(endpoint.getJavaFile());
        }
        boolean othersLeft = RestEndpointIntrospector.listEndpoints(appRoot, webService).stream()
            .anyMatch(e -> !(e.getMethod().equals(httpMethod) && e.getPath().equals(path)));
        if (!othersLeft && Files.exists(layout.supportFile)) {
            List<String> users = ServiceClientEditor.usersOf(layout, "RestJson", layout.supportFile);
            users.remove(endpoint.getJavaFile().getFileName().toString());
            if (users.isEmpty()) {
                if (!dryRun) {
                    Files.delete(layout.supportFile);
                    deleteIfEmpty(layout.clientsDir);
                }
                cs.addDeleted(layout.supportFile);
            }
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
                if (JAVA_KEYWORDS.contains(m.group(1)) || !Character.isLowerCase(m.group(1).charAt(0))) {
                    throw new IllegalArgumentException("path parameter {" + m.group(1) + "} becomes a Java argument"
                        + " name, so it must start with a lowercase letter and not be a Java keyword; rename it");
                }
                if (RESERVED_PARAMS.contains(m.group(1))) {
                    throw new IllegalArgumentException("path parameter {" + m.group(1) + "} would clash with the"
                        + " method's own '" + m.group(1) + "' argument; rename it");
                }
                params.add(m.group(1));
            }
        }
        return params;
    }

    private static Layout requireWebService(Path appRoot, String webService) throws IOException {
        if (AppIntrospector.resolveServiceType(appRoot, webService) != ServiceBuilder.ServiceType.WEBSERVICE) {
            throw new IllegalArgumentException("service '" + webService + "' is not a webservice; REST endpoints are served by a webservice");
        }
        return Layout.of(appRoot, webService);
    }

    private static String requireBody(String body) {
        if (body == null || body.isBlank()) {
            throw new IllegalArgumentException("body is required: the Java code of the endpoint method, without its braces");
        }
        return body.strip();
    }

    private static List<String> checkImports(List<String> imports) {
        List<String> out = new ArrayList<>();
        if (imports == null) return out;
        for (String i : imports) {
            String t = i == null ? "" : i.trim().replaceFirst("^import\\s+", "").replaceFirst(";$", "").trim();
            if (!IMPORT.matcher(t).matches()) throw new IllegalArgumentException("not an import: '" + i + "'");
            out.add(t);
        }
        return out;
    }

    /**
     * Refuse a body that names another service's client before
     * {@code add_service_client} has added it. Client class names are
     * predictable ({@code <Service>Client}), so this is a plain text check; a
     * compile catches anything it misses.
     */
    private static void requireClientsFor(Path appRoot, Layout layout, String code) throws IOException {
        String codeOnly = COMMENTS_AND_STRINGS.matcher(code).replaceAll(" ");
        for (String name : ServiceIntrospector.listServiceNames(appRoot)) {
            String kebab = TokenUtils.toKebabCase(name);
            if (kebab.equals(layout.webKebab)) continue;
            String cls = layout.clientClassName(kebab);
            Path clientFile = layout.clientFile(kebab);
            if (Pattern.compile("\\b" + Pattern.quote(cls) + "\\b").matcher(codeOnly).find()
                    && !(Files.exists(clientFile) && Files.readString(clientFile).contains(ServiceClientEditor.CLIENT_MARKER))) {
                throw new IllegalStateException("the body uses " + cls + ", but " + layout.webKebab
                    + " has no client for " + kebab + "; call add_service_client(" + layout.webKebab + ", "
                    + kebab + ") first");
            }
        }
    }

    /** A path with its parameter names erased: {@code /orders/{id}} and {@code /orders/{orderId}} are one route. */
    static String routeKey(String path) {
        return path.replaceAll("\\{[^}]*\\}", "{}");
    }

    /**
     * Refuse a path that JAX-RS would treat as the same route as one already
     * served, under different parameter names, whatever the method: each spelling
     * would get a root resource class of its own, and Jersey routes a request to
     * one of them without backtracking, so the other's methods answer 405 (or the
     * pair is rejected as ambiguous at startup). Covers endpoints added here and
     * hand-written resource classes whose class-level path is equivalent.
     */
    private static void requireNoEquivalentRoute(Layout layout, Path appRoot, String webService, String path,
                                                 Map<Path, String> resources) throws IOException {
        String key = routeKey(path);
        for (RestEndpointDef e : RestEndpointIntrospector.listEndpoints(appRoot, webService)) {
            if (!e.getPath().equals(path) && routeKey(e.getPath()).equals(key)) {
                throw new IllegalArgumentException(path + " is the same route as the existing " + e.getPath()
                    + " on service " + layout.webKebab + "; use that spelling");
            }
        }
        for (Map.Entry<Path, String> r : resources.entrySet()) {
            if (countMarkers(r.getValue()) > 0) continue;
            String classPath = classPath(r.getValue());
            if (classPath != null && routeKey(classPath).equals(key)) {
                throw new IllegalArgumentException(path + " is already served by the hand-written "
                    + r.getKey().getFileName() + " (@Path(\"" + classPath + "\"))");
            }
        }
    }

    /** The web service's resource classes and their text, read once per operation. */
    private static Map<Path, String> readResources(Layout layout) throws IOException {
        Map<Path, String> out = new java.util.TreeMap<>();
        if (!Files.isDirectory(layout.resourcesDir)) return out;
        try (Stream<Path> files = Files.list(layout.resourcesDir)) {
            for (Path file : (Iterable<Path>) files::iterator) {
                if (file.toString().endsWith(".java")) out.put(file, Files.readString(file));
            }
        }
        return out;
    }

    /** A class-level {@code @Path} value, or null. */
    private static String classPath(String text) {
        Matcher m = CLASS_PATH.matcher(text);
        return m.find() ? m.group(1) : null;
    }

    // --- generated Java -------------------------------------------------

    static String render(String snippet, Map<String, String> tokens) throws IOException {
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

    private static String parameters(String method, List<String> pathParams) {
        StringBuilder sb = new StringBuilder("@Context final UriInfo uri");
        for (String p : pathParams) {
            sb.append(", @PathParam(\"").append(p).append("\") final String ").append(p);
        }
        if (BODY_METHODS.contains(method)) sb.append(", final String body");
        return sb.toString();
    }

    private static String indent(String code) {
        StringBuilder sb = new StringBuilder();
        for (String line : code.split("\n", -1)) {
            if (sb.length() > 0) sb.append('\n');
            if (!line.isBlank()) sb.append(RestEndpointIntrospector.BODY_INDENT).append(line.stripTrailing());
        }
        return sb.toString();
    }

    private static String addImports(String classText, List<String> imports) {
        String text = classText;
        for (String i : imports) {
            String line = "import " + i + ";";
            if (text.contains(line)) continue;
            int last = text.lastIndexOf("\nimport ");
            int at = last < 0 ? text.indexOf('\n', text.indexOf("package ")) : text.indexOf('\n', last + 1);
            text = text.substring(0, at + 1) + line + "\n" + text.substring(at + 1);
        }
        return text;
    }

    /** The generated resource class already serving {@code path}, or null. */
    private static Path findResourceFile(String path, Map<Path, String> resources) {
        for (Map.Entry<Path, String> r : resources.entrySet()) {
            if (countMarkers(r.getValue()) > 0 && path.equals(classPath(r.getValue()))) return r.getKey();
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
        String body = classText.substring(0, close).stripTrailing();
        return body + "\n\n" + method.stripTrailing() + "\n" + classText.substring(close);
    }

    private static int countMarkers(String text) {
        return (int) text.lines().filter(l -> RestEndpointIntrospector.MARKER.matcher(l).matches()).count();
    }

    private static String fileStem(Path file) {
        String name = file.getFileName().toString();
        return name.substring(0, name.length() - ".java".length());
    }

    static void write(Path file, String content) throws IOException {
        Files.createDirectories(file.getParent());
        Files.writeString(file, content);
    }

    static void deleteIfEmpty(Path dir) throws IOException {
        if (Files.isDirectory(dir)) {
            try (Stream<Path> s = Files.list(dir)) {
                if (s.findAny().isEmpty()) Files.delete(dir);
            }
        }
    }
}
