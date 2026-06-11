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
package com.neeve.appbuilder;

import java.util.Map;

/**
 * X-ADML scalar type-name helper.
 *
 * <p>ADML field types are the <em>capitalized</em> names of the
 * {@code com.neeve.adm.AdmPrimitive.Type} enum — {@code Byte}, {@code Short},
 * {@code Integer}, {@code Long}, {@code Float}, {@code Double}, {@code Boolean},
 * {@code Char}, {@code String}, {@code Date}, {@code Currency}, {@code UUID}.
 * The ADM parser aliases a few lowercase Java-primitive spellings (e.g.
 * {@code int}, {@code char}) but not all of them — most notably {@code long}
 * is rejected outright. A field type that compiles in the SDK's own XML can
 * therefore still blow up at ADM/ASM codegen time in the generated app.
 *
 * <p>{@link #normalizeFieldType(String)} maps the lowercase Java-primitive
 * spellings to their canonical ADML names so callers can pass the spelling
 * they're used to. Anything that isn't a recognized scalar alias — already
 * canonical scalars, array types, and entity/message references — is returned
 * unchanged (the builder must not guess at user-defined type names).
 */
final class AdmTypes {
    private AdmTypes() {}

    /** Lowercase Java-primitive (and a couple of common) spellings → canonical ADML scalar name. */
    private static final Map<String, String> ALIASES = Map.ofEntries(
        Map.entry("boolean", "Boolean"),
        Map.entry("byte",    "Byte"),
        Map.entry("char",    "Char"),
        Map.entry("short",   "Short"),
        Map.entry("int",     "Integer"),
        Map.entry("integer", "Integer"),
        Map.entry("long",    "Long"),
        Map.entry("float",   "Float"),
        Map.entry("double",  "Double"),
        Map.entry("string",  "String"),
        Map.entry("date",    "Date"),
        Map.entry("currency","Currency"),
        Map.entry("uuid",    "UUID")
    );

    /**
     * Return the canonical ADML scalar name for a field type, or the input
     * unchanged when it isn't a recognized scalar alias (entity/message
     * references, array types, and already-canonical names pass through).
     * Null/blank is returned unchanged.
     */
    static String normalizeFieldType(String type) {
        if (type == null) return null;
        String trimmed = type.trim();
        if (trimmed.isEmpty()) return type;
        return ALIASES.getOrDefault(trimmed.toLowerCase(), trimmed);
    }
}
