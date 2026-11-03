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

import com.neeve.appbuilder.ServiceClientIntrospector;
import com.neeve.appbuilder.model.ServiceClientDef;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;
import jakarta.ws.rs.BadRequestException;
import jakarta.ws.rs.GET;
import jakarta.ws.rs.Path;
import jakarta.ws.rs.Produces;
import jakarta.ws.rs.QueryParam;
import jakarta.ws.rs.core.MediaType;
import java.io.IOException;
import java.util.List;

/**
 * The reverse of {@link ServiceClients}: every web service's client for one
 * service, so a caller about to change that service's api.xml can see whose
 * generated client it breaks. Rooted apart from {@code /v1/services/{svc}},
 * since the web services are what it finds, not what it is given.
 */
@Path("/v1/service-clients")
@Produces(MediaType.APPLICATION_JSON)
@Tag(name = "Service clients", description = "Prepare a web service for request-reply with another service")
public class ServiceCallers extends AbstractResource {

    @GET
    @Operation(summary = "List every web service's client for one service",
               description = "Returns one record per web service holding a client for the service: the web service, "
                           + "the client's class and engine. Methods are omitted; they are the service's api.xml "
                           + "operations. 400 if service is missing or unknown.")
    public List<ServiceClientDef> list(@QueryParam("app_root") String appRoot,
                                       @QueryParam("service") String service) throws IOException {
        if (service == null || service.isBlank()) throw new BadRequestException("service is required: name the service whose callers to list, "
            + "or list a web service's own clients at /v1/services/{svc}/service-clients (MCP: list_service_clients with webservice)");
        return ServiceClientIntrospector.listClientsOf(requireAbsoluteAppRoot(appRoot), service);
    }
}
