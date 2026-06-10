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
package com.neeve.appbuilder.model;

import java.nio.file.Path;
import java.util.Objects;

/**
 * A connector snapped into a service: a user-authored Rumi message-bus
 * binding (a class implementing {@code com.neeve.sma.spi.connector.Connector})
 * wired via a {@code connector://} bus binding in {@code config.xml}.
 *
 * <p>A connector is made up of three cohesive artifacts, all captured here:
 *
 * <ul>
 *   <li>a Java class under the service's {@code connector} subpackage
 *       ({@link #getJavaFile()} / {@link #getClassName()}),
 *   <li>a {@code <bus descriptor="connector://...">} binding in the system
 *       {@code config.xml} ({@link #getBusName()}, {@link #getInboundChannel()},
 *       {@link #getDescriptor()}),
 *   <li>a {@code <bus name="..."/>} reference in the owning app's
 *       {@code <messaging>} block.
 * </ul>
 */
public final class ConnectorDef {
    private final String name;
    private final String className;
    private final String busName;
    private final String inboundChannel;
    private final String descriptor;
    private final Path javaFile;

    public ConnectorDef(String name,
                        String className,
                        String busName,
                        String inboundChannel,
                        String descriptor,
                        Path javaFile) {
        this.name = Objects.requireNonNull(name, "name");
        this.className = Objects.requireNonNull(className, "className");
        this.busName = Objects.requireNonNull(busName, "busName");
        this.inboundChannel = inboundChannel;
        this.descriptor = descriptor;
        this.javaFile = javaFile;
    }

    /** Logical connector name as used by add/remove (kebab-cased). */
    public String getName() {
        return name;
    }

    /** Fully-qualified name of the {@code Connector} implementation class. */
    public String getClassName() {
        return className;
    }

    /** Name of the {@code connector://} bus this connector is bound to. */
    public String getBusName() {
        return busName;
    }

    /** Channel the connector publishes inbound messages on. May be null. */
    public String getInboundChannel() {
        return inboundChannel;
    }

    /** Full bus descriptor string ({@code connector://...&classname=...}). */
    public String getDescriptor() {
        return descriptor;
    }

    /** Path to the connector's Java source file. May be null when introspected purely from config. */
    public Path getJavaFile() {
        return javaFile;
    }

    @Override
    public String toString() {
        return "ConnectorDef{name=" + name + ", class=" + className + ", bus=" + busName + "}";
    }
}
