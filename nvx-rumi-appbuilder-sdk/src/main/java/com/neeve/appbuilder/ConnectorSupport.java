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

import java.nio.file.Path;
import java.util.Map;

/**
 * Internal derivation helpers shared by {@link ConnectorIntrospector} and
 * {@link ConnectorEditor}: config path, the service's full name and connector
 * package, descriptor parsing, and the connector-name convention.
 *
 * <p>The connector wiring conventions are:
 *
 * <ul>
 *   <li>class FQCN: {@code <appPkg>.<servicePkg>.connector.<ConnectorClass>}
 *   <li>bus name: {@code <ServiceName>-<connectorKebab>} (unique per service)
 *   <li>inbound channel: {@code in}
 * </ul>
 */
final class ConnectorSupport {
    private ConnectorSupport() {}

    static final String INBOUND_CHANNEL = "in";

    /** Path to the system module's config.xml. */
    static Path configPath(Path appRoot, Map<String, String> tokens) {
        return appRoot
                .resolve(tokens.get(TokenUtils.toToken("SystemArtifactId")))
                .resolve("conf/config.xml");
    }

    /** The service's full name ({@code AppTokenName-serviceKebab}), as used in config. */
    static String serviceFullName(Map<String, String> tokens, String serviceKebab) {
        return tokens.get(TokenUtils.toToken("AppTokenName")) + "-" + serviceKebab;
    }

    /** The dotted package the service's connector classes live in. */
    static String connectorPackage(String appPackageName, String serviceKebab) {
        return appPackageName + "." + TokenUtils.toPackagePath(serviceKebab) + ".connector";
    }

    /** The connector bus name for a given service + connector. */
    static String busName(String serviceFullName, String connectorKebab) {
        return serviceFullName + "-" + connectorKebab;
    }

    /** The connector bus descriptor wiring the class to the bus. */
    static String descriptor(String className, String inboundChannel) {
        return "connector://.&classname=" + className + "&inbound_channel=" + inboundChannel;
    }

    /** Extract a {@code key=value} parameter from a connector descriptor; null if absent. */
    static String descriptorParam(String descriptor, String key) {
        for (String part : descriptor.split("&")) {
            int eq = part.indexOf('=');
            if (eq > 0 && part.substring(0, eq).equals(key)) {
                return part.substring(eq + 1);
            }
        }
        return null;
    }

    /**
     * Recover the logical connector name from a bus name. Connectors created
     * by {@link ConnectorEditor} use {@code <ServiceName>-<connectorKebab>};
     * the connector <em>service</em> template's built-in connector uses the
     * service name itself, in which case we fall back to the class simple name.
     */
    static String connectorNameFromBus(String busName, String serviceFullName, String className) {
        String prefix = serviceFullName + "-";
        if (busName != null && busName.startsWith(prefix)) {
            return busName.substring(prefix.length());
        }
        String simple = className.substring(className.lastIndexOf('.') + 1);
        return TokenUtils.toKebabCase(simple);
    }
}
