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

import com.neeve.appbuilder.AppIntrospector;
import com.neeve.appbuilder.ApplicationBuilder;
import com.neeve.appbuilder.rest.dto.AppParamsRequest;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;
import jakarta.ws.rs.Consumes;
import jakarta.ws.rs.GET;
import jakarta.ws.rs.POST;
import jakarta.ws.rs.Path;
import jakarta.ws.rs.Produces;
import jakarta.ws.rs.QueryParam;
import jakarta.ws.rs.core.MediaType;

import java.io.IOException;
import java.util.List;
import java.util.Map;

/**
 * App-level endpoints: create, list, metadata.
 *
 * <p>All endpoints that operate on an existing app take {@code app_root}
 * as a query parameter — filesystem paths don't fit cleanly as JAX-RS
 * path segments.
 */
@Path("/v1/apps")
@Produces(MediaType.APPLICATION_JSON)
@Tag(name = "Apps", description = "Rumi app scaffolding and metadata")
public class Apps extends AbstractResource {

    @GET
    @Operation(summary = "List Rumi apps under a parent directory",
               description = "Scans the parent directory for scaffolded Rumi apps and returns their root paths.")
    public List<java.nio.file.Path> list(@QueryParam("under") String under) throws IOException {
        java.nio.file.Path base = requireAbsolutePath("under", under);
        return AppIntrospector.listRumiApps(base);
    }

    @POST
    @Consumes(MediaType.APPLICATION_JSON)
    @Operation(summary = "Scaffold a new Rumi app",
               description = "Creates a new Rumi app from the supplied parameters. Returns the resolved AppParams with derived fields (appTokenName, appRoot, tokenMap) populated so the caller knows where the app landed.")
    public ApplicationBuilder.AppParams create(AppParamsRequest req) throws IOException {
        if (req == null) throw new IllegalArgumentException("request body is required");
        ApplicationBuilder.AppParams params = req.toSdk();
        try {
            new ApplicationBuilder().createApplication(params);
        } catch (IOException | RuntimeException e) {
            throw e;
        } catch (Exception e) {
            throw new IOException(e);
        }
        return params;
    }

    @GET
    @Path("/info")
    @Operation(summary = "Get app metadata",
               description = "Returns the AppParams for an existing scaffolded app (package, group ID, rumi version, encoding type, etc.).")
    public ApplicationBuilder.AppParams info(@QueryParam("app_root") String appRoot) throws IOException {
        return AppIntrospector.loadAppParams(requireAbsoluteAppRoot(appRoot));
    }
}
