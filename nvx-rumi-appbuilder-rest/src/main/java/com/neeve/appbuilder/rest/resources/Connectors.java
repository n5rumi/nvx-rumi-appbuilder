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

import com.neeve.appbuilder.ConnectorEditor;
import com.neeve.appbuilder.ConnectorIntrospector;
import com.neeve.appbuilder.model.ChangeSet;
import com.neeve.appbuilder.model.ConnectorDef;
import com.neeve.appbuilder.rest.dto.AddConnectorRequest;
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
 * Connector endpoints, scoped to a single service. A connector is a
 * user-authored Rumi message-bus binding snapped into the service.
 */
@Path("/v1/services/{svc}/connectors")
@Produces(MediaType.APPLICATION_JSON)
@Tag(name = "Connectors", description = "Custom Rumi connectors (message-bus bindings) on a service")
public class Connectors extends AbstractResource {

    @GET
    @Operation(summary = "List connectors on a service",
               description = "Returns every connector bound to the service: its class, connector bus, and inbound channel.")
    public List<ConnectorDef> list(@QueryParam("app_root") String appRoot,
                                   @PathParam("svc") String service) throws IOException {
        return ConnectorIntrospector.listConnectors(requireAbsoluteAppRoot(appRoot), service);
    }

    @GET
    @Path("/{name}")
    @Operation(summary = "Get a single connector",
               description = "Returns the named connector's definition. 404 if the connector isn't present.")
    public ConnectorDef get(@QueryParam("app_root") String appRoot,
                            @PathParam("svc") String service,
                            @PathParam("name") String name) throws IOException {
        ConnectorDef c = ConnectorIntrospector.getConnector(requireAbsoluteAppRoot(appRoot), service, name);
        if (c == null) throw new NotFoundException("connector not found: " + name);
        return c;
    }

    @POST
    @Consumes(MediaType.APPLICATION_JSON)
    @Operation(summary = "Add a connector to a service",
               description = "Snaps a custom connector into the service: a Connector class under the service's connector subpackage, a connector:// bus binding, and the app messaging reference. Idempotent; supports dry_run.")
    @Parameter(in = ParameterIn.QUERY, name = "detail",
               description = "Return the full result: every key present and paths absolute. "
                           + "By default paths are relative to the app_root you supplied and "
                           + "empty collections are omitted (RUMI-414).")
    public ChangeSet add(@QueryParam("app_root") String appRoot,
                         @PathParam("svc") String service,
                         @QueryParam("dry_run") @DefaultValue("false") boolean dryRun,
                         AddConnectorRequest req) throws IOException {
        if (req == null || req.getName() == null || req.getName().isBlank()) {
            throw new IllegalArgumentException("connector name is required");
        }
        return ConnectorEditor.addConnector(requireAbsoluteAppRoot(appRoot), service, req.getName(), dryRun);
    }

    @DELETE
    @Path("/{name}")
    @Operation(summary = "Remove a connector from a service",
               description = "Reverts the connector: deletes the Connector class, removes the bus binding and the app messaging reference. Supports dry_run.")
    @Parameter(in = ParameterIn.QUERY, name = "detail",
               description = "Return the full result: every key present and paths absolute. "
                           + "By default paths are relative to the app_root you supplied and "
                           + "empty collections are omitted (RUMI-414).")
    public ChangeSet remove(@QueryParam("app_root") String appRoot,
                            @PathParam("svc") String service,
                            @PathParam("name") String name,
                            @QueryParam("dry_run") @DefaultValue("false") boolean dryRun) throws IOException {
        return ConnectorEditor.removeConnector(requireAbsoluteAppRoot(appRoot), service, name, dryRun);
    }
}
