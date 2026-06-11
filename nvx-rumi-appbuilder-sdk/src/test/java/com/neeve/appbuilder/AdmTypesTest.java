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

import org.junit.Test;

import static org.junit.Assert.*;

public class AdmTypesTest {

    @Test
    public void normalizes_lowercase_java_primitives_to_canonical_adml() {
        assertEquals("Long", AdmTypes.normalizeFieldType("long"));
        assertEquals("Integer", AdmTypes.normalizeFieldType("int"));
        assertEquals("Integer", AdmTypes.normalizeFieldType("integer"));
        assertEquals("Double", AdmTypes.normalizeFieldType("double"));
        assertEquals("Boolean", AdmTypes.normalizeFieldType("boolean"));
        assertEquals("String", AdmTypes.normalizeFieldType("string"));
        assertEquals("Char", AdmTypes.normalizeFieldType("char"));
    }

    @Test
    public void leaves_canonical_names_unchanged() {
        assertEquals("Long", AdmTypes.normalizeFieldType("Long"));
        assertEquals("String", AdmTypes.normalizeFieldType("String"));
    }

    @Test
    public void passes_through_entity_and_unknown_type_references() {
        // User-defined types (entity/message names) must not be guessed at.
        assertEquals("Money", AdmTypes.normalizeFieldType("Money"));
        assertEquals("com.example.Foo", AdmTypes.normalizeFieldType("com.example.Foo"));
    }

    @Test
    public void handles_null_and_blank() {
        assertNull(AdmTypes.normalizeFieldType(null));
        assertEquals("  ", AdmTypes.normalizeFieldType("  "));
    }

    @Test
    public void trims_before_matching() {
        assertEquals("Long", AdmTypes.normalizeFieldType("  long  "));
    }
}
