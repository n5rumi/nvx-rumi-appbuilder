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

import com.neeve.appbuilder.RestEndpointEditor;
import com.neeve.appbuilder.RestEndpointIntrospector;
import com.neeve.appbuilder.model.ChangeSet;
import com.neeve.appbuilder.model.RestEndpointDef;
import com.neeve.appbuilder.rest.dto.AddRestEndpointRequest;
import com.neeve.appbuilder.rest.dto.UpdateRestEndpointRequest;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.Parameter;
import io.swagger.v3.oas.annotations.enums.ParameterIn;
import io.swagger.v3.oas.annotations.tags.Tag;
import jakarta.ws.rs.Consumes;
import jakarta.ws.rs.DELETE;
import jakarta.ws.rs.DefaultValue;
import jakarta.ws.rs.GET;
import jakarta.ws.rs.NotFoundException;
import jakarta.ws.rs.POST;
import jakarta.ws.rs.PUT;
import jakarta.ws.rs.Path;
import jakarta.ws.rs.PathParam;
import jakarta.ws.rs.Produces;
import jakarta.ws.rs.QueryParam;
import jakarta.ws.rs.core.MediaType;
import java.io.IOException;
import java.util.List;

/**
 * REST endpoints on a web service. Each is an HTTP method and path whose body is
 * the caller's Java code, the way a message handler's is. An endpoint is
 * identified by its method and path; the path is a query parameter because it
 * contains slashes. A body that calls another service needs that service's
 * client first: see {@code /service-clients}.
 */
@Path("/v1/services/{svc}/rest-endpoints")
@Produces(MediaType.APPLICATION_JSON)
@Tag(name = "REST endpoints", description = "HTTP endpoints on a web service, whose bodies are the caller's code")
public class RestEndpoints extends AbstractResource {

    @GET
    @Operation(summary = "List the REST endpoints on a web service",
               description = "Returns every endpoint's method and path. Bodies are omitted; get one endpoint for its body.")
    public List<RestEndpointDef> list(@QueryParam("app_root") String appRoot,
                                      @PathParam("svc") String service) throws IOException {
        return RestEndpointIntrospector.listEndpoints(requireAbsoluteAppRoot(appRoot), service);
    }

    @GET
    @Path("/{method}")
    @Operation(summary = "Get a single REST endpoint",
               description = "Returns the endpoint with this method and path, with its body verbatim. 404 if the web service has none.")
    public RestEndpointDef get(@QueryParam("app_root") String appRoot,
                               @PathParam("svc") String service,
                               @PathParam("method") String method,
                               @QueryParam("path") String path) throws IOException {
        RestEndpointDef e = RestEndpointIntrospector.getEndpoint(requireAbsoluteAppRoot(appRoot), service, method, path);
        if (e == null) throw new NotFoundException("REST endpoint not found: " + method + " " + path);
        return e;
    }

    @POST
    @Consumes(MediaType.APPLICATION_JSON)
    @Operation(summary = "Add a REST endpoint to a web service",
               description = "Adds the JAX-RS method for method + path with the given body. A body that names another "
                           + "service's client is refused until add_service_client has added it. Idempotent on an "
                           + "identical body; supports dry_run.")
    @Parameter(in = ParameterIn.QUERY, name = "detail",
               description = "Return the full result: every key present and paths absolute. "
                           + "By default paths are relative to the app_root you supplied and "
                           + "empty collections are omitted (RUMI-414).")
    public ChangeSet add(@QueryParam("app_root") String appRoot,
                         @PathParam("svc") String service,
                         @QueryParam("dry_run") @DefaultValue("false") boolean dryRun,
                         AddRestEndpointRequest req) throws IOException {
        if (req == null) {
            throw new IllegalArgumentException("method, path and body are required");
        }
        return RestEndpointEditor.addEndpoint(requireAbsoluteAppRoot(appRoot), service,
            req.getMethod(), req.getPath(), req.getBody(), req.getImports(), dryRun);
    }

    @PUT
    @Path("/{method}")
    @Consumes(MediaType.APPLICATION_JSON)
    @Operation(summary = "Replace a REST endpoint's body",
               description = "Replaces the body of the endpoint with this method and path, leaving its signature alone. Supports dry_run.")
    @Parameter(in = ParameterIn.QUERY, name = "detail",
               description = "Return the full result: every key present and paths absolute. "
                           + "By default paths are relative to the app_root you supplied and "
                           + "empty collections are omitted (RUMI-414).")
    public ChangeSet update(@QueryParam("app_root") String appRoot,
                            @PathParam("svc") String service,
                            @PathParam("method") String method,
                            @QueryParam("path") String path,
                            @QueryParam("dry_run") @DefaultValue("false") boolean dryRun,
                            UpdateRestEndpointRequest req) throws IOException {
        if (req == null) throw new IllegalArgumentException("body is required");
        return RestEndpointEditor.updateEndpoint(requireAbsoluteAppRoot(appRoot), service, method, path,
            req.getBody(), req.getImports(), dryRun);
    }

    @DELETE
    @Path("/{method}")
    @Operation(summary = "Remove a REST endpoint from a web service",
               description = "Removes the endpoint. Service clients stay; remove them with remove_service_client. Supports dry_run.")
    @Parameter(in = ParameterIn.QUERY, name = "detail",
               description = "Return the full result: every key present and paths absolute. "
                           + "By default paths are relative to the app_root you supplied and "
                           + "empty collections are omitted (RUMI-414).")
    public ChangeSet remove(@QueryParam("app_root") String appRoot,
                            @PathParam("svc") String service,
                            @PathParam("method") String method,
                            @QueryParam("path") String path,
                            @QueryParam("dry_run") @DefaultValue("false") boolean dryRun) throws IOException {
        return RestEndpointEditor.removeEndpoint(requireAbsoluteAppRoot(appRoot), service, method, path, dryRun);
    }
}
