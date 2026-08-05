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
package com.neeve.appbuilder.test;

import com.neeve.appbuilder.ConfigValidator;
import com.neeve.appbuilder.ModelValidator;
import com.neeve.appbuilder.Schemas;
import com.neeve.appbuilder.XmlDomUtils;
import com.neeve.appbuilder.model.ChangeSet;
import com.neeve.appbuilder.model.ValidationResult;
import org.w3c.dom.Document;
import org.w3c.dom.Element;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Set;
import java.util.stream.Collectors;
import java.util.stream.Stream;

/**
 * Assert that a scaffolded project is still <em>valid</em> — not merely that
 * an introspector can still read it (RUMI-378).
 *
 * <p>That distinction is the whole point. The editor tests re-read their
 * output through the same component that wrote it, so they prove the output
 * is parseable by its own writer and nothing more. A file that is well-formed
 * but schema-invalid sails straight through, which is exactly how a field
 * attribute that ADML does not define survived in fixtures across the SDK and
 * REST suites until schema validation was switched on.
 *
 * <p>Every file is dispatched to the right validator by the namespace of its
 * root element, so this works on config, message, state and API models alike
 * without the caller having to say which is which.
 */
public final class ProjectValidity {

    private ProjectValidity() {}

    /**
     * Validate every file a {@link ChangeSet} created or modified.
     *
     * <p>This is the assertion to reach for after a mutating operation: the
     * change set already names precisely the files that operation touched, so
     * coverage follows the operation rather than a hand-maintained list that
     * can fall behind it.
     *
     * @throws AssertionError if any touched file is invalid.
     */
    public static void assertChangeSetValid(ChangeSet changeSet) throws IOException {
        List<Path> touched = new ArrayList<>();
        touched.addAll(changeSet.getFilesCreated());
        touched.addAll(changeSet.getFilesModified());

        List<String> failures = new ArrayList<>();
        for (Path p : new LinkedHashSet<>(touched)) {
            // A dry run reports what it would touch without writing it.
            if (!Files.exists(p)) {
                continue;
            }
            describeIfInvalid(p).ifPresent(failures::add);
        }
        if (!failures.isEmpty()) {
            throw new AssertionError("the operation left "
                + failures.size() + " file(s) invalid:\n  " + String.join("\n  ", failures));
        }
    }

    /**
     * Validate every XML file under {@code appRoot} that a bundled schema
     * governs. Slower than {@link #assertChangeSetValid}, and used where an
     * operation's blast radius is wider than the files it names — a service
     * removal, say, which reverts config fragments and parent-POM references.
     *
     * @throws AssertionError if any governed file is invalid.
     */
    public static void assertProjectValid(Path appRoot) throws IOException {
        List<String> failures = new ArrayList<>();
        try (Stream<Path> walk = Files.walk(appRoot)) {
            List<Path> xml = walk
                .filter(Files::isRegularFile)
                .filter(p -> p.getFileName().toString().endsWith(".xml"))
                .filter(p -> !p.toString().contains("/target/"))
                .collect(Collectors.toList());
            for (Path p : xml) {
                describeIfInvalid(p).ifPresent(failures::add);
            }
        }
        if (!failures.isEmpty()) {
            throw new AssertionError("the project has "
                + failures.size() + " invalid file(s):\n  " + String.join("\n  ", failures));
        }
    }

    /**
     * The set of files under {@code appRoot} a bundled schema governs. Useful
     * for asserting that a test actually exercised something.
     */
    public static Set<Path> governedFiles(Path appRoot) throws IOException {
        Set<Path> out = new LinkedHashSet<>();
        try (Stream<Path> walk = Files.walk(appRoot)) {
            walk.filter(Files::isRegularFile)
                .filter(p -> p.getFileName().toString().endsWith(".xml"))
                .filter(p -> !p.toString().contains("/target/"))
                .filter(p -> schemaKindOf(p) != null)
                .forEach(out::add);
        }
        return out;
    }

    // --- internal -----------------------------------------------------

    private static java.util.Optional<String> describeIfInvalid(Path file) throws IOException {
        Schemas.Kind kind = schemaKindOf(file);
        if (kind == null) {
            // A pom.xml or an assembly descriptor — no Rumi schema governs it,
            // so silence here is correct rather than a gap.
            return java.util.Optional.empty();
        }
        ValidationResult result = kind == Schemas.Kind.X_DDL
            ? ConfigValidator.validateFile(file)
            : ModelValidator.validateFile(file);
        if (result.isOk()) {
            return java.util.Optional.empty();
        }
        String detail = result.getErrors().stream()
            .map(e -> e.getMessage())
            .collect(Collectors.joining("; "));
        return java.util.Optional.of(file + " — " + detail);
    }

    /** Which bundled schema governs this file, by its root-element namespace. */
    private static Schemas.Kind schemaKindOf(Path file) {
        try {
            Document doc = XmlDomUtils.parseXmlDocument(file);
            Element root = doc.getDocumentElement();
            return root == null ? null : Schemas.forNamespace(root.getNamespaceURI());
        } catch (Exception e) {
            return null;
        }
    }
}
