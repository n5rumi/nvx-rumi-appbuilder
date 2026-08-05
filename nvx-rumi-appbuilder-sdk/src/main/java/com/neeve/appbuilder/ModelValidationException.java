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

import com.neeve.appbuilder.model.ValidationResult;
import com.neeve.appbuilder.model.ValidationResult.ValidationError;

import java.nio.file.Path;
import java.util.stream.Collectors;

/**
 * Thrown when a model edit would produce an invalid model file. The edit is
 * rejected before anything is written, so the file on disk is untouched.
 *
 * <p>Extends {@link IllegalStateException} so the REST layer's existing
 * mapping renders it as {@code 422 Unprocessable}, the same status the
 * referential-safety checks already use.
 *
 * <p>The message names the file, the offending element and the rule, because
 * the caller is usually an agent that must act on it without reading a Maven
 * log — which is the entire point of validating here rather than letting ADM
 * codegen discover it three steps later.
 */
public class ModelValidationException extends IllegalStateException {

    private static final long serialVersionUID = 1L;

    private final transient ValidationResult result;
    private final transient Path modelFile;

    public ModelValidationException(Path modelFile, ValidationResult result) {
        super(buildMessage(modelFile, result));
        this.modelFile = modelFile;
        this.result = result;
    }

    /** The full validation result, including any warnings. */
    public ValidationResult getResult() {
        return result;
    }

    /** The model file the rejected edit targeted. */
    public Path getModelFile() {
        return modelFile;
    }

    private static String buildMessage(Path modelFile, ValidationResult result) {
        String detail = result.getErrors().stream()
            .filter(e -> e.getSeverity() != ValidationError.Severity.WARNING)
            .map(ModelValidationException::describe)
            .collect(Collectors.joining("; "));
        return "the edit would make " + modelFile + " invalid, so it was not written: " + detail;
    }

    private static String describe(ValidationError e) {
        // Line numbers come from the schema layer; the semantic layer reports
        // -1 because it works on a DOM, where the source position is gone.
        return e.getLine() > 0
            ? "line " + e.getLine() + ": " + e.getMessage()
            : e.getMessage();
    }
}
