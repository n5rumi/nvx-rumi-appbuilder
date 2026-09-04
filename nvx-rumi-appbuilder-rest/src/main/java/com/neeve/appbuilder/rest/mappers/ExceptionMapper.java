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
package com.neeve.appbuilder.rest.mappers;

import com.fasterxml.jackson.core.JsonParseException;
import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.JsonMappingException;
import com.fasterxml.jackson.databind.exc.MismatchedInputException;
import com.fasterxml.jackson.databind.exc.UnrecognizedPropertyException;
import java.util.Collection;
import java.util.List;
import java.util.stream.Collectors;
import com.neeve.appbuilder.rest.Main;
import com.neeve.appbuilder.rest.dto.ErrorResponse;
import com.neeve.trace.Tracer;
import com.neeve.util.UtlThrowable;
import jakarta.ws.rs.BadRequestException;
import jakarta.ws.rs.NotFoundException;
import jakarta.ws.rs.core.MediaType;
import jakarta.ws.rs.core.Response;
import jakarta.ws.rs.ext.Provider;

import java.io.FileNotFoundException;
import java.nio.file.FileAlreadyExistsException;
import java.nio.file.NoSuchFileException;

/**
 * Central exception-to-HTTP mapper. Translates SDK and JAX-RS exceptions
 * into the {@link ErrorResponse} envelope with a semantic HTTP status
 * code.
 *
 * <p>Stable string codes clients can branch on, plus a human-readable
 * description. Implemented as a JAX-RS {@code ExceptionMapper} because
 * the SDK throws exceptions (Java-library style).
 *
 * <p>Status mapping:
 *
 * <table>
 *   <caption>HTTP status mapping</caption>
 *   <tr><th>Status</th><th>Code</th><th>Source</th></tr>
 *   <tr><td>400</td><td>BadRequest</td><td>{@link IllegalArgumentException}, JAX-RS {@link BadRequestException}</td></tr>
 *   <tr><td>404</td><td>NotFound</td><td>{@link NoSuchFileException}, {@link FileNotFoundException}, JAX-RS {@link NotFoundException}</td></tr>
 *   <tr><td>409</td><td>Conflict</td><td>{@link FileAlreadyExistsException}, {@link IllegalStateException} with "already exists"</td></tr>
 *   <tr><td>422</td><td>Unprocessable</td><td>{@link IllegalStateException} (non-conflict variants)</td></tr>
 *   <tr><td>500</td><td>InternalError</td><td>Everything else</td></tr>
 * </table>
 *
 * <p>Unhandled (500) exceptions are logged at ERROR with their stack
 * trace so operators can diagnose from service logs; the response body
 * deliberately does not include internal details.
 */
@Provider
public class ExceptionMapper implements jakarta.ws.rs.ext.ExceptionMapper<Throwable> {
    // Same named singleton configured in Main; Tracer.get resolves to
    // the one instance whose level Main set from config.
    private static final Tracer TRACER = Tracer.get(Main.NAME);

    @Override
    public Response toResponse(Throwable t) {
        Mapping m = map(t);
        if (m.status >= 500) {
            TRACER.log("Unhandled exception in App Builder REST handler: "
                + UtlThrowable.prepareStackTrace(t), Tracer.Level.SEVERE);
        } else if (TRACER.isEnabled(Tracer.Level.FINE)) {
            TRACER.log("Handled exception " + t.getClass().getSimpleName()
                + " -> " + m.status + " " + m.code, Tracer.Level.FINE);
        }
        return Response.status(m.status)
            .type(MediaType.APPLICATION_JSON)
            .entity(new ErrorResponse(m.code, m.description))
            .build();
    }

