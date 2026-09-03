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
package com.neeve.appbuilder.rest;

import com.fasterxml.jackson.annotation.JsonInclude;
import com.fasterxml.jackson.core.JsonGenerator;
import com.fasterxml.jackson.databind.JsonSerializer;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.SerializerProvider;
import com.fasterxml.jackson.databind.module.SimpleModule;
import com.neeve.appbuilder.model.HandlerDef;
import jakarta.ws.rs.ext.ContextResolver;
import jakarta.ws.rs.ext.Provider;

import java.io.IOException;
import java.nio.file.Path;

/**
 * Jersey {@link ContextResolver} that supplies a shared {@link ObjectMapper}
 * with a {@link Path}-as-string serializer so SDK types containing
 * {@code Path} fields ({@code ServiceInfo.moduleDir}, {@code ChangeSet}
 * file lists) serialise cleanly to JSON strings.
 *
 * <p>Without this, Jackson treats {@code Path} as a bean and tries to
 * introspect its internal accessors — which both breaks and leaks
 * implementation details.
 */
@Provider
public class JacksonConfig implements ContextResolver<ObjectMapper> {
    private final ObjectMapper mapper;

    public JacksonConfig() {
        this.mapper = new ObjectMapper();
        SimpleModule m = new SimpleModule("rumi-appbuilder-rest");
        m.addSerializer(Path.class, new JsonSerializer<Path>() {
            @Override
            public void serialize(Path value, JsonGenerator gen, SerializerProvider p) throws IOException {
                gen.writeString(value == null ? null : value.toString());
            }
        });
        mapper.registerModule(m);
        // A HandlerDef carries a body only when one was asked for, so every
        // other response would otherwise ship "body":null on every handler --
        // including GET /v1/services/{svc}. Scoped to this type rather than set
        // globally: flipping null-suppression for every DTO is a wider change
        // than it looks, and would drop keys clients may rely on being present.
        // Receipt trimming is NOT done here. A bean-level override does not
        // reach a nested type, so suppressing empties on BatchResult still let
        // every BatchResult.Item ship "reason":null; and a mapper rule cannot
        // see the request, so detail=true would restore the paths and not the
        // keys. CompactReceipt does both, chosen per-request by
        // CompactResultFilter (RUMI-414).
        mapper.configOverride(HandlerDef.class)
              .setInclude(JsonInclude.Value.construct(JsonInclude.Include.NON_NULL,
                                                      JsonInclude.Include.ALWAYS));
    }

    @Override
    public ObjectMapper getContext(Class<?> type) { return mapper; }
}
