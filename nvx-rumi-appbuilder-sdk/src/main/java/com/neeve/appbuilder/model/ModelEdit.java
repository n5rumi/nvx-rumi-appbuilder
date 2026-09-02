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
import java.util.List;
import java.util.Objects;

/**
 * One item in a model batch (RUMI-412) — a message, message entity, state
 * entity, collection or set of fields to add.
 *
 * <p>Deliberately a small tagged union rather than a general command object.
 * The batch exists to remove round trips from building a model, not to become
 * a second way of expressing every operation the editors already offer; every
 * kind here maps to exactly one existing editor call.
 *
 * <p>Order matters and is preserved. A batch that adds a message and then adds
 * fields to it is the normal shape, so items are applied in sequence rather
 * than grouped by kind.
 */
public final class ModelEdit {

    public enum Kind {
        /** A message in the service's own model, or in ROE. */
        MESSAGE,
        /** An embedded {@code <entity>} in a message model. */
        MESSAGE_ENTITY,
        /** A state entity. */
        STATE_ENTITY,
        /** A state collection. */
        COLLECTION,
        /** Fields appended to an existing message or entity. */
        FIELDS,
    }

    private final Kind kind;
    private final String service;
    private final String name;
    private final String scope;      // "messages" | "state" | "roe"; null = the kind's default
    private final List<FieldDef> fields;
    private final String is;         // COLLECTION only
    private final String contains;   // COLLECTION only

    public ModelEdit(Kind kind, String service, String name, String scope,
                     List<FieldDef> fields, String is, String contains) {
        this.kind = Objects.requireNonNull(kind, "kind");
        this.service = service;
        this.name = Objects.requireNonNull(name, "name");
        this.scope = scope;
        this.fields = fields == null ? Collections.emptyList()
                                     : Collections.unmodifiableList(fields);
        this.is = is;
        this.contains = contains;
    }

    public Kind getKind() { return kind; }
    public String getService() { return service; }
    public String getName() { return name; }
    public String getScope() { return scope; }
    public List<FieldDef> getFields() { return fields; }
    public String getIs() { return is; }
    public String getContains() { return contains; }

    /** Short label used in batch results and error messages. */
    public String describe() {
        return kind + " " + (service == null ? "" : service + "/") + name;
    }

    @Override
    public String toString() {
        return "ModelEdit{" + describe() + "}";
    }
}
