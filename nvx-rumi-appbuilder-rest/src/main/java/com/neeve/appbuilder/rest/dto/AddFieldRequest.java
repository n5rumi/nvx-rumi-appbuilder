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
import com.neeve.appbuilder.FieldEditor;

import java.util.Collections;
import java.util.Map;

/**
 * Request body for {@code POST /v1/services/{svc}/fields}.
 *
 * <p>{@code scope} selects the model: {@code messages} (service-private),
 * {@code state} (service state model), or {@code roe} (shared ROE model).
 * {@code type} is the owning {@code <message>}/{@code <entity>} name.
 */
public final class AddFieldRequest {
    private final String scope;
    private final String type;
    private final String name;
    private final String fieldType;
    private final Map<String, String> attributes;

    @JsonCreator
    public AddFieldRequest(@JsonProperty("scope") String scope,
                           @JsonProperty("type") String type,
                           @JsonProperty("name") String name,
                           @JsonProperty("fieldType") String fieldType,
                           @JsonProperty("attributes") Map<String, String> attributes) {
        this.scope = scope;
        this.type = type;
        this.name = name;
        this.fieldType = fieldType;
        this.attributes = attributes == null ? Collections.emptyMap() : attributes;
    }

    public String getScope() { return scope; }
    public String getType() { return type; }
    public String getName() { return name; }
    public String getFieldType() { return fieldType; }
    public Map<String, String> getAttributes() { return attributes; }

    public FieldEditor.ModelScope toScope() { return parseScope(scope); }

    /** Map a friendly scope string to the SDK enum. */
    public static FieldEditor.ModelScope parseScope(String scope) {
        if (scope == null) throw new IllegalArgumentException("scope is required (messages|state|roe)");
        switch (scope.trim().toLowerCase()) {
            case "messages":
            case "service_messages": return FieldEditor.ModelScope.SERVICE_MESSAGES;
            case "state":
            case "service_state":    return FieldEditor.ModelScope.SERVICE_STATE;
            case "roe":
            case "roe_messages":     return FieldEditor.ModelScope.ROE_MESSAGES;
            default: throw new IllegalArgumentException("unknown scope '" + scope + "' (messages|state|roe)");
        }
    }
}
