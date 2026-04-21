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
}
