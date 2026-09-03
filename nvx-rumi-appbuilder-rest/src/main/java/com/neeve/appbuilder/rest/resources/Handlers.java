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
import com.neeve.appbuilder.rest.dto.UpdateHandlerRequest;
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
import jakarta.ws.rs.PUT;
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
               description = "Scans the service's Main.java for methods annotated with @EventHandler and returns their signatures. "
                           + "Bodies are omitted unless include_body is set: a listing answers what handlers exist, and returning "
                           + "every body turns that into a dump of the whole service. Fetch one body with GET /{method}.")
    public List<HandlerDef> list(@QueryParam("app_root") String appRoot,
                                 @PathParam("svc") String service,
                                 @QueryParam("include_body") @DefaultValue("false") boolean includeBody)
            throws IOException {
        return HandlerIntrospector.listHandlers(requireAbsoluteAppRoot(appRoot), service, includeBody);
    }

    @GET
    @Path("/{method}")
    @Operation(summary = "Get a single handler",
               description = "Returns the handler definition (parameters, message type, modifiers) and its body, verbatim and "
                           + "without the enclosing braces. 404 if the handler isn't present. The body round-trips: hand it "
                           + "back to PUT unchanged and the file is byte-identical.")
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
    @Parameter(in = ParameterIn.QUERY, name = "detail",
               description = "Return the full result: every key present and paths absolute. "
                           + "By default paths are relative to the app_root you supplied and "
                           + "empty collections are omitted (RUMI-414).")
    public ChangeSet add(@QueryParam("app_root") String appRoot,
                         @PathParam("svc") String service,
                         @QueryParam("dry_run") @DefaultValue("false") boolean dryRun,
                         AddHandlerRequest req) throws IOException {
        if (req == null) throw new IllegalArgumentException("request body is required");
        return JavaSourceEditor.addHandler(requireAbsoluteAppRoot(appRoot), service,
            req.getMethod(), req.getMessageType(), req.getBody(), dryRun);
    }

    @PUT
    @Path("/{method}")
    @Consumes(MediaType.APPLICATION_JSON)
    @Operation(summary = "Replace a handler's body",
               description = "Replaces the body of an existing @EventHandler method, leaving its signature, annotations and the "
                           + "rest of the file untouched. Idempotent: an unchanged body is a no-op rather than a rewrite. "
                           + "400 if the body does not parse, in which case the file is left exactly as it was. A handler that "
                           + "does not exist is reported as a no-op change set, not a 404, matching DELETE.")
    @Parameter(in = ParameterIn.QUERY, name = "detail",
               description = "Return the full result: every key present and paths absolute. "
                           + "By default paths are relative to the app_root you supplied and "
                           + "empty collections are omitted (RUMI-414).")
    public ChangeSet update(@QueryParam("app_root") String appRoot,
                            @PathParam("svc") String service,
                            @PathParam("method") String method,
                            @QueryParam("dry_run") @DefaultValue("false") boolean dryRun,
                            UpdateHandlerRequest req) throws IOException {
        if (req == null) throw new IllegalArgumentException("request body is required");
        // An ABSENT body is not an empty one. Collapsing the two would let a
        // client that mistyped the key, or that strips nulls, silently erase a
        // working handler and get a 200 back. "" stays the explicit way to
        // empty a handler.
        if (req.getBody() == null) {
            throw new IllegalArgumentException(
                "'body' is required; send \"\" to deliberately empty the handler");
        }
        return JavaSourceEditor.updateHandler(requireAbsoluteAppRoot(appRoot), service,
            method, req.getBody(), dryRun);
    }

    @DELETE
    @Path("/{method}")
    @Operation(summary = "Remove a handler from a service",
               description = "Removes the @EventHandler method from the service's Main.java.")
    @Parameter(in = ParameterIn.QUERY, name = "detail",
               description = "Return the full result: every key present and paths absolute. "
                           + "By default paths are relative to the app_root you supplied and "
                           + "empty collections are omitted (RUMI-414).")
    public ChangeSet remove(@QueryParam("app_root") String appRoot,
                            @PathParam("svc") String service,
                            @PathParam("method") String method,
                            @QueryParam("dry_run") @DefaultValue("false") boolean dryRun) throws IOException {
        return JavaSourceEditor.removeHandler(requireAbsoluteAppRoot(appRoot), service, method, dryRun);
    }
}
