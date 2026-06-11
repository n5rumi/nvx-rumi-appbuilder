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

import com.neeve.appbuilder.ApiIntrospector;
import com.neeve.appbuilder.ApiOperationEditor;
import com.neeve.appbuilder.model.ApiOperationDef;
import com.neeve.appbuilder.model.ChangeSet;
import com.neeve.appbuilder.rest.dto.AddOperationRequest;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;
import jakarta.ws.rs.Consumes;
import jakarta.ws.rs.DELETE;
import jakarta.ws.rs.DefaultValue;
import jakarta.ws.rs.GET;
import jakarta.ws.rs.NotFoundException;
import jakarta.ws.rs.POST;
import jakarta.ws.rs.Path;
import jakarta.ws.rs.PathParam;
import jakarta.ws.rs.Produces;
import jakarta.ws.rs.QueryParam;
import jakarta.ws.rs.core.MediaType;

import java.io.IOException;
import java.util.List;

/**
 * Request-reply API operations in a service's api.xml. Each operation pairs a
 * request message with a response message and becomes a generated client method.
 */
@Path("/v1/services/{svc}/operations")
@Produces(MediaType.APPLICATION_JSON)
@Tag(name = "Operations", description = "Request-reply API operations (api.xml)")
public class Operations extends AbstractResource {

    @GET
    @Operation(summary = "List API operations on a service")
    public List<ApiOperationDef> list(@QueryParam("app_root") String appRoot,
                                      @PathParam("svc") String service) throws IOException {
        return ApiIntrospector.listOperations(requireAbsoluteAppRoot(appRoot), service);
    }

    @GET
    @Path("/{name}")
    @Operation(summary = "Get a single API operation")
    public ApiOperationDef get(@QueryParam("app_root") String appRoot,
                               @PathParam("svc") String service,
                               @PathParam("name") String name) throws IOException {
        ApiOperationDef op = ApiIntrospector.getOperation(requireAbsoluteAppRoot(appRoot), service, name);
        if (op == null) throw new NotFoundException("operation not found: " + name);
        return op;
    }

    @POST
    @Consumes(MediaType.APPLICATION_JSON)
    @Operation(summary = "Add an API operation",
               description = "Pairs inMessage (request) with outMessage (response); both must be known messages. Idempotent on name.")
    public ChangeSet add(@QueryParam("app_root") String appRoot,
                         @PathParam("svc") String service,
                         @QueryParam("dry_run") @DefaultValue("false") boolean dryRun,
                         AddOperationRequest req) throws IOException {
        if (req == null || req.getName() == null || req.getName().isBlank()) {
            throw new IllegalArgumentException("operation name is required");
        }
        return ApiOperationEditor.addOperation(requireAbsoluteAppRoot(appRoot), service,
            req.getName(), req.getInMessage(), req.getOutMessage(), req.getRestPath(), req.getRestMethod(), dryRun);
    }

    @DELETE
    @Path("/{name}")
    @Operation(summary = "Remove an API operation")
    public ChangeSet remove(@QueryParam("app_root") String appRoot,
                            @PathParam("svc") String service,
                            @PathParam("name") String name,
                            @QueryParam("dry_run") @DefaultValue("false") boolean dryRun) throws IOException {
        return ApiOperationEditor.removeOperation(requireAbsoluteAppRoot(appRoot), service, name, dryRun);
    }

    @POST
    @Path("/{name}/rename")
    @Operation(summary = "Rename an API operation")
    public ChangeSet rename(@QueryParam("app_root") String appRoot,
                            @PathParam("svc") String service,
                            @PathParam("name") String name,
                            @QueryParam("to") String to,
                            @QueryParam("dry_run") @DefaultValue("false") boolean dryRun) throws IOException {
        return ApiOperationEditor.renameOperation(requireAbsoluteAppRoot(appRoot), service, name, to, dryRun);
    }
}
