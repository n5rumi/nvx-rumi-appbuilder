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

import com.neeve.appbuilder.rest.dto.ErrorResponse;
import jakarta.ws.rs.BadRequestException;
import jakarta.ws.rs.NotFoundException;
import jakarta.ws.rs.core.MediaType;
import jakarta.ws.rs.core.Response;
import jakarta.ws.rs.ext.Provider;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

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
    private static final Logger LOG = LoggerFactory.getLogger(ExceptionMapper.class);

    @Override
    public Response toResponse(Throwable t) {
        Mapping m = map(t);
        if (m.status >= 500) {
            LOG.error("Unhandled exception in App Builder REST handler", t);
        } else {
            LOG.debug("Handled exception {} -> {} {}", t.getClass().getSimpleName(), m.status, m.code);
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
        return new Mapping(500, "InternalError", "An internal error occurred");
    }

    private static String messageOr(Throwable t, String fallback) {
        String m = t.getMessage();
        return (m == null || m.isBlank()) ? fallback : m;
    }

    private record Mapping(int status, String code, String description) {}
}
