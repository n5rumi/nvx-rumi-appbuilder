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

import com.neeve.aep.AepEngine;
import com.neeve.aep.annotations.EventHandler;
import com.neeve.aep.event.AepEngineStoppedEvent;
import com.neeve.aep.event.AepMessagingPrestartEvent;
import com.neeve.config.Config;
import com.neeve.server.app.annotations.AppInjectionPoint;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * Rumi App Builder REST — Rumi AEP app entry point.
 *
 * <p>This class is loaded and invoked by Rumi's standalone launch
 * machinery (via {@code conf/config.xml}'s {@code mainClass} attribute).
 * The AEP framework injects engine references through
 * {@link AppInjectionPoint}-annotated setters and drives lifecycle via
 * {@link EventHandler}-annotated methods.
 *
 * <p>The HTTP server starts on {@link AepMessagingPrestartEvent} and
 * stops on {@link AepEngineStoppedEvent}.
 *
 * <p>Configuration is sourced via {@link Config#getValue} — so every
 * operator-tunable knob (port, host) comes from the Rumi config file
 * configured at launch time.
 */
public final class Main {
    private static final Logger LOG = LoggerFactory.getLogger(Main.class);

    private final HttpServer _httpServer;
    private AepEngine _engine;

    public Main() {
        final String host = Config.getValue("appbuilder.rest.host", "0.0.0.0");
        final int port = Config.getValue("appbuilder.rest.port", 3200);
        _httpServer = new HttpServer(host, port, new ResourceConfig());
    }

    @AppInjectionPoint
    public void setAppEngine(final AepEngine engine) {
        _engine = engine;
    }

    @EventHandler
    public void onMessagingPrestart(final AepMessagingPrestartEvent event) {
        try {
            _httpServer.start();
            LOG.info("Rumi App Builder REST listening on http://{}:{}",
                _httpServer.getHost(), _httpServer.getPort());
        } catch (Exception e) {
            LOG.error("Could not start HTTP server", e);
            _engine.stop(e);
        }
    }

    @EventHandler
    public void onEngineStopped(final AepEngineStoppedEvent event) {
        try {
            _httpServer.stop();
        } catch (Exception e) {
            LOG.warn("Could not stop HTTP server cleanly: {}", e.getMessage());
        }
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
            register(org.glassfish.jersey.jackson.JacksonFeature.class);
            register(JacksonConfig.class);

            // Resources.
            register(com.neeve.appbuilder.rest.resources.Health.class);
            register(com.neeve.appbuilder.rest.resources.Apps.class);
            register(com.neeve.appbuilder.rest.resources.Services.class);
            register(com.neeve.appbuilder.rest.resources.Handlers.class);
            register(com.neeve.appbuilder.rest.resources.Messages.class);
            register(com.neeve.appbuilder.rest.resources.StateEntities.class);
            register(com.neeve.appbuilder.rest.resources.Config.class);
            register(com.neeve.appbuilder.rest.resources.FactoryIds.class);

            // Swagger UI + OpenAPI YAML.
            register(com.neeve.appbuilder.rest.resources.SwaggerUI.class);

            // Central exception -> HTTP status mapping.
            register(com.neeve.appbuilder.rest.mappers.ExceptionMapper.class);
        }
    }
}
