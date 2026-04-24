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

import com.fasterxml.jackson.databind.ObjectMapper;
import jakarta.ws.rs.Consumes;
import jakarta.ws.rs.Produces;
import jakarta.ws.rs.WebApplicationException;
import jakarta.ws.rs.core.MediaType;
import jakarta.ws.rs.core.MultivaluedMap;
import jakarta.ws.rs.ext.MessageBodyReader;
import jakarta.ws.rs.ext.MessageBodyWriter;
import jakarta.ws.rs.ext.Provider;

import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.lang.annotation.Annotation;
import java.lang.reflect.Type;

/**
 * Minimal Jackson JSON provider registered directly on the Jersey
 * {@code ResourceConfig}. Does the {@code application/json}
 * read/write for every resource without pulling
 * {@code jersey-media-json-jackson} (whose
 * {@code JacksonMapperConfigurator} hardcodes a reference to
 * {@code JakartaXmlBindAnnotationIntrospector}, forcing
 * {@code jakarta.xml.bind} 4.x on the classpath and colliding with
 * the 2.3.2 canonical Rumi dep).
 *
 * <p>Uses the shared {@link JacksonConfig}-configured
 * {@link ObjectMapper} so {@code Path} → string and DOM {@code Element}
 * → XML-string serialisation still apply everywhere.
 */
@Provider
@Produces(MediaType.APPLICATION_JSON)
@Consumes(MediaType.APPLICATION_JSON)
public final class JacksonJsonProvider
        implements MessageBodyWriter<Object>, MessageBodyReader<Object> {

    private static final ObjectMapper MAPPER = new JacksonConfig().getContext(Object.class);

    // ---- writer ------------------------------------------------------

    @Override
    public boolean isWriteable(Class<?> type, Type genericType, Annotation[] annotations, MediaType mediaType) {
        return mediaType == null || MediaType.APPLICATION_JSON_TYPE.isCompatible(mediaType);
    }

    @Override
    public long getSize(Object o, Class<?> type, Type genericType, Annotation[] annotations, MediaType mediaType) {
        return -1;
    }

    @Override
    public void writeTo(Object o, Class<?> type, Type genericType, Annotation[] annotations,
                        MediaType mediaType, MultivaluedMap<String, Object> httpHeaders,
                        OutputStream entityStream) throws IOException, WebApplicationException {
        MAPPER.writeValue(entityStream, o);
    }

    // ---- reader ------------------------------------------------------

    @Override
    public boolean isReadable(Class<?> type, Type genericType, Annotation[] annotations, MediaType mediaType) {
        return mediaType == null || MediaType.APPLICATION_JSON_TYPE.isCompatible(mediaType);
    }

    @Override
    public Object readFrom(Class<Object> type, Type genericType, Annotation[] annotations,
                           MediaType mediaType, MultivaluedMap<String, String> httpHeaders,
                           InputStream entityStream) throws IOException, WebApplicationException {
        // Prefer the generic type so parameterised collections (List<Foo>) deserialise correctly.
        if (genericType != null && genericType != type) {
            return MAPPER.readValue(entityStream, MAPPER.getTypeFactory().constructType(genericType));
        }
        return MAPPER.readValue(entityStream, type);
    }
}
