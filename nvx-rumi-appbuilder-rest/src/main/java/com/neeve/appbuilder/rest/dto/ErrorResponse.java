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

/**
 * Envelope returned on every error: one stable {@code code} (enum-like
 * string clients can branch on) plus one human-readable
 * {@code description}.
 *
 * <p>Serialised as:
 * <pre>
 * {
 *   "error": {
 *     "code": "AppNotFound",
 *     "description": "No Rumi app found at /tmp/foo"
 *   }
 * }
 * </pre>
 *
 * <p>Keep the code vocabulary short and semantic. The full code set is
 * enumerated under the {@code AppBuilderErrorCodes} docs (see
 * {@code RestExceptionMapper}).
 */
public final class ErrorResponse {
    private final Error error;

    public ErrorResponse(String code, String description) {
        this.error = new Error(code, description);
    }

    public Error getError() { return error; }

    public static final class Error {
        private final String code;
        private final String description;

        public Error(String code, String description) {
            this.code = code;
            this.description = description;
        }

        public String getCode() { return code; }
        public String getDescription() { return description; }
    }
}
