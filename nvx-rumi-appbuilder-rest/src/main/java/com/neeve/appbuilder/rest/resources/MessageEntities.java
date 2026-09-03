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

import com.neeve.appbuilder.EntityEditor;
import com.neeve.appbuilder.FieldEditor;
import com.neeve.appbuilder.MessageIntrospector;
import com.neeve.appbuilder.model.ChangeSet;
import com.neeve.appbuilder.model.EntityDef;
import com.neeve.appbuilder.rest.dto.AddEntityRequest;
import com.neeve.appbuilder.rest.dto.AddFieldRequest;
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
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * Embedded {@code <entity>} declarations in a message model — entities defined
 * alongside the messages and used as message field types. The {@code scope}
 * query parameter selects the model: {@code messages} (the service's private
 * message model, the default) or {@code roe} (the shared app-wide ROE model).
 * State entities live elsewhere — see {@link StateEntities}.
 *
 * <p>Field-level edits on an embedded entity go through the {@code Fields}
 * resource with the matching scope.
 */
@Path("/v1/services/{svc}/message-entities")
@Produces(MediaType.APPLICATION_JSON)
@Tag(name = "MessageEntities", description = "Embedded X-ADML entities in a message model (service or shared ROE model)")
public class MessageEntities extends AbstractResource {

    @GET
    @Operation(summary = "List embedded entities in a message model",
               description = "Returns every embedded entity in the model (scope=messages|roe; default messages).")
    public List<EntityDef> list(@QueryParam("app_root") String appRoot,
                                @PathParam("svc") String service,
                                @QueryParam("scope") @DefaultValue("messages") String scope) throws IOException {
        return MessageIntrospector.listEntities(requireAbsoluteAppRoot(appRoot), service, parseScope(scope));
    }

    @GET
    @Path("/{name}")
    @Operation(summary = "Get a single embedded entity",
               description = "Returns the entity definition (fields, attributes, local id). scope=messages|roe. 404 if absent.")
    public EntityDef get(@QueryParam("app_root") String appRoot,
                         @PathParam("svc") String service,
                         @PathParam("name") String name,
                         @QueryParam("scope") @DefaultValue("messages") String scope) throws IOException {
        EntityDef e = MessageIntrospector.getEntity(requireAbsoluteAppRoot(appRoot), service, name, parseScope(scope));
        if (e == null) throw new NotFoundException("embedded entity not found: " + name);
        return e;
    }

    @POST
    @Consumes(MediaType.APPLICATION_JSON)
    @Operation(summary = "Add an embedded entity to a message model",
               description = "Adds a new embedded entity (scope=messages|roe; default messages). Defaults asEmbedded=true (an entity used as a message field type must be embedded); pass attributes.asEmbedded=false to override. Its local id is allocated automatically and never reused.")
    @Parameter(in = ParameterIn.QUERY, name = "detail",
               description = "Return the full result: every key present and paths absolute. "
                           + "By default paths are relative to the app_root you supplied and "
                           + "empty collections are omitted (RUMI-414).")
    public ChangeSet add(@QueryParam("app_root") String appRoot,
                         @PathParam("svc") String service,
                         @QueryParam("scope") @DefaultValue("messages") String scope,
                         @QueryParam("dry_run") @DefaultValue("false") boolean dryRun,
                         AddEntityRequest req) throws IOException {
        if (req == null) throw new IllegalArgumentException("request body is required");
        // Embedded entities in a message model exist to be field types, so they
        // must be asEmbedded. Default it on unless the caller set it explicitly.
        Map<String, String> attrs = new LinkedHashMap<>(req.getAttributes());
        attrs.putIfAbsent("asEmbedded", "true");
        return EntityEditor.addEntity(requireAbsoluteAppRoot(appRoot), service,
            parseScope(scope), req.getName(), attrs, req.toSdkFields(), dryRun);
    }

    @DELETE
    @Path("/{name}")
    @Operation(summary = "Remove an embedded entity",
               description = "Removes the named embedded entity (scope=messages|roe). Blocked when a field/collection in the model still references it, unless force=true. Its id is reserved (tombstone) so it is never reused.")
    @Parameter(in = ParameterIn.QUERY, name = "detail",
               description = "Return the full result: every key present and paths absolute. "
                           + "By default paths are relative to the app_root you supplied and "
                           + "empty collections are omitted (RUMI-414).")
    public ChangeSet remove(@QueryParam("app_root") String appRoot,
                            @PathParam("svc") String service,
                            @PathParam("name") String name,
                            @QueryParam("scope") @DefaultValue("messages") String scope,
                            @QueryParam("dry_run") @DefaultValue("false") boolean dryRun,
                            @QueryParam("force") @DefaultValue("false") boolean force) throws IOException {
        return EntityEditor.removeEntity(requireAbsoluteAppRoot(appRoot), service,
            parseScope(scope), name, dryRun, force);
    }

    /** Parse a message-model scope (messages|roe); state entities use {@link StateEntities}. */
    private static FieldEditor.ModelScope parseScope(String scope) {
        FieldEditor.ModelScope s = AddFieldRequest.parseScope(scope);
        if (s == FieldEditor.ModelScope.SERVICE_STATE) {
            throw new IllegalArgumentException(
                "state entities are managed at /v1/services/{svc}/state-entities, not here (scope must be messages|roe)");
        }
        return s;
    }
}
