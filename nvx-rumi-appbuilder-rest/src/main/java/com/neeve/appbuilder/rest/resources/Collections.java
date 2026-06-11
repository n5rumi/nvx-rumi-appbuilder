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

import com.neeve.appbuilder.CollectionEditor;
import com.neeve.appbuilder.StateIntrospector;
import com.neeve.appbuilder.model.ChangeSet;
import com.neeve.appbuilder.model.CollectionDef;
import com.neeve.appbuilder.rest.dto.AddCollectionRequest;
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
 * {@code <collection>} declarations in a service's state model — low-level
 * X-ADML structures ({@code is}=StringMap|IntMap|…|Queue, {@code contains}=
 * element type). State-scoped; collections are a state concept.
 */
@Path("/v1/services/{svc}/collections")
@Produces(MediaType.APPLICATION_JSON)
@Tag(name = "Collections", description = "X-ADML state collections (maps/queues)")
public class Collections extends AbstractResource {

    @GET
    @Operation(summary = "List collections on a service",
               description = "Returns every collection defined in the service's state.xml.")
    public List<CollectionDef> list(@QueryParam("app_root") String appRoot,
                                    @PathParam("svc") String service) throws IOException {
        return StateIntrospector.listCollections(requireAbsoluteAppRoot(appRoot), service);
    }

    @GET
    @Path("/{name}")
    @Operation(summary = "Get a single collection",
               description = "Returns the collection definition (kind, element type, id). 404 if absent.")
    public CollectionDef get(@QueryParam("app_root") String appRoot,
                             @PathParam("svc") String service,
                             @PathParam("name") String name) throws IOException {
        CollectionDef c = StateIntrospector.getCollection(requireAbsoluteAppRoot(appRoot), service, name);
        if (c == null) throw new NotFoundException("collection not found: " + name);
        return c;
    }

    @POST
    @Consumes(MediaType.APPLICATION_JSON)
    @Operation(summary = "Add a collection to a service's state model",
               description = "is is the kind (StringMap|IntMap|…|Queue); contains is the element type. Its local id is allocated automatically and never reused.")
    public ChangeSet add(@QueryParam("app_root") String appRoot,
                         @PathParam("svc") String service,
                         @QueryParam("dry_run") @DefaultValue("false") boolean dryRun,
                         AddCollectionRequest req) throws IOException {
        if (req == null) throw new IllegalArgumentException("request body is required");
        return CollectionEditor.addCollection(requireAbsoluteAppRoot(appRoot), service,
            com.neeve.appbuilder.FieldEditor.ModelScope.SERVICE_STATE,
            req.getName(), req.getIs(), req.getContains(), req.getAttributes(), dryRun);
    }

    @DELETE
    @Path("/{name}")
    @Operation(summary = "Remove a collection",
               description = "Removes the named collection from the service's state.xml. Its id is reserved (tombstone) so it is never reused.")
    public ChangeSet remove(@QueryParam("app_root") String appRoot,
                            @PathParam("svc") String service,
                            @PathParam("name") String name,
                            @QueryParam("dry_run") @DefaultValue("false") boolean dryRun) throws IOException {
        return CollectionEditor.removeCollection(requireAbsoluteAppRoot(appRoot), service, name, dryRun);
    }
}
