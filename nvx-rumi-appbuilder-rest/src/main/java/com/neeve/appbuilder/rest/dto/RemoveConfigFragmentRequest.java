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
import com.neeve.appbuilder.model.ElementSelector;

import java.util.Collections;
import java.util.List;

/**
 * Request body for {@code DELETE /v1/config/fragments}.
 *
 * <p>Selectors: specify {@code tag} alone, {@code name} alone, or both
 * (most common — {@code tag="app"} + {@code name="trading-order-processor-template"}).
 */
public final class RemoveConfigFragmentRequest {
    private final List<String> scopePath;
    private final String tag;
    private final String name;

    @JsonCreator
    public RemoveConfigFragmentRequest(@JsonProperty("scopePath") List<String> scopePath,
                                       @JsonProperty("tag") String tag,
                                       @JsonProperty("name") String name) {
        this.scopePath = scopePath == null ? Collections.emptyList() : scopePath;
        this.tag = tag;
        this.name = name;
    }

    public List<String> getScopePath() { return scopePath; }
    public String getTag() { return tag; }
    public String getName() { return name; }

    public ElementSelector toSelector() {
        if (tag != null && name != null) return ElementSelector.byTagAndName(tag, name);
        if (tag != null) return ElementSelector.byTag(tag);
        if (name != null) return ElementSelector.byName(name);
        throw new IllegalArgumentException("at least one of tag, name must be provided");
    }
}
