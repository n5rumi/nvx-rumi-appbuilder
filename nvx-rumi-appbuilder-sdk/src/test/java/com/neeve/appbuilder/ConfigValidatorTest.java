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
import org.junit.After;
import org.junit.Before;
import org.junit.Test;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.stream.Stream;

import static org.junit.Assert.*;

public class ConfigValidatorTest {

    private Path tempDir;
    private Path appRoot;

    @Before
    public void setUp() throws IOException {
        tempDir = Files.createTempDirectory("cfgval-");
        appRoot = PhaseBTestSupport.scaffoldApp(tempDir, "trading", "com.example.trading");
    }

    @After
    public void tearDown() throws IOException {
        if (tempDir != null && Files.exists(tempDir)) {
            try (Stream<Path> walk = Files.walk(tempDir)) {
                walk.sorted((a, b) -> b.getNameCount() - a.getNameCount())
                    .forEach(p -> { try { Files.deleteIfExists(p); } catch (IOException ignored) {} });
            }
        }
    }

    @Test
    public void validate_throwsWhenConfigMissing() throws Exception {
        try {
            ConfigValidator.validate(appRoot);
            fail("expected IOException");
        } catch (IOException expected) {
            assertTrue(expected.getMessage().contains("config.xml not found"));
        }
    }

    @Test
    public void validateFile_returnsErrorsForMalformedXml() throws Exception {
        Path file = tempDir.resolve("broken.xml");
        Files.writeString(file, "<not-even-close-to-xddl/>");
        ValidationResult r = ConfigValidator.validateFile(file);
        assertFalse(r.isOk());
        assertFalse(r.getErrors().isEmpty());
    }

    @Test
    public void validateFile_syntacticallyInvalidXmlIsFatal() throws Exception {
        Path file = tempDir.resolve("broken.xml");
        Files.writeString(file, "<model>");  // unclosed tag
        ValidationResult r = ConfigValidator.validateFile(file);
        assertFalse(r.isOk());
        assertTrue(r.getErrors().stream().anyMatch(
            e -> e.getSeverity() == ValidationResult.ValidationError.Severity.FATAL));
    }

    // ---- RUMI-442 AC4: two same-named siblings under one scope ------------
    //
    // Written against validateFile with a hand-built document, matching the
    // rest of this class: PhaseBTestSupport.scaffoldApp does not produce a
    // config.xml, so ConfigValidator.validate(appRoot) has nothing to read.

    private static final String XDDL = "http://www.neeveresearch.com/schema/x-ddl";

    private Path writeDdl(String name, String body) throws IOException {
        Path f = tempDir.resolve(name);
        Files.writeString(f, "<x-ddl xmlns=\"" + XDDL + "\">" + body + "</x-ddl>");
        return f;
    }

    private static boolean hasDuplicateFinding(ValidationResult r, String named) {
        return r.getErrors().stream().anyMatch(
            e -> e.getMessage() != null
                 && e.getMessage().contains("elements named")
                 && e.getMessage().contains(named));
    }

    /**
     * The schema cannot catch this. X-DDL does not declare {@code name} as a
     * key, so a second {@code <app name="x">} is structurally valid and only
     * shows up at boot, as whichever copy the loader picks.
     *
     * <p>Still needed after addFragment stopped CREATING duplicates: a
     * config.xml written before that fix, or by hand, still has them, and this
     * is the only thing that will say so.
     */
    @Test
    public void validate_reportsTwoSameNamedSiblingsUnderOneScope() throws Exception {
        Path f = writeDdl("dup.xml",
            "<apps><templates>"
            + "<app name=\"twin\"/><app name=\"twin\"/>"
            + "</templates></apps>");
        ValidationResult r = ConfigValidator.validateFile(f);

        // Asserted on the SPECIFIC finding, not on isOk(). A hand-built X-DDL
        // may also draw schema complaints, and isOk() would then be false for
        // a reason that has nothing to do with this check.
        assertTrue("the duplicate must be reported and must name it: " + r.getErrors(),
                   hasDuplicateFinding(r, "twin"));
        assertTrue("and say why it matters, not just that it is a duplicate: " + r.getErrors(),
                   r.getErrors().stream().anyMatch(
                       e -> e.getMessage() != null && e.getMessage().contains("document order")));
        assertFalse("a duplicate must make the document invalid", r.isOk());
    }

    /**
     * The same name under two DIFFERENT scopes is legitimate X-DDL -- one app
     * template per profile is the normal shape -- so the check is per parent.
     * Without this, the fix for RUMI-442 would flag every profiled config.
     */
    @Test
    public void validate_allowsTheSameNameUnderDifferentScopes() throws Exception {
        Path f = writeDdl("scoped.xml",
            "<apps><templates><app name=\"shared\"/></templates></apps>"
            + "<profiles><profile name=\"standalone\"><apps><templates>"
            + "<app name=\"shared\"/>"
            + "</templates></apps></profile></profiles>");
        ValidationResult r = ConfigValidator.validateFile(f);

        assertFalse("one name per scope in two scopes is not a duplicate: " + r.getErrors(),
                    hasDuplicateFinding(r, "shared"));
    }

    /** A named element and an UNNAMED sibling of the same tag are not a pair. */
    @Test
    public void validate_doesNotFlagUnnamedSiblingsOfTheSameTag() throws Exception {
        Path f = writeDdl("unnamed.xml",
            "<apps><templates><app name=\"holder\">"
            + "<factory id=\"1\"/><factory id=\"2\"/>"
            + "</app></templates></apps>");
        ValidationResult r = ConfigValidator.validateFile(f);
        assertTrue("no element named \"\" should ever be reported: " + r.getErrors(),
                   r.getErrors().stream().noneMatch(
                       e -> e.getMessage() != null && e.getMessage().contains("elements named \"\"")));
    }
}
