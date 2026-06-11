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

import java.util.Collections;
import java.util.Map;

/**
 * Request body for {@code POST /v1/services/{svc}/collections}.
 *
 * <p>{@code is} is the collection kind ({@code StringMap}, {@code IntMap}, …,
 * {@code Queue}); {@code contains} is the element type (an entity/message name
 * or a scalar). {@code attributes} carries any extra collection-level
 * attributes; {@code name}/{@code id} there are ignored.
 */
public final class AddCollectionRequest {
    private final String name;
    private final String is;
    private final String contains;
    private final Map<String, String> attributes;

    @JsonCreator
    public AddCollectionRequest(@JsonProperty("name") String name,
                                @JsonProperty("is") String is,
                                @JsonProperty("contains") String contains,
                                @JsonProperty("attributes") Map<String, String> attributes) {
        this.name = name;
        this.is = is;
        this.contains = contains;
        this.attributes = attributes == null ? Collections.emptyMap() : attributes;
    }

    public String getName() { return name; }
    public String getIs() { return is; }
    public String getContains() { return contains; }
    public Map<String, String> getAttributes() { return attributes; }
}
