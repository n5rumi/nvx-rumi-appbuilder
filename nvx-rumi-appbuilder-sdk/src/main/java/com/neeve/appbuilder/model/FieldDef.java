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
 * A field declared under a {@code <message>} or {@code <entity>} element in
 * an X-ADML model file. Attribute-level metadata (type, required, default,
 * key, etc.) is exposed as a generic attribute map so introspection doesn't
 * need to track every attribute the schema supports.
 */
public final class FieldDef {
    private final String name;
    private final String type;
    private final Map<String, String> attributes;

    public FieldDef(String name, String type, Map<String, String> attributes) {
        this.name = name;
        this.type = type;
        this.attributes = Collections.unmodifiableMap(
            Objects.requireNonNull(attributes, "attributes"));
    }

    /** Field name (the {@code name} attribute on {@code <field>}). */
    public String getName() {
        return name;
    }

    /** Field type (the {@code type} attribute on {@code <field>}). May be null. */
    public String getType() {
        return type;
    }

    /** Every attribute on the {@code <field>} element, name → value. */
    public Map<String, String> getAttributes() {
        return attributes;
    }

    @Override
    public String toString() {
        return "FieldDef{name=" + name + ", type=" + type + "}";
    }
}
