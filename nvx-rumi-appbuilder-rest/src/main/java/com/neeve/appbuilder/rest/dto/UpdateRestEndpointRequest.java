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
package com.neeve.appbuilder.rest.dto;

import com.fasterxml.jackson.annotation.JsonCreator;
import com.fasterxml.jackson.annotation.JsonProperty;
import java.util.List;

/** Request body for {@code PUT /v1/services/{svc}/rest-endpoints/{method}?path=...}: the new body and any imports it needs. */
public final class UpdateRestEndpointRequest {
    private final String body;
    private final List<String> imports;

    @JsonCreator
    public UpdateRestEndpointRequest(@JsonProperty("body") String body,
                                     @JsonProperty("imports") List<String> imports) {
        this.body = body;
        this.imports = imports;
    }

    public String getBody() { return body; }
    public List<String> getImports() { return imports; }
}
