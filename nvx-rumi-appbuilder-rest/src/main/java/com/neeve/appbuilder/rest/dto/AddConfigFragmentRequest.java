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
import java.util.List;

/**
 * Request body for {@code POST /v1/config/fragments}.
 *
 * <p>{@code scopePath} is the list of X-DDL element tags identifying the
 * parent under which the fragment sits, e.g. {@code ["apps","templates"]}
 * or {@code ["buses"]}. {@code xml} is the raw fragment as it would
 * appear in a config.xml (e.g. {@code <bus xmlns="..." name="aux"/>}).
 */
public final class AddConfigFragmentRequest {
    private final List<String> scopePath;
    private final String xml;

    @JsonCreator
    public AddConfigFragmentRequest(@JsonProperty("scopePath") List<String> scopePath,
                                    @JsonProperty("xml") String xml) {
        this.scopePath = scopePath == null ? Collections.emptyList() : scopePath;
        this.xml = xml;
    }

    public List<String> getScopePath() { return scopePath; }
    public String getXml() { return xml; }
}
