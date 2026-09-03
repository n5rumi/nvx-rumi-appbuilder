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

import com.neeve.appbuilder.ModelBatch;
import com.neeve.appbuilder.model.BatchResult;
import com.neeve.appbuilder.rest.dto.ModelBatchRequest;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.Parameter;
import io.swagger.v3.oas.annotations.enums.ParameterIn;
import io.swagger.v3.oas.annotations.tags.Tag;
import jakarta.ws.rs.Consumes;
import jakarta.ws.rs.DefaultValue;
import jakarta.ws.rs.POST;
import jakarta.ws.rs.Path;
import jakarta.ws.rs.Produces;
import jakarta.ws.rs.QueryParam;
import jakarta.ws.rs.core.MediaType;

import java.io.IOException;

/** Whole-model operations (RUMI-412). */
@Path("/v1/model")
@Produces(MediaType.APPLICATION_JSON)
@Tag(name = "Model", description = "Apply many model edits in one call")
public class Model extends AbstractResource {

    @POST
    @Path("/batch")
    @Consumes(MediaType.APPLICATION_JSON)
    @Operation(summary = "Apply a batch of model edits",
               description = "Applies messages, message entities, state entities, collections and fields in ONE call, in the "
                           + "order given - so a batch can add a message and then add fields to it. All-or-nothing: a rejected "
                           + "edit rolls the whole batch back, leaving the app exactly as it was. The result reports each item "
                           + "separately, including which ones were no-ops because they already existed, so re-applying a model "
                           + "is safe and informative. Prefer this over one call per element: the payload is the same, the "
                           + "round trips are not.")
    @Parameter(in = ParameterIn.QUERY, name = "detail",
               description = "Return the full result: every key present and paths absolute. "
                           + "By default paths are relative to the app_root you supplied and "
                           + "empty collections are omitted (RUMI-414).")
    public BatchResult apply(@QueryParam("app_root") String appRoot,
                             @QueryParam("dry_run") @DefaultValue("false") boolean dryRun,
                             ModelBatchRequest req) throws IOException {
        if (req == null) throw new IllegalArgumentException("request body is required");
        // Converting first means a malformed edit is a 400 before anything is
        // written, rather than a rollback partway through a batch.
        return ModelBatch.apply(requireAbsoluteAppRoot(appRoot), req.toSdk(), dryRun);
    }
}
