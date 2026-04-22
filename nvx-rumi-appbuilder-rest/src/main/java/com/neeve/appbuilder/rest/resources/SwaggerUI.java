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
package com.neeve.appbuilder.rest.resources;

import io.swagger.v3.oas.annotations.Hidden;
import jakarta.ws.rs.GET;
import jakarta.ws.rs.Path;
import jakarta.ws.rs.Produces;
import jakarta.ws.rs.core.Context;
import jakarta.ws.rs.core.Response;
import jakarta.ws.rs.core.UriInfo;

import java.io.IOException;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.util.Objects;

/**
 * Serves the Swagger UI at {@code /swagger} and the OpenAPI YAML spec
 * at {@code /openapi}. Pattern lifted verbatim from Datafye API REST
 * and Paywhere client API: the UI is a single HTML document with the
 * swagger-ui CSS + JS bundles inlined at request time, so no CDN
 * dependency and no external static-file handler.
 *
 * <p>{@code @Hidden} keeps these two endpoints out of the OpenAPI spec
 * itself (they're not part of the product API surface).
 */
@Path("/")
@Hidden
public final class SwaggerUI {

    @GET
    @Path("/swagger")
    @Produces("text/html")
    public String getSwaggerTestPage(@Context UriInfo uriInfo) throws IOException {
        SwaggerUIModel model = SwaggerUIModel.builder()
            .html(loadResource("/swagger-ui/index.html"))
            .css(loadResource("/swagger-ui/swagger-ui.css"))
            .jsBundle(loadResource("/swagger-ui/swagger-ui-bundle.js"))
            .jsPreset(loadResource("/swagger-ui/swagger-ui-standalone-preset.js"))
            .yaml(apiBaseUrl(uriInfo) + "/openapi")
            .build();
        return fillHtmlTemplate(model);
    }

    @GET
    @Path("/openapi")
    @Produces("application/yaml")
    public Response getOpenApiYaml(@Context UriInfo uriInfo) throws IOException {
        String yaml = loadResource("/META-INF/openAPI/appbuilder-api.yaml")
            .replace("{url}", apiBaseUrl(uriInfo));
        return Response.ok(yaml).build();
    }

    /**
     * Derive the public base URL from the incoming request. Matches
     * whatever scheme/host/port the caller used, so the Swagger UI's
     * "Try it" button hits the right server behind proxies or in
     * multi-host deployments without any config.
     */
    private static String apiBaseUrl(UriInfo uriInfo) {
        var base = uriInfo.getBaseUri();
        String authority = base.getScheme() + "://" + base.getAuthority();
        // Base URI includes the servlet root (typically /); strip trailing slash.
        if (authority.endsWith("/")) authority = authority.substring(0, authority.length() - 1);
        return authority;
    }

    private static String loadResource(String resourcePath) throws IOException {
        try (InputStream in = SwaggerUI.class.getResourceAsStream(resourcePath)) {
            Objects.requireNonNull(in, "classpath resource not found: " + resourcePath);
            return new String(in.readAllBytes(), StandardCharsets.UTF_8);
        }
    }

    private static String fillHtmlTemplate(SwaggerUIModel m) {
        return m.getHtml()
            .replace("{{OPENAPI_YAML_URL}}", m.getYaml())
            .replace("{{CSS_CONTENT}}", m.getCss())
            .replace("{{JS_BUNDLE_CONTENT}}", m.getJsBundle())
            .replace("{{JS_STANDALONE_PRESET_CONTENT}}", m.getJsPreset());
    }
}
