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
import com.neeve.appbuilder.model.ServiceClientDef;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import java.util.stream.Collectors;
import java.util.stream.Stream;

/**
 * Read-only introspection over the service clients {@link ServiceClientEditor}
 * added to web services. A client is a class in the web service's {@code rest}
 * package carrying the generated marker; its methods come from the target
 * service's api.xml, which is what its generated {@code AbstractClient} is built
 * from.
 */
public final class ServiceClientIntrospector {
    private ServiceClientIntrospector() {}

    private static final Pattern TARGET = Pattern.compile("TARGET_SERVICE = \"([^\"]+)\"");

    /** Every client the web service holds, ordered by target service. */
    public static List<ServiceClientDef> listClients(Path appRoot, String webService) throws IOException {
        Layout layout = Layout.of(appRoot, webService);
        if (!Files.isDirectory(layout.clientsDir)) return Collections.emptyList();
        List<Path> files;
        try (Stream<Path> s = Files.list(layout.clientsDir)) {
            files = s.filter(p -> p.toString().endsWith("Client.java")).sorted().collect(Collectors.toList());
        }
        List<ServiceClientDef> out = new ArrayList<>();
        for (Path file : files) {
            String text = Files.readString(file);
            if (!text.contains(ServiceClientEditor.CLIENT_MARKER)) continue;
            Matcher m = TARGET.matcher(text);
            if (!m.find()) continue;
            String target = m.group(1);
            String simple = file.getFileName().toString().replace(".java", "");
            out.add(new ServiceClientDef(layout.webKebab, target, ServiceClientEditor.generatedEngineName(file),
                layout.webPackage + ".rest." + simple, file, methods(appRoot, target)));
        }
        return out;
    }

    /** The web service's client for {@code service}, or null. */
    public static ServiceClientDef getClient(Path appRoot, String webService, String service) throws IOException {
        String kebab = TokenUtils.toKebabCase(service);
        for (ServiceClientDef c : listClients(appRoot, webService)) {
            if (c.getService().equals(kebab)) return c;
        }
        return null;
    }

    /**
     * Every client, in any web service of the app, for {@code service}. Reads
     * only each client's target constant, not the target's api.xml, so the
     * records carry no methods.
     */
    public static List<ServiceClientDef> listClientsOf(Path appRoot, String service) throws IOException {
        String kebab = TokenUtils.toKebabCase(service);
        List<ServiceClientDef> out = new ArrayList<>();
        for (String name : ServiceIntrospector.listServiceNames(appRoot)) {
            if (!Files.isDirectory(AppIntrospector.resolveServiceModuleDir(appRoot, name))) continue;
            if (AppIntrospector.resolveServiceType(appRoot, name) != ServiceBuilder.ServiceType.WEBSERVICE) continue;
            Layout layout = Layout.of(appRoot, name);
            Path file = layout.clientFile(kebab);
            if (!Files.exists(file)) continue;
            String text = Files.readString(file);
            Matcher m = TARGET.matcher(text);
            if (text.contains(ServiceClientEditor.CLIENT_MARKER) && m.find() && m.group(1).equals(kebab)) {
                out.add(new ServiceClientDef(layout.webKebab, kebab, ServiceClientEditor.generatedEngineName(file),
                    layout.webPackage + ".rest." + layout.clientClassName(kebab), file, List.of()));
            }
        }
        return out;
    }

    /**
     * The client methods {@code service}'s api.xml gives, e.g.
     * {@code lookupOrder(LookupOrderRequest) -> LookupOrderReply}. Each also has a
     * {@code (request, timeoutSeconds)} overload and an {@code Async} form.
     */
    static List<String> methods(Path appRoot, String service) throws IOException {
        List<String> out = new ArrayList<>();
        if (!Files.exists(AppIntrospector.resolveApiXmlFile(appRoot, service))) return out;
        for (ApiOperationDef op : ApiIntrospector.listOperations(appRoot, service)) {
            String name = Character.toLowerCase(op.getName().charAt(0)) + op.getName().substring(1);
            out.add(name + "(" + op.getInMessage() + ") -> " + op.getOutMessage());
        }
        return out;
    }
}
