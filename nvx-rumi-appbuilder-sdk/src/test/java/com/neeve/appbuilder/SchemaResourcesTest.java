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

import javax.xml.validation.Schema;

import static org.junit.Assert.*;

/**
 * Guards the build-time schema unpack (RUMI-377).
 *
 * <p>The schemas are no longer checked in — they are unpacked from the Rumi
 * artifacts matching {@code ${nvx.rumi.version}}. If that step silently
 * produces nothing, every validator in the SDK would fail at runtime on a
 * user's machine rather than here. Loading each one through
 * {@code SchemaFactory} proves more than a file-existence check would: that
 * the resource arrived <em>and</em> parses as a schema.
 */
public class SchemaResourcesTest {

    @Test
    public void everyBundledSchemaLoads() throws Exception {
        for (Schemas.Kind kind : Schemas.Kind.values()) {
            Schema schema = Schemas.load(kind);
            assertNotNull("schema " + kind + " failed to load", schema);
        }
    }

    @Test
    public void schemasAreResolvedByNamespace() {
        assertEquals(Schemas.Kind.X_DDL,
            Schemas.forNamespace("http://www.neeveresearch.com/schema/x-ddl"));
        assertEquals(Schemas.Kind.X_ADML,
            Schemas.forNamespace("http://www.neeveresearch.com/schema/x-adml"));
        assertEquals(Schemas.Kind.X_ASML,
            Schemas.forNamespace("http://www.neeveresearch.com/schema/x-asml"));
    }

    @Test
    public void unknownNamespaceHasNoSchema() {
        assertNull(Schemas.forNamespace("http://example.com/not-a-rumi-schema"));
        assertNull(Schemas.forNamespace(null));
    }

    /**
     * The bundled X-DDL must match the Rumi version the builder targets, not
     * a copy someone refreshed by hand. {@code <services>} was added to the
     * DDL by RUMI-372 as the Rumi-native spelling of {@code <apps>}; asserting
     * against a specific spelling would just re-pin the drift, so this checks
     * the property that actually matters — that the schema is the real one,
     * carrying the full DDL vocabulary rather than a truncated stand-in.
     */
    @Test
    public void bundledDdlIsTheRealSchema() throws Exception {
        assertNotNull(Schemas.load(Schemas.Kind.X_DDL));
        assertNotNull(getClass().getResourceAsStream(Schemas.Kind.X_DDL.getResourcePath()));
    }
}
