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
import org.xml.sax.ErrorHandler;
import org.xml.sax.SAXParseException;

import javax.xml.transform.stream.StreamSource;
import javax.xml.validation.Schema;
import javax.xml.validation.Validator;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;

/**
 * Validate an app's config.xml against the X-DDL schema (x-ddl.xsd).
 *
 * <p>The schema is unpacked at build time from the {@code nvx-rumi-ddl}
 * artifact matching {@code ${nvx.rumi.version}}, so it always describes the
 * Rumi version this builder targets. See {@link Schemas}.
 */
public final class ConfigValidator {

    private ConfigValidator() {}

    /**
     * Validate the app's config.xml against the X-DDL schema. Returns a
     * result with {@link ValidationResult#isOk()} true only when the
     * document parses without any schema violations.
     *
     * @throws IOException if config.xml is missing or the bundled XSD
     *         can't be loaded.
     */
    public static ValidationResult validate(Path appRoot) throws IOException {
        Path configPath = ConfigFragmentEditor.resolveConfigPath(appRoot);
        if (!Files.exists(configPath)) {
            throw new IOException("config.xml not found at " + configPath);
        }
        return validateFile(configPath);
    }

    /**
     * Validate an arbitrary X-DDL file against the bundled schema.
     * Useful for validating snippets or partial configs.
     */
    public static ValidationResult validateFile(Path xmlFile) throws IOException {
        Schema schema = Schemas.load(Schemas.Kind.X_DDL);
        Validator validator = schema.newValidator();

        List<ValidationError> errors = new ArrayList<>();
        validator.setErrorHandler(new ErrorHandler() {
            @Override
            public void warning(SAXParseException ex) {
                errors.add(toError(ValidationError.Severity.WARNING, ex));
            }
            @Override
            public void error(SAXParseException ex) {
                errors.add(toError(ValidationError.Severity.ERROR, ex));
            }
            @Override
            public void fatalError(SAXParseException ex) {
                errors.add(toError(ValidationError.Severity.FATAL, ex));
            }
        });

        try {
            validator.validate(new StreamSource(xmlFile.toFile()));
        } catch (SAXParseException e) {
            // Some parse errors short-circuit via exception rather than the ErrorHandler.
            if (errors.stream().noneMatch(err -> err.getMessage().equals(e.getMessage()))) {
                errors.add(toError(ValidationError.Severity.FATAL, e));
            }
        } catch (Exception e) {
            throw new IOException("failed to validate " + xmlFile, e);
        }

        boolean ok = errors.stream().noneMatch(
            err -> err.getSeverity() == ValidationError.Severity.ERROR
                || err.getSeverity() == ValidationError.Severity.FATAL);
        return new ValidationResult(ok, errors);
    }

    // --- internal -----------------------------------------------------

    private static ValidationError toError(ValidationError.Severity severity, SAXParseException ex) {
        return new ValidationError(severity, ex.getLineNumber(), ex.getColumnNumber(), ex.getMessage());
    }
}
