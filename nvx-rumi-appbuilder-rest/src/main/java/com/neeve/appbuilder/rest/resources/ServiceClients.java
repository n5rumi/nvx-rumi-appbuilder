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

import com.neeve.appbuilder.ServiceClientEditor;
import com.neeve.appbuilder.ServiceClientIntrospector;
import com.neeve.appbuilder.model.ChangeSet;
import com.neeve.appbuilder.model.ServiceClientDef;
import com.neeve.appbuilder.rest.dto.AddServiceClientRequest;
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
import jakarta.ws.rs.Path;
import jakarta.ws.rs.PathParam;
import jakarta.ws.rs.Produces;
import jakarta.ws.rs.QueryParam;
import jakarta.ws.rs.core.MediaType;
import java.io.IOException;
import java.util.List;

/**
 * Service clients held by a web service: what prepares it for request-reply with
 * another service, the prerequisite for a REST endpoint body that calls that
 * service.
 */
@Path("/v1/services/{svc}/service-clients")
@Produces(MediaType.APPLICATION_JSON)
@Tag(name = "Service clients", description = "Prepare a web service for request-reply with another service")
public class ServiceClients extends AbstractResource {

    @GET
    @Operation(summary = "List a web service's service clients",
               description = "Returns every client: the service it calls, its engine, its class, and the methods it offers.")
    public List<ServiceClientDef> list(@QueryParam("app_root") String appRoot,
                                       @PathParam("svc") String service) throws IOException {
        return ServiceClientIntrospector.listClients(requireAbsoluteAppRoot(appRoot), service);
    }

    @GET
    @Path("/{target}")
    @Operation(summary = "Get a web service's client for one service",
               description = "Returns the client's class and its methods, one per operation of the target's api.xml. 404 if there is none.")
    public ServiceClientDef get(@QueryParam("app_root") String appRoot,
                                @PathParam("svc") String service,
                                @PathParam("target") String target) throws IOException {
        ServiceClientDef c = ServiceClientIntrospector.getClient(requireAbsoluteAppRoot(appRoot), service, target);
        if (c == null) throw new NotFoundException("no client for " + target + " on " + service);
        return c;
    }

    @POST
    @Consumes(MediaType.APPLICATION_JSON)
    @Operation(summary = "Prepare a web service for request-reply with another service",
               description = "Adds the client class, the Maven dependency, the target's client bus and the client's "
                           + "connection in every profile. The reason names the client class and its methods. "
                           + "Idempotent; supports dry_run.")
    @Parameter(in = ParameterIn.QUERY, name = "detail",
               description = "Return the full result: every key present and paths absolute. "
                           + "By default paths are relative to the app_root you supplied and "
                           + "empty collections are omitted (RUMI-414).")
    public ChangeSet add(@QueryParam("app_root") String appRoot,
                         @PathParam("svc") String service,
                         @QueryParam("dry_run") @DefaultValue("false") boolean dryRun,
                         AddServiceClientRequest req) throws IOException {
        if (req == null || req.getService() == null || req.getService().isBlank()) {
            throw new IllegalArgumentException("service is required");
        }
        return ServiceClientEditor.addServiceClient(requireAbsoluteAppRoot(appRoot), service, req.getService(), dryRun);
    }

    @DELETE
    @Path("/{target}")
    @Operation(summary = "Remove a web service's client for one service",
               description = "Removes the client, the dependency it added and its configuration. Refused while code in "
                           + "the web service uses the client, unless force=true. Supports dry_run.")
    @Parameter(in = ParameterIn.QUERY, name = "detail",
               description = "Return the full result: every key present and paths absolute. "
                           + "By default paths are relative to the app_root you supplied and "
                           + "empty collections are omitted (RUMI-414).")
    public ChangeSet remove(@QueryParam("app_root") String appRoot,
                            @PathParam("svc") String service,
                            @PathParam("target") String target,
                            @QueryParam("force") @DefaultValue("false") boolean force,
                            @QueryParam("dry_run") @DefaultValue("false") boolean dryRun) throws IOException {
        return ServiceClientEditor.removeServiceClient(requireAbsoluteAppRoot(appRoot), service, target, force, dryRun);
    }
}
