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

import com.neeve.appbuilder.model.RestEndpointDef;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Map;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import java.util.stream.Collectors;
import java.util.stream.Stream;

/**
 * Read-only introspection over the REST endpoints {@link RestEndpointEditor}
 * generated into a web service. An endpoint is found by the marker comment the
 * editor writes above its method, in a resource class under the web service's
 * {@code resources} package, so endpoints written by hand are not listed.
 */
public final class RestEndpointIntrospector {
    private RestEndpointIntrospector() {}

    /** {@code // @rest-endpoint GET /contracts/{id} -> orders.getContract} */
    static final Pattern MARKER = Pattern.compile(
        "^\\s*// @rest-endpoint (GET|POST|PUT|PATCH|DELETE) (\\S+) -> ([a-z0-9-]+)\\.(\\S+)\\s*$");
    static final String END_MARKER = "// @rest-endpoint-end";

    /** Every generated endpoint on the web service, ordered by path and then method. */
    public static List<RestEndpointDef> listEndpoints(Path appRoot, String webService) throws IOException {
        Layout layout = Layout.of(appRoot, webService);
        if (!Files.isDirectory(layout.resourcesDir)) return Collections.emptyList();
        List<Path> files;
        try (Stream<Path> s = Files.list(layout.resourcesDir)) {
            files = s.filter(p -> p.toString().endsWith(".java")).sorted().collect(Collectors.toList());
        }
        List<RestEndpointDef> out = new ArrayList<>();
        for (Path file : files) {
            for (String line : Files.readAllLines(file)) {
                Matcher m = MARKER.matcher(line);
                if (m.matches()) {
                    out.add(new RestEndpointDef(layout.webKebab, m.group(1), m.group(2), m.group(3), m.group(4), file));
                }
            }
        }
        out.sort((a, b) -> a.getPath().equals(b.getPath())
            ? a.getMethod().compareTo(b.getMethod()) : a.getPath().compareTo(b.getPath()));
        return out;
    }

    /** The endpoint with this method and path, or null if the web service has none. */
    public static RestEndpointDef getEndpoint(Path appRoot, String webService, String method, String path)
            throws IOException {
        String m = method == null ? null : method.trim().toUpperCase();
        for (RestEndpointDef e : listEndpoints(appRoot, webService)) {
            if (e.getMethod().equals(m) && e.getPath().equals(path)) return e;
        }
        return null;
    }

    /** Every generated endpoint, in any web service of the app, that calls {@code service}. */
    public static List<RestEndpointDef> listCallers(Path appRoot, String service) throws IOException {
        String kebab = TokenUtils.toKebabCase(service);
        List<RestEndpointDef> out = new ArrayList<>();
        for (String name : ServiceIntrospector.listServiceNames(appRoot)) {
            if (!Files.isDirectory(AppIntrospector.resolveServiceModuleDir(appRoot, name))) continue;
            if (AppIntrospector.resolveServiceType(appRoot, name) != ServiceBuilder.ServiceType.WEBSERVICE) continue;
            for (RestEndpointDef e : listEndpoints(appRoot, name)) {
                if (e.getService().equals(kebab)) out.add(e);
            }
        }
        return out;
    }

    /** Where a web service's generated REST code lives, and the names it uses. */
    static final class Layout {
        final Map<String, String> tokens;
        final String webKebab;
        final String webFullName;
        final String webPackage;
        final Path webModuleDir;
        final Path webSourceDir;
        final Path supportFile;
        final Path resourcesDir;
        final Path clientsDir;

        private Layout(Map<String, String> tokens, String webKebab, Path webModuleDir, Path webSourceDir) {
            this.tokens = tokens;
            this.webKebab = webKebab;
            this.webFullName = ConnectorSupport.serviceFullName(tokens, webKebab);
            this.webPackage = tokens.get(TokenUtils.toToken("AppPackageName")) + "." + TokenUtils.toPackagePath(webKebab);
            this.webModuleDir = webModuleDir;
            this.webSourceDir = webSourceDir;
            // the generated support and client classes get a package of their own,
            // so they cannot collide with classes the developer wrote, such as a
            // hand-written clients.TenancyClient
            this.supportFile = webSourceDir.resolve("rest").resolve("RestEndpointSupport.java");
            this.resourcesDir = webSourceDir.resolve("resources");
            this.clientsDir = webSourceDir.resolve("rest");
        }

        static Layout of(Path appRoot, String webService) throws IOException {
            Map<String, String> tokens = AppIntrospector.loadAppParams(appRoot).getTokenMap();
            return new Layout(tokens,
                TokenUtils.toKebabCase(webService),
                AppIntrospector.resolveServiceModuleDir(appRoot, webService),
                AppIntrospector.resolveMainJavaFile(appRoot, webService).getParent());
        }

        String clientClassName(String targetKebab) {
            return TokenUtils.toPascalCase(targetKebab) + "Client";
        }

        /**
         * The client name the generated clients use, distinct from the web
         * service's own name so its configuration never lands on, or is removed
         * from, a client the developer configured for themselves.
         */
        String clientName() {
            return webFullName + "-rest";
        }

        Path clientFile(String targetKebab) {
            return clientsDir.resolve(clientClassName(targetKebab) + ".java");
        }
    }
}
