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
import java.util.stream.Collectors;

/**
 * Request body for {@code POST /v1/services/{svc}/state-entities}.
 */
public final class AddStateEntityRequest {
    private final String name;
    private final List<FieldSpec> fields;

    @JsonCreator
    public AddStateEntityRequest(@JsonProperty("name") String name,
                                 @JsonProperty("fields") List<FieldSpec> fields) {
        this.name = name;
        this.fields = fields == null ? Collections.emptyList() : fields;
    }

    public String getName() { return name; }
    public List<FieldSpec> getFields() { return fields; }

    public List<FieldDef> toSdkFields() {
        return fields.stream().map(FieldSpec::toSdk).collect(Collectors.toList());
    }
}
