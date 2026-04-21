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

import com.neeve.appbuilder.rest.mappers.RestExceptionMapper;
import com.neeve.appbuilder.rest.resources.HealthResource;
import org.glassfish.jersey.jackson.JacksonFeature;
import org.glassfish.jersey.server.ResourceConfig;

/**
 * Jersey {@link ResourceConfig} for the Rumi App Builder REST service.
 *
 * <p>Resources are registered explicitly (rather than via package scan)
 * so the API surface is visible in one place and easy to audit. The DTO,
 * editor, and introspector endpoints land under RUMI-301; for the
 * RUMI-300 scaffold only the health endpoint is wired.
 */
public class AppBuilderResourceConfig extends ResourceConfig {
    public AppBuilderResourceConfig() {
        register(JacksonFeature.class);

        // Resources — one explicit registration per surface.
        register(HealthResource.class);

        // Exception mapping — centralised per the Paywhere-style pattern.
        register(RestExceptionMapper.class);
    }
}
