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

import com.neeve.appbuilder.HandlerIntrospector;
import com.neeve.appbuilder.JavaSourceEditor;
import com.neeve.appbuilder.model.ChangeSet;
import com.neeve.appbuilder.model.HandlerDef;
import com.neeve.appbuilder.rest.dto.AddHandlerRequest;
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
 * Handler endpoints, scoped to a single service.
 */
@Path("/v1/services/{svc}/handlers")
@Produces(MediaType.APPLICATION_JSON)
@Tag(name = "Handlers", description = "Service @EventHandler methods in Main.java")
public class Handlers extends AbstractResource {

    @GET
    @Operation(summary = "List handlers on a service",
               description = "Scans the service's Main.java for methods annotated with @EventHandler and returns their signatures.")
    public List<HandlerDef> list(@QueryParam("app_root") String appRoot,
                                 @PathParam("svc") String service) throws IOException {
        return HandlerIntrospector.listHandlers(requireAbsoluteAppRoot(appRoot), service);
    }

    @GET
    @Path("/{method}")
    @Operation(summary = "Get a single handler",
               description = "Returns the handler definition (parameters, message type, modifiers) for the named method. 404 if the handler isn't present.")
    public HandlerDef get(@QueryParam("app_root") String appRoot,
                          @PathParam("svc") String service,
                          @PathParam("method") String method) throws IOException {
        HandlerDef h = HandlerIntrospector.getHandler(requireAbsoluteAppRoot(appRoot), service, method);
        if (h == null) throw new NotFoundException("handler not found: " + method);
        return h;
    }

    @POST
    @Consumes(MediaType.APPLICATION_JSON)
    @Operation(summary = "Add a handler to a service",
               description = "Adds an @EventHandler method to the service's Main.java. If body is null the method starts with an empty body plus a TODO comment.")
    public ChangeSet add(@QueryParam("app_root") String appRoot,
                         @PathParam("svc") String service,
                         @QueryParam("dry_run") @DefaultValue("false") boolean dryRun,
                         AddHandlerRequest req) throws IOException {
        if (req == null) throw new IllegalArgumentException("request body is required");
        return JavaSourceEditor.addHandler(requireAbsoluteAppRoot(appRoot), service,
            req.getMethod(), req.getMessageType(), req.getBody(), dryRun);
    }

    @DELETE
    @Path("/{method}")
    @Operation(summary = "Remove a handler from a service",
               description = "Removes the @EventHandler method from the service's Main.java.")
    public ChangeSet remove(@QueryParam("app_root") String appRoot,
                            @PathParam("svc") String service,
                            @PathParam("method") String method,
                            @QueryParam("dry_run") @DefaultValue("false") boolean dryRun) throws IOException {
        return JavaSourceEditor.removeHandler(requireAbsoluteAppRoot(appRoot), service, method, dryRun);
    }
}
