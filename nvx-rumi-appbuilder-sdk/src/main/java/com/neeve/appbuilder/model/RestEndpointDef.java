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
 * A REST endpoint added to a web service by
 * {@link com.neeve.appbuilder.RestEndpointEditor}: an HTTP method and path whose
 * body is the developer's code.
 */
public final class RestEndpointDef {
    private final String webService;
    private final String method;
    private final String path;
    private final Path javaFile;
    private final String body;

    public RestEndpointDef(String webService, String method, String path, Path javaFile, String body) {
        this.webService = Objects.requireNonNull(webService, "webService");
        this.method = Objects.requireNonNull(method, "method");
        this.path = Objects.requireNonNull(path, "path");
        this.javaFile = javaFile;
        this.body = body;
    }

    /** The web service that serves the endpoint. */
    public String getWebService() {
        return webService;
    }

    /** The HTTP method: GET, POST, PUT, PATCH or DELETE. */
    public String getMethod() {
        return method;
    }

    /** The endpoint path, as a JAX-RS template such as {@code /contracts/{id}}. */
    public String getPath() {
        return path;
    }

    /** The resource class that holds the endpoint. */
    public Path getJavaFile() {
        return javaFile;
    }

    /** The method body, verbatim and without its braces; null in a listing. */
    public String getBody() {
        return body;
    }

    @Override
    public String toString() {
        return method + " " + path;
    }
}
