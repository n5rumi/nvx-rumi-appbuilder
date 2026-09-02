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

import com.neeve.appbuilder.FieldEditor;
import com.neeve.appbuilder.model.ChangeSet;
import com.neeve.appbuilder.rest.dto.AddFieldRequest;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;
import jakarta.ws.rs.Consumes;
import jakarta.ws.rs.DELETE;
import jakarta.ws.rs.DefaultValue;
import jakarta.ws.rs.POST;
import jakarta.ws.rs.Path;
import jakarta.ws.rs.PathParam;
import jakarta.ws.rs.Produces;
import jakarta.ws.rs.QueryParam;
import jakarta.ws.rs.core.MediaType;

import java.io.IOException;

/**
 * Field-level model editing on a {@code <message>} or {@code <entity>}, scoped
 * to the service's message model, state model, or the shared ROE model. A
 * deleted field's id is reserved forever (never reused); a field's type can
 * never change on the wire, so there is no retype.
 */
@Path("/v1/services/{svc}/fields")
@Produces(MediaType.APPLICATION_JSON)
@Tag(name = "Fields", description = "Add/delete/deprecate/rename fields on a message or entity")
public class Fields extends AbstractResource {

    @POST
    @Consumes(MediaType.APPLICATION_JSON)
    @Operation(summary = "Add a field to a message or entity",
               description = "scope is messages|state|roe; type is the message/entity name. Each field gets a stable, never-reused id. "
                           + "Send a fields[] array to add several in one call - the whole batch is one load-validate-write, so a "
                           + "rejected field means none are added. The single name/fieldType form still works.")
    public ChangeSet add(@QueryParam("app_root") String appRoot,
                         @PathParam("svc") String service,
                         @QueryParam("dry_run") @DefaultValue("false") boolean dryRun,
                         AddFieldRequest req) throws IOException {
        if (req == null) throw new IllegalArgumentException("request body is required");
        if (req.isBatch()) {
            if (req.getName() != null && !req.getName().isBlank()) {
                // Both forms at once is ambiguous: which field carries the
                // attributes? Refuse rather than silently honouring one.
                throw new IllegalArgumentException(
                    "send either 'name' or 'fields', not both");
            }
            return FieldEditor.addFields(requireAbsoluteAppRoot(appRoot), service, req.toScope(),
                req.getType(), req.toFieldDefs(), dryRun);
        }
        if (req.getName() == null || req.getName().isBlank()) {
            throw new IllegalArgumentException("field name is required, or send a fields[] array");
        }
        return FieldEditor.addField(requireAbsoluteAppRoot(appRoot), service, req.toScope(),
            req.getType(), req.getName(), req.getFieldType(), req.getAttributes(), dryRun);
    }

    @DELETE
    @Operation(summary = "Delete a field",
               description = "Physically removes the field and reserves its id (tombstone) so it is never reused.")
    public ChangeSet delete(@QueryParam("app_root") String appRoot,
                            @PathParam("svc") String service,
                            @QueryParam("scope") String scope,
                            @QueryParam("type") String type,
                            @QueryParam("name") String name,
                            @QueryParam("dry_run") @DefaultValue("false") boolean dryRun) throws IOException {
        return FieldEditor.deleteField(requireAbsoluteAppRoot(appRoot), service,
            AddFieldRequest.parseScope(scope), type, name, dryRun);
    }

    @POST
    @Path("/deprecate")
    @Operation(summary = "Deprecate a field",
               description = "Keeps the field but marks its accessors @Deprecated. Distinct from delete.")
    public ChangeSet deprecate(@QueryParam("app_root") String appRoot,
                               @PathParam("svc") String service,
                               @QueryParam("scope") String scope,
                               @QueryParam("type") String type,
                               @QueryParam("name") String name,
                               @QueryParam("dry_run") @DefaultValue("false") boolean dryRun) throws IOException {
        return FieldEditor.deprecateField(requireAbsoluteAppRoot(appRoot), service,
            AddFieldRequest.parseScope(scope), type, name, dryRun);
    }

    @POST
    @Path("/rename")
    @Operation(summary = "Rename a field",
               description = "Changes only the name; the id is unchanged (wire-safe). Hand-written Java referencing the old accessor still needs fixing.")
    public ChangeSet rename(@QueryParam("app_root") String appRoot,
                            @PathParam("svc") String service,
                            @QueryParam("scope") String scope,
                            @QueryParam("type") String type,
                            @QueryParam("name") String name,
                            @QueryParam("to") String to,
                            @QueryParam("dry_run") @DefaultValue("false") boolean dryRun) throws IOException {
        return FieldEditor.renameField(requireAbsoluteAppRoot(appRoot), service,
            AddFieldRequest.parseScope(scope), type, name, to, dryRun);
    }
}
