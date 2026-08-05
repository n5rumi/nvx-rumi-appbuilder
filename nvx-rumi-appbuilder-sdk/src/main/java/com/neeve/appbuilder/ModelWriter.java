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
import org.w3c.dom.Document;

import java.nio.file.Path;

/**
 * The single seam through which every model-file edit reaches disk.
 *
 * <p>All the model editors — {@link FieldEditor}, {@link MessageEditor},
 * {@link EntityEditor}, {@link CollectionEditor}, {@link ApiOperationEditor} —
 * write through {@link #saveValidated}, so validation is implemented once and
 * cannot be forgotten by a new editor that follows the same pattern.
 *
 * <h2>Validate first, then write</h2>
 *
 * The alternative — write, validate, roll back on failure — is a worse fit for
 * the actual caller. When an agent's tool call fails it retries; it rarely
 * cleans up. Leaving a broken model on disk between the failure and the retry
 * turns one bad edit into a project that no longer builds for any reason. So
 * a rejected edit never touches the file at all.
 */
public final class ModelWriter {

    private ModelWriter() {}

    /**
     * Validate {@code doc} as it would be written to {@code modelFile}, and
     * write it only if it is valid.
     *
     * @throws ModelValidationException if the document would be an invalid
     *         model. {@code modelFile} is left untouched.
     * @throws Exception if serialization or the write itself fails.
     */
    public static void saveValidated(Document doc, Path modelFile) throws Exception {
        saveValidated(doc, modelFile, false);
    }

    /**
     * As {@link #saveValidated(Document, Path)}, but skips the write when
     * {@code dryRun} is set.
     *
     * <p>Note that a dry run still <em>validates</em>. A dry run exists to
     * tell the caller what the real call would do, and agents are instructed
     * to prefer one before a destructive operation — so a dry run that
     * reported success and was then followed by a rejected write would be
     * actively misleading, which is worse than not offering the check.
     *
     * @throws ModelValidationException if the document would be an invalid
     *         model, on dry runs as well as real ones.
     */
    public static void saveValidated(Document doc, Path modelFile, boolean dryRun)
            throws Exception {
        ValidationResult result = ModelValidator.validatePending(doc, modelFile);
        if (!result.isOk()) {
            throw new ModelValidationException(modelFile, result);
        }
        if (!dryRun) {
            XmlDomUtils.saveXmlDocument(doc, modelFile);
        }
    }
}
