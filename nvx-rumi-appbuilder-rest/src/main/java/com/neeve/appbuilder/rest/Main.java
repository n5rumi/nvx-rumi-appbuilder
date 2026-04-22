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
package com.neeve.appbuilder.rest;

import com.neeve.appbuilder.rest.mappers.ExceptionMapper;
import com.neeve.appbuilder.rest.resources.Apps;
import com.neeve.appbuilder.rest.resources.Config;
import com.neeve.appbuilder.rest.resources.FactoryIds;
import com.neeve.appbuilder.rest.resources.Handlers;
import com.neeve.appbuilder.rest.resources.Health;
import com.neeve.appbuilder.rest.resources.Messages;
import com.neeve.appbuilder.rest.resources.Services;
import com.neeve.appbuilder.rest.resources.StateEntities;
import com.neeve.appbuilder.rest.resources.SwaggerUI;
import org.glassfish.jersey.jackson.JacksonFeature;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * Entry point for the Rumi App Builder REST service.
 *
 * <p>Starts an embedded Jetty 12 HTTP server hosting the Jersey 3
 * {@link ResourceConfig}. Runs as a plain Java process in v1 (no AEP
 * lifecycle, no Rumi Management hooks — those land when the packaging
 * story in RUMI-304 requires them).
 *
 * <p>Configuration is via environment variables so the deployed
 * systemd/AMI story can control behaviour without a config file:
 *
 * <ul>
 *   <li>{@code RUMI_APPBUILDER_REST_PORT} — HTTP port, default {@code 3200}
 *   <li>{@code RUMI_APPBUILDER_REST_HOST} — bind host, default {@code 0.0.0.0}
 * </ul>
 */
public final class Main {
    private static final Logger LOG = LoggerFactory.getLogger(Main.class);

    private Main() {}

    public static void main(String[] args) throws Exception {
        int port = intEnv("RUMI_APPBUILDER_REST_PORT", 3200);
        String host = env("RUMI_APPBUILDER_REST_HOST", "0.0.0.0");

        HttpServer server = new HttpServer(host, port, new ResourceConfig());
        server.start();
        LOG.info("Rumi App Builder REST listening on http://{}:{}", host, port);

        Runtime.getRuntime().addShutdownHook(new Thread(() -> {
            try { server.stop(); } catch (Exception e) {
                LOG.warn("Could not stop HTTP server cleanly: {}", e.getMessage());
            }
        }, "appbuilder-rest-shutdown"));

        server.join();
    }

    private static int intEnv(String name, int defaultValue) {
        String raw = System.getenv(name);
        if (raw == null || raw.isBlank()) return defaultValue;
        try { return Integer.parseInt(raw.trim()); }
        catch (NumberFormatException e) {
            LOG.warn("Env {} is not a valid int ({}); using default {}", name, raw, defaultValue);
            return defaultValue;
        }
    }

    private static String env(String name, String defaultValue) {
        String raw = System.getenv(name);
        return (raw == null || raw.isBlank()) ? defaultValue : raw;
    }

    /**
     * Jersey resource config for the App Builder REST service.
     *
     * <p>Resources are registered explicitly (not via package scan) so the
     * API surface is visible in one place and easy to audit.
     *
     * <p>The OpenAPI spec's global metadata lives on
     * {@code AbstractResource} via {@code @OpenAPIDefinition}. The
     * {@code swagger-maven-plugin-jakarta} picks it up during the
     * {@code resolve} goal and emits {@code META-INF/openAPI/appbuilder-api.yaml}.
     * {@code SwaggerUI} serves that YAML at runtime at {@code /openapi}
     * and the Swagger UI page at {@code /swagger}.
     */
    public static final class ResourceConfig extends org.glassfish.jersey.server.ResourceConfig {
        public ResourceConfig() {
            register(JacksonFeature.class);
            register(JacksonConfig.class);

            // Resources.
            register(Health.class);
            register(Apps.class);
            register(Services.class);
            register(Handlers.class);
            register(Messages.class);
            register(StateEntities.class);
            register(Config.class);
            register(FactoryIds.class);

            // Swagger UI + OpenAPI YAML (Datafye/Paywhere pattern).
            register(SwaggerUI.class);

            // Central exception -> HTTP status mapping (Paywhere-style envelope).
            register(ExceptionMapper.class);
        }
    }
}
