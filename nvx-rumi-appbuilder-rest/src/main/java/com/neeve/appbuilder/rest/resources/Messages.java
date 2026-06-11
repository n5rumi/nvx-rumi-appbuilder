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

import com.neeve.appbuilder.MessageEditor;
import com.neeve.appbuilder.MessageIntrospector;
import com.neeve.appbuilder.model.ChangeSet;
import com.neeve.appbuilder.model.MessageDef;
import com.neeve.appbuilder.rest.dto.AddFieldRequest;
import com.neeve.appbuilder.rest.dto.AddMessageRequest;
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
 * Message-type endpoints. The {@code scope} query parameter selects the model:
 * {@code messages} (the service's private message model, the default) or
 * {@code roe} (the shared app-wide ROE model). For {@code roe} the {@code svc}
 * path segment is ignored — the ROE model is app-level — but a value must still
 * be supplied to satisfy the path.
 */
@Path("/v1/services/{svc}/messages")
@Produces(MediaType.APPLICATION_JSON)
@Tag(name = "Messages", description = "X-ADML message type definitions (service or shared ROE model)")
public class Messages extends AbstractResource {

    @GET
    @Operation(summary = "List message types",
               description = "Returns every message type in the model (scope=messages|roe; default messages).")
    public List<MessageDef> list(@QueryParam("app_root") String appRoot,
                                 @PathParam("svc") String service,
                                 @QueryParam("scope") @DefaultValue("messages") String scope) throws IOException {
        return MessageIntrospector.listMessages(requireAbsoluteAppRoot(appRoot), service,
            AddFieldRequest.parseScope(scope));
    }

    @GET
    @Path("/{name}")
    @Operation(summary = "Get a single message type",
               description = "Returns the message definition (fields, factory IDs, local IDs). scope=messages|roe. 404 if the message isn't present.")
    public MessageDef get(@QueryParam("app_root") String appRoot,
                          @PathParam("svc") String service,
                          @PathParam("name") String name,
                          @QueryParam("scope") @DefaultValue("messages") String scope) throws IOException {
        MessageDef m = MessageIntrospector.getMessage(requireAbsoluteAppRoot(appRoot), service, name,
            AddFieldRequest.parseScope(scope));
        if (m == null) throw new NotFoundException("message not found: " + name);
        return m;
    }

    @POST
    @Consumes(MediaType.APPLICATION_JSON)
    @Operation(summary = "Add a message type",
               description = "Adds a new message type to the model (scope=messages|roe; default messages). The local message ID is allocated automatically and never reused.")
    public ChangeSet add(@QueryParam("app_root") String appRoot,
                         @PathParam("svc") String service,
                         @QueryParam("scope") @DefaultValue("messages") String scope,
                         @QueryParam("dry_run") @DefaultValue("false") boolean dryRun,
                         AddMessageRequest req) throws IOException {
        if (req == null) throw new IllegalArgumentException("request body is required");
        return MessageEditor.addMessage(requireAbsoluteAppRoot(appRoot), service,
            AddFieldRequest.parseScope(scope), req.getName(), req.toSdkFields(), dryRun);
    }

    @DELETE
    @Path("/{name}")
    @Operation(summary = "Remove a message type",
               description = "Removes the named message (scope=messages|roe). Its id is reserved (tombstone) so it is never reused.")
    public ChangeSet remove(@QueryParam("app_root") String appRoot,
                            @PathParam("svc") String service,
                            @PathParam("name") String name,
                            @QueryParam("scope") @DefaultValue("messages") String scope,
                            @QueryParam("dry_run") @DefaultValue("false") boolean dryRun) throws IOException {
        return MessageEditor.removeMessage(requireAbsoluteAppRoot(appRoot), service,
            AddFieldRequest.parseScope(scope), name, dryRun);
    }
}
