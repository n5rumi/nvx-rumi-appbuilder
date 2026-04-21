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

import com.neeve.appbuilder.ServiceBuilder;
import com.neeve.appbuilder.ServiceIntrospector;
import com.neeve.appbuilder.ServiceRemover;
import com.neeve.appbuilder.model.ChangeSet;
import com.neeve.appbuilder.model.ServiceInfo;
import com.neeve.appbuilder.rest.dto.AddServiceRequest;
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
 * Service-level endpoints: list, get, add, remove.
 */
@Path("/v1/services")
@Produces(MediaType.APPLICATION_JSON)
public class Services extends AbstractResource {

    @GET
    public List<ServiceInfo> list(@QueryParam("app_root") String appRoot) throws IOException {
        return ServiceIntrospector.listServices(requireAbsoluteAppRoot(appRoot));
    }

    @GET
    @Path("/{name}")
    public ServiceInfo get(@QueryParam("app_root") String appRoot,
                           @PathParam("name") String name) throws IOException {
        ServiceInfo info = ServiceIntrospector.getService(requireAbsoluteAppRoot(appRoot), name);
        if (info == null) throw new NotFoundException("service not found: " + name);
        return info;
    }

    @POST
    @Consumes(MediaType.APPLICATION_JSON)
    public ServiceInfo add(@QueryParam("app_root") String appRoot,
                           AddServiceRequest req) throws Exception {
        java.nio.file.Path root = requireAbsoluteAppRoot(appRoot);
        if (req == null) throw new IllegalArgumentException("request body is required");
        if (req.getName() == null || req.getName().isBlank()) {
            throw new IllegalArgumentException("service name is required");
        }
        if (req.getType() == null || req.getType().isBlank()) {
            throw new IllegalArgumentException("service type is required (processor|driver|csvwriter)");
        }
        ServiceBuilder.ServiceParams params = req.toSdk(root.toString());
        new ServiceBuilder().createService(params);
        return ServiceIntrospector.getService(root, req.getName());
    }

    @DELETE
    @Path("/{name}")
    public ChangeSet remove(@QueryParam("app_root") String appRoot,
                            @PathParam("name") String name,
                            @QueryParam("dry_run") @DefaultValue("false") boolean dryRun) throws IOException {
        return ServiceRemover.removeService(requireAbsoluteAppRoot(appRoot), name, dryRun);
    }
}