    private static Mapping map(Throwable t) {
        // JAX-RS exceptions — let their status stand, wrap description.
        if (t instanceof NotFoundException) {
            return new Mapping(404, "NotFound", messageOr(t, "Resource not found"));
        }
        if (t instanceof BadRequestException) {
            return new Mapping(400, "BadRequest", messageOr(t, "Malformed request"));
        }
        if (t instanceof IllegalArgumentException) {
            return new Mapping(400, "BadRequest", messageOr(t, "Invalid argument"));
        }
        if (t instanceof NoSuchFileException || t instanceof FileNotFoundException) {
            return new Mapping(404, "NotFound", messageOr(t, "File or path not found"));
        }
        if (t instanceof FileAlreadyExistsException) {
            return new Mapping(409, "Conflict", messageOr(t, "Resource already exists"));
        }
        if (t instanceof IllegalStateException) {
            // Conflict vs. unprocessable: the SDK uses IllegalStateException for both
            // "X already exists" (409) and "operation can't proceed in current state" (422).
            // Heuristic on the message text keeps the mapper simple without a custom
            // exception hierarchy; revisit if false positives appear.
            String msg = t.getMessage() == null ? "" : t.getMessage().toLowerCase();
            if (msg.contains("already exists") || msg.contains("already present")
                || msg.contains("conflict")) {
                return new Mapping(409, "Conflict", messageOr(t, "Resource already exists"));
            }
            return new Mapping(422, "Unprocessable", messageOr(t, "Operation not allowed in current state"));
        }
        // A body we could not READ is the caller's problem, so name what was
        // wrong with it. Deliberately last, and deliberately narrow.
        //
        // Last, because a JAX-RS exception carrying a Jackson cause is still a
        // 404: running this first turned NotFoundException("no such app", jsonCause)
        // into a 400.
        //
        // Narrow, because Jackson raises on the way OUT too. JsonMappingException
        // is thrown by both sides, so matching that parent classified a failing
        // response serialization as a bad request -- wrong status, and it echoed
        // the exception text into the body of a class whose javadoc promises not
        // to leak internals. It also silenced the fault: a mapping under 500
        // skips the SEVERE log in toResponse, so a genuine server error stopped
        // reaching the service log at all. MismatchedInputException and
        // JsonParseException are raised only while reading.
        DeserializationFailure bind = firstReadFailure(t);
        if (bind != null) {
            return new Mapping(400, "BadRequest", describeBindFailure(bind.cause));
        }

        return new Mapping(500, "InternalError", "An internal error occurred");
    }

    /** Carries the matched cause so the caller cannot re-widen the type by accident. */
    private record DeserializationFailure(JsonProcessingException cause) {}

    /**
     * The first READ-side Jackson failure in the cause chain, or null.
     *
     * <p>Bounded rather than cycle-checked: {@code initCause} refuses {@code this}
     * but permits a longer loop, and a non-terminating walk here would hang the
     * request thread inside the exception mapper. Real chains are a few deep.
     */
    private static DeserializationFailure firstReadFailure(Throwable t) {
        int depth = 0;
        for (Throwable c = t; c != null && depth < MAX_CAUSE_DEPTH; c = c.getCause(), depth++) {
            if (c instanceof MismatchedInputException || c instanceof JsonParseException) {
                return new DeserializationFailure((JsonProcessingException) c);
            }
        }
        return null;
    }

    private static final int MAX_CAUSE_DEPTH = 16;

    /**
     * Name the property and, where Jackson knows it, where it sat in the
     * payload. The original message carries a source location that means
     * nothing to a caller, so only the useful half is passed on.
     */
    private static String describeBindFailure(JsonProcessingException e) {
        StringBuilder sb = new StringBuilder("Could not read the request body");
        if (e instanceof UnrecognizedPropertyException) {
            UnrecognizedPropertyException u = (UnrecognizedPropertyException) e;
            sb.append(": unknown property '").append(u.getPropertyName()).append("'");
            String path = pathOf(u);
            if (!path.isEmpty()) sb.append(" at ").append(path);
            Collection<Object> known = u.getKnownPropertyIds();
            if (known != null && !known.isEmpty()) {
                sb.append(". Known properties: ");
                sb.append(known.stream().map(String::valueOf).sorted()
                               .collect(Collectors.joining(", ")));
            }
            return sb.toString();
        }
        if (e instanceof JsonMappingException) {
            String path = pathOf((JsonMappingException) e);
            if (!path.isEmpty()) sb.append(" at ").append(path);
            String m = e.getOriginalMessage();
            if (m != null && !m.isBlank()) sb.append(": ").append(m);
            return sb.toString();
        }
        String m = e.getOriginalMessage();
        return m == null || m.isBlank() ? sb.toString() : sb + ": " + m;
    }

    private static String pathOf(JsonMappingException e) {
        List<JsonMappingException.Reference> refs = e.getPath();
        if (refs == null || refs.isEmpty()) return "";
        StringBuilder p = new StringBuilder();
        for (JsonMappingException.Reference r : refs) {
            if (r.getFieldName() != null) {
                if (p.length() > 0) p.append('.');
                p.append(r.getFieldName());
            } else if (r.getIndex() >= 0) {
                p.append('[').append(r.getIndex()).append(']');
            }
        }
        return p.toString();
    }

    private static String messageOr(Throwable t, String fallback) {
        String m = t.getMessage();
        return (m == null || m.isBlank()) ? fallback : m;
    }

    private record Mapping(int status, String code, String description) {}
}
