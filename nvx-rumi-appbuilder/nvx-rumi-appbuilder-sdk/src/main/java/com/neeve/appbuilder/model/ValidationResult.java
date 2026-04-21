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
package com.neeve.appbuilder.model;

import java.util.Collections;
import java.util.List;
import java.util.Objects;

/**
 * Result of validating an X-DDL document against the schema.
 */
public final class ValidationResult {
    private final boolean ok;
    private final List<ValidationError> errors;

    public ValidationResult(boolean ok, List<ValidationError> errors) {
        this.ok = ok;
        this.errors = Collections.unmodifiableList(Objects.requireNonNull(errors, "errors"));
    }

    public boolean isOk() {
        return ok;
    }

    public List<ValidationError> getErrors() {
        return errors;
    }

    @Override
    public String toString() {
        return "ValidationResult{ok=" + ok + ", errors=" + errors.size() + "}";
    }

    /** Single validation-failure record. */
    public static final class ValidationError {
        public enum Severity { WARNING, ERROR, FATAL }

        private final Severity severity;
        private final int line;
        private final int column;
        private final String message;

        public ValidationError(Severity severity, int line, int column, String message) {
            this.severity = Objects.requireNonNull(severity, "severity");
            this.line = line;
            this.column = column;
            this.message = Objects.requireNonNull(message, "message");
        }

        public Severity getSeverity() {
            return severity;
        }

        public int getLine() {
            return line;
        }

        public int getColumn() {
            return column;
        }

        public String getMessage() {
            return message;
        }

        @Override
        public String toString() {
            return severity + " at " + line + ":" + column + " — " + message;
        }
    }
}
