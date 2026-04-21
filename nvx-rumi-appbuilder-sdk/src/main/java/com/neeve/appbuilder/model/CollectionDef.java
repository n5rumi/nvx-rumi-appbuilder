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
package com.neeve.appbuilder.model;

import java.util.Collections;
import java.util.Map;
import java.util.Objects;

/**
 * A {@code <collection>} declaration in an X-ADML state.xml file. Collections
 * are typed maps keyed by some entity field. We expose the attributes as a
 * generic map rather than tracking every named attribute of the schema.
 */
public final class CollectionDef {
    private final String name;
    private final Integer id;
    private final Map<String, String> attributes;

    public CollectionDef(String name, Integer id, Map<String, String> attributes) {
        this.name = name;
        this.id = id;
        this.attributes = Collections.unmodifiableMap(
            Objects.requireNonNull(attributes, "attributes"));
    }

    public String getName() {
        return name;
    }

    public Integer getId() {
        return id;
    }

    public Map<String, String> getAttributes() {
        return attributes;
    }

    @Override
    public String toString() {
        return "CollectionDef{name=" + name + ", id=" + id + "}";
    }
}
