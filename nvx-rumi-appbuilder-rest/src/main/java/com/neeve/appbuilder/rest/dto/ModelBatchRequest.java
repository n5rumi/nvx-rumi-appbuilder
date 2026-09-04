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
import com.neeve.appbuilder.model.ModelEdit;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Map;
import java.util.Locale;
import java.util.stream.Collectors;

/** Request body for {@code POST /v1/model/batch} (RUMI-412). */
public final class ModelBatchRequest {

    /** One edit. {@code kind} is message|message_entity|state_entity|collection|fields. */
    public static final class Edit {
        private final String kind;
        private final String service;
        private final String name;
        private final String scope;
        private final List<FieldSpec> fields;
        private final String is;
        private final String contains;
        /**
         * Entity-level attributes for a state_entity or message_entity edit —
         * {@code asEmbedded="true"} above all, which is how the ADM language
         * declares an embedded entity. Absent here, the batch path silently
         * dropped it while the per-element tools accepted it (RUMI-424).
         */
        private final Map<String, String> attributes;

        @JsonCreator
        public Edit(@JsonProperty("kind") String kind,
                    @JsonProperty("service") String service,
                    @JsonProperty("name") String name,
                    @JsonProperty("scope") String scope,
                    @JsonProperty("fields") List<FieldSpec> fields,
                    @JsonProperty("is") String is,
                    @JsonProperty("contains") String contains,
                    @JsonProperty("attributes") Map<String, String> attributes) {
            this.kind = kind;
            this.service = service;
            this.name = name;
            this.scope = scope;
            this.fields = fields == null ? Collections.emptyList() : fields;
            this.is = is;
            this.contains = contains;
            this.attributes = attributes == null ? Collections.emptyMap() : attributes;
        }

        public String getKind() { return kind; }
        public String getService() { return service; }
        public String getName() { return name; }
        public String getScope() { return scope; }
        public List<FieldSpec> getFields() { return fields; }
        public String getIs() { return is; }
        public String getContains() { return contains; }
        public Map<String, String> getAttributes() { return attributes; }

        ModelEdit toSdk() {
            if (kind == null) {
                throw new IllegalArgumentException(
                    "each edit needs a kind (message|message_entity|state_entity|collection|fields)");
            }
            ModelEdit.Kind k;
            try {
                k = ModelEdit.Kind.valueOf(kind.trim().toUpperCase(Locale.ROOT));
            } catch (IllegalArgumentException e) {
                throw new IllegalArgumentException("unknown edit kind '" + kind
                    + "' (message|message_entity|state_entity|collection|fields)");
            }
            if (name == null || name.isBlank()) {
                throw new IllegalArgumentException("edit of kind " + kind + " needs a name");
            }
            return new ModelEdit(k, service, name, scope,
                fields.stream().map(FieldSpec::toSdk).collect(Collectors.toList()),
                is, contains, attributes);
        }
    }

    private final List<Edit> edits;

    @JsonCreator
    public ModelBatchRequest(@JsonProperty("edits") List<Edit> edits) {
        this.edits = edits == null ? Collections.emptyList() : edits;
    }

    public List<Edit> getEdits() { return edits; }

    /**
     * Convert every edit up front, so a malformed one is a 400 before any of
     * them is applied rather than a rollback partway through.
     */
    public List<ModelEdit> toSdk() {
        List<ModelEdit> out = new ArrayList<>();
        for (Edit e : edits) out.add(e.toSdk());
        return out;
    }
}
