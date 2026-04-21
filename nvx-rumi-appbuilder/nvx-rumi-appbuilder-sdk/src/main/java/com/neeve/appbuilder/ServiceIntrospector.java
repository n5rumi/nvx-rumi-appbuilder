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

import com.neeve.appbuilder.model.ServiceInfo;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Collections;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Set;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Read-only enumeration and roll-up over the services declared in a Rumi
 * app. Service names are discovered by parsing the parent POM's
 * {@code <modules>} list and stripping the parent-artifact-id prefix that
 * {@link ServiceBuilder} uses when it scaffolds a service module.
 *
 * <p>Each {@link ServiceInfo} result includes type, module path, and
 * rolled-up content from {@link MessageIntrospector} and
 * {@link StateIntrospector}. Handler list is a placeholder (empty) until
 * Phase C's {@code HandlerIntrospector} lands.
 */
public final class ServiceIntrospector {
    /** Matches {@code <module>foo-bar-service-name</module>} in the parent POM. */
    private static final Pattern MODULE_PATTERN =
        Pattern.compile("<module>\\s*([^<\\s]+)\\s*</module>");

    private ServiceIntrospector() {}

    /**
     * Return every service declared in the app, in parent-POM order. Built-in
     * modules (the ROE model module and the system module) are filtered out.
     */
    public static List<ServiceInfo> listServices(Path appRoot) throws IOException {
        List<String> names = listServiceNames(appRoot);
        List<ServiceInfo> out = new ArrayList<>(names.size());
        for (String name : names) {
            out.add(buildServiceInfo(appRoot, name));
        }
        return out;
    }

    /**
     * Return the named service, or {@code null} if no service with that name
     * is declared in the app's parent POM.
     */
    public static ServiceInfo getService(Path appRoot, String serviceName) throws IOException {
        List<String> names = listServiceNames(appRoot);
        for (String name : names) {
            if (name.equals(serviceName)) {
                return buildServiceInfo(appRoot, name);
            }
        }
        return null;
    }

    /**
     * Return service names only — cheap enumeration without reading each
     * service's model files. Names are the original user-facing kebab-case
     * names (e.g. {@code "order-processor"}), derived by stripping the
     * parent-artifact-id prefix from the Maven module name.
     */
    public static List<String> listServiceNames(Path appRoot) throws IOException {
        ApplicationBuilder.AppParams params = AppIntrospector.loadAppParams(appRoot);
        String parentArtifactId = params.getTokenMap().get(TokenUtils.toToken("ParentArtifactId"));
        String roeArtifactId = params.getTokenMap().get(TokenUtils.toToken("RoeArtifactId"));
        String systemArtifactId = params.getTokenMap().get(TokenUtils.toToken("SystemArtifactId"));
        String prefix = parentArtifactId + "-";

        // Built-in non-service modules
        Set<String> builtIns = new LinkedHashSet<>();
        builtIns.add(roeArtifactId);
        builtIns.add(systemArtifactId);

        Path parentPom = appRoot.resolve("pom.xml");
        if (!Files.exists(parentPom)) return Collections.emptyList();
        String pom = new String(Files.readAllBytes(parentPom), StandardCharsets.UTF_8);

        List<String> services = new ArrayList<>();
        Matcher m = MODULE_PATTERN.matcher(pom);
        while (m.find()) {
            String module = m.group(1);
            if (builtIns.contains(module)) continue;
            if (!module.startsWith(prefix)) continue;   // safety: only modules named after the parent prefix
            String serviceName = module.substring(prefix.length());
            services.add(serviceName);
        }
        return services;
    }

    // --- internal -----------------------------------------------------

    private static ServiceInfo buildServiceInfo(Path appRoot, String serviceName) throws IOException {
        Path moduleDir = AppIntrospector.resolveServiceModuleDir(appRoot, serviceName);
        ServiceBuilder.ServiceType type = AppIntrospector.resolveServiceType(appRoot, serviceName);
        return new ServiceInfo(
            serviceName,
            type,
            moduleDir,
            MessageIntrospector.listMessages(appRoot, serviceName),
            StateIntrospector.listStateEntities(appRoot, serviceName),
            StateIntrospector.listCollections(appRoot, serviceName),
            Collections.emptyList()  // handlers — populated by HandlerIntrospector in Phase C
        );
    }
}
