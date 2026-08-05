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

import javax.xml.XMLConstants;
import javax.xml.transform.stream.StreamSource;
import javax.xml.validation.Schema;
import javax.xml.validation.SchemaFactory;
import java.io.IOException;
import java.io.InputStream;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

/**
 * The Rumi XML schemas bundled in the SDK JAR, and the single place that
 * loads them.
 *
 * <p>All three are unpacked at build time from the Rumi artifacts matching
 * {@code ${nvx.rumi.version}} (see the {@code maven-dependency-plugin}
 * {@code unpack-rumi-schemas} execution in the SDK POM) rather than being
 * checked into source control. That is deliberate: a hand-maintained copy
 * drifts silently in both directions — accepting what the platform rejects,
 * and rejecting what it accepts — and a validator trusted to be
 * authoritative while quietly a version behind is worse than no validator
 * at all. Because there is no checked-in copy, there is nothing to drift
 * and nothing to hand-edit.
 *
 * <p>Schemas are parsed once and cached; {@link Schema} is thread-safe.
 */
public final class Schemas {

    /** A bundled schema: its namespace, and where it lives on the classpath. */
    public enum Kind {
        /** X-DDL — deployment descriptors ({@code config.xml}). */
        X_DDL("/schemas/x-ddl.xsd", "http://www.neeveresearch.com/schema/x-ddl"),
        /** X-ADML — message and state models ({@code messages.xml}, {@code state.xml}). */
        X_ADML("/schemas/x-adml.xsd", "http://www.neeveresearch.com/schema/x-adml"),
        /** X-ASML — service API models ({@code api.xml}). */
        X_ASML("/schemas/x-asml.xsd", "http://www.neeveresearch.com/schema/x-asml");

        private final String resourcePath;
        private final String namespace;

        Kind(String resourcePath, String namespace) {
            this.resourcePath = resourcePath;
            this.namespace = namespace;
        }

        public String getResourcePath() { return resourcePath; }

        public String getNamespace() { return namespace; }
    }

    private static final Map<Kind, Schema> CACHE = new ConcurrentHashMap<>();

    private Schemas() {}

    /**
     * Look up the schema governing documents in {@code namespace}.
     *
     * @return the matching kind, or {@code null} if no bundled schema
     *         governs that namespace.
     */
    public static Kind forNamespace(String namespace) {
        if (namespace == null) {
            return null;
        }
        for (Kind kind : Kind.values()) {
            if (kind.getNamespace().equals(namespace)) {
                return kind;
            }
        }
        return null;
    }

    /**
     * Load (and cache) a bundled schema.
     *
     * @throws IOException if the schema is missing from the JAR or does not
     *         parse — meaning the build's unpack step did not produce it.
     */
    public static Schema load(Kind kind) throws IOException {
        Schema cached = CACHE.get(kind);
        if (cached != null) {
            return cached;
        }
        try (InputStream is = Schemas.class.getResourceAsStream(kind.getResourcePath())) {
            if (is == null) {
                throw new IOException(
                    "bundled schema " + kind + " not found on the classpath at "
                    + kind.getResourcePath() + ". It is unpacked at build time from the Rumi "
                    + "artifacts for ${nvx.rumi.version}; a missing schema means that step "
                    + "did not run or the artifact no longer ships it.");
            }
            SchemaFactory factory = SchemaFactory.newInstance(XMLConstants.W3C_XML_SCHEMA_NS_URI);
            Schema schema = factory.newSchema(new StreamSource(is));
            CACHE.put(kind, schema);
            return schema;
        } catch (IOException e) {
            throw e;
        } catch (Exception e) {
            throw new IOException("failed to load bundled schema " + kind, e);
        }
    }
}
