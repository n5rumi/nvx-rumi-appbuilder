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
import com.neeve.appbuilder.model.FieldDef;

import java.util.Collections;
import java.util.List;
import java.util.Map;
import java.util.stream.Collectors;

/**
 * Request body for adding an embedded {@code <entity>} to a message model
 * ({@code POST /v1/services/{svc}/message-entities}). Same {name, fields} shape
 * as the message- and state-entity add bodies; the target model is selected by
 * the resource's {@code scope} query parameter rather than carried here.
 *
 * <p>{@code attributes} carries entity-level attributes (e.g.
 * {@code {"asEmbedded":"true"}}); {@code name}/{@code id} there are ignored.
 */
public final class AddEntityRequest {
    private final String name;
    private final Map<String, String> attributes;
    private final List<FieldSpec> fields;

    @JsonCreator
    public AddEntityRequest(@JsonProperty("name") String name,
                            @JsonProperty("attributes") Map<String, String> attributes,
                            @JsonProperty("fields") List<FieldSpec> fields) {
        this.name = name;
        this.attributes = attributes == null ? Collections.emptyMap() : attributes;
        this.fields = fields == null ? Collections.emptyList() : fields;
    }

    public String getName() { return name; }
    public Map<String, String> getAttributes() { return attributes; }
    public List<FieldSpec> getFields() { return fields; }

    public List<FieldDef> toSdkFields() {
        return fields.stream().map(FieldSpec::toSdk).collect(Collectors.toList());
    }
}
