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

import com.neeve.appbuilder.FactoryIdCollector;
import jakarta.ws.rs.GET;
import jakarta.ws.rs.Path;
import jakarta.ws.rs.Produces;
import jakarta.ws.rs.QueryParam;
import jakarta.ws.rs.core.MediaType;

import java.io.IOException;
import java.util.Map;

/**
 * Factory-ID endpoints: list used IDs and get the next free one.
 */
@Path("/v1/factory-ids")
@Produces(MediaType.APPLICATION_JSON)
public class FactoryIds extends AbstractResource {

    /**
     * {@code GET /v1/factory-ids?app_root=...} — map of used ID to owner.
     */
    @GET
    public Map<Integer, String> list(@QueryParam("app_root") String appRoot) throws IOException {
        return FactoryIdCollector.listUsedIds(requireAbsoluteAppRoot(appRoot));
    }

    /**
     * {@code GET /v1/factory-ids/next?app_root=...} — lowest free ID. The
     * PROJECT.md catalog mentioned a {@code ?kind=} filter; dropped
     * because the SDK does not split factory IDs by kind today.
     */
    @GET
    @Path("/next")
    public Map<String, Integer> next(@QueryParam("app_root") String appRoot) throws IOException {
        return Map.of("nextAvailableId", FactoryIdCollector.nextAvailableId(requireAbsoluteAppRoot(appRoot)));
    }
}
