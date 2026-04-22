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

import com.neeve.appbuilder.StateEditor;
import com.neeve.appbuilder.StateIntrospector;
import com.neeve.appbuilder.model.ChangeSet;
import com.neeve.appbuilder.model.EntityDef;
import com.neeve.appbuilder.rest.dto.AddStateEntityRequest;
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
 * State-entity endpoints, scoped to a single service.
 */
@Path("/v1/services/{svc}/state-entities")
@Produces(MediaType.APPLICATION_JSON)
@Tag(name = "StateEntities", description = "X-ADML state entity definitions")
public class StateEntities extends AbstractResource {

    @GET
    @Operation(summary = "List state entities on a service",
               description = "Returns every state entity defined in the service's state.xml.")
    public List<EntityDef> list(@QueryParam("app_root") String appRoot,
                                @PathParam("svc") String service) throws IOException {
        return StateIntrospector.listStateEntities(requireAbsoluteAppRoot(appRoot), service);
    }

    @GET
    @Path("/{name}")
    @Operation(summary = "Get a single state entity",
               description = "Returns the entity definition (fields, key annotations, factory IDs) for the named entity. 404 if the entity isn't present.")
    public EntityDef get(@QueryParam("app_root") String appRoot,
                         @PathParam("svc") String service,
                         @PathParam("name") String name) throws IOException {
        EntityDef e = StateIntrospector.getStateEntity(requireAbsoluteAppRoot(appRoot), service, name);
        if (e == null) throw new NotFoundException("state entity not found: " + name);
        return e;
    }

    @POST
    @Consumes(MediaType.APPLICATION_JSON)
    @Operation(summary = "Add a state entity to a service",
               description = "Adds a new state entity to the service's state.xml. Fields can carry attributes like key=true.")
    public ChangeSet add(@QueryParam("app_root") String appRoot,
                         @PathParam("svc") String service,
                         @QueryParam("dry_run") @DefaultValue("false") boolean dryRun,
                         AddStateEntityRequest req) throws IOException {
        if (req == null) throw new IllegalArgumentException("request body is required");
        return StateEditor.addStateEntity(requireAbsoluteAppRoot(appRoot), service,
            req.getName(), req.toSdkFields(), dryRun);
    }

    @DELETE
    @Path("/{name}")
    @Operation(summary = "Remove a state entity",
               description = "Removes the named entity from the service's state.xml.")
    public ChangeSet remove(@QueryParam("app_root") String appRoot,
                            @PathParam("svc") String service,
                            @PathParam("name") String name,
                            @QueryParam("dry_run") @DefaultValue("false") boolean dryRun) throws IOException {
        return StateEditor.removeStateEntity(requireAbsoluteAppRoot(appRoot), service, name, dryRun);
    }
}
