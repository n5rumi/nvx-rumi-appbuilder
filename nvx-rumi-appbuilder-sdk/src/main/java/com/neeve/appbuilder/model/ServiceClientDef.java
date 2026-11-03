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
import java.util.List;
import java.util.Objects;

/**
 * A generated client a web service holds for request-reply with another
 * service, added by {@link com.neeve.appbuilder.ServiceClientEditor}. An endpoint
 * body calls it as {@code <className>.get().<operation>(request)}.
 */
public final class ServiceClientDef {
    private final String webService;
    private final String service;
    private final String engine;
    private final String className;
    private final Path javaFile;
    private final List<String> methods;

    public ServiceClientDef(String webService, String service, String engine, String className,
                            Path javaFile, List<String> methods) {
        this.webService = Objects.requireNonNull(webService, "webService");
        this.service = Objects.requireNonNull(service, "service");
        this.engine = engine;
        this.className = Objects.requireNonNull(className, "className");
        this.javaFile = javaFile;
        this.methods = List.copyOf(methods);
    }

    /** The web service that holds the client. */
    public String getWebService() {
        return webService;
    }

    /** The service the client calls. */
    public String getService() {
        return service;
    }

    /** The engine the client calls, {@code <service>-<partition>}, which keys its requests. */
    public String getEngine() {
        return engine;
    }

    /** The fully qualified client class. */
    public String getClassName() {
        return className;
    }

    /** The client's source file. */
    public Path getJavaFile() {
        return javaFile;
    }

    /** One entry per operation, e.g. {@code lookupOrder(LookupOrderRequest) -> LookupOrderReply}. */
    public List<String> getMethods() {
        return methods;
    }

    @Override
    public String toString() {
        return webService + " -> " + service + " (" + className + ")";
    }
}
