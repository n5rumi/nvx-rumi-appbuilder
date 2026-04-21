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
import java.util.Map;

/**
 * Request-side field descriptor shared by message-add and state-entity-add
 * endpoints. Mirrors the SDK's {@link FieldDef} shape but is safe to
 * deserialize from untrusted JSON: constructor is explicit, attributes
 * map defaults to empty.
 *
 * <p>JSON: {@code {"name":"price","type":"double","attributes":{"key":"true"}}}.
 */
public final class FieldSpec {
    private final String name;
    private final String type;
    private final Map<String, String> attributes;

    @JsonCreator
    public FieldSpec(@JsonProperty("name") String name,
                     @JsonProperty("type") String type,
                     @JsonProperty("attributes") Map<String, String> attributes) {
        this.name = name;
        this.type = type;
        this.attributes = attributes == null ? Collections.emptyMap() : attributes;
    }

    public String getName() { return name; }
    public String getType() { return type; }
    public Map<String, String> getAttributes() { return attributes; }

    public FieldDef toSdk() {
        return new FieldDef(name, type, attributes);
    }
}
