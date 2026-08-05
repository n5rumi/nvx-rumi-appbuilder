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

import io.github.classgraph.ClassGraph;
import io.github.classgraph.Resource;
import io.github.classgraph.ScanResult;

import java.io.IOException;
import java.io.InputStream;
import java.nio.file.*;
import java.util.*;

class TemplateProcessor {
    static Path extractTemplateDirectory(String templateDirPrefix, String templatePath, boolean templatePathCanBeAbsent) throws IOException {
        Path tempDir = Files.createTempDirectory(templateDirPrefix + "-");
        try (ScanResult scanResult = new ClassGraph().acceptPaths(templatePath).scan()) {
            var resources = scanResult.getAllResources();
            if (resources.isEmpty()) {
                if (templatePathCanBeAbsent) {
                    return null;
                }
                throw new InternalError("Template path not found or is empty: " + templatePath);
            }
            for (Resource resource : resources) {
                String relativePath = resource.getPath().substring(templatePath.length() + 1);
                Path targetPath = tempDir.resolve(relativePath);
                Files.createDirectories(targetPath.getParent());
                try (InputStream in = resource.open()) {
                    Files.copy(in, targetPath, StandardCopyOption.REPLACE_EXISTING);
                }
            }
        }
        return tempDir;
    }

    static void applyTemplate(Path templateDir, Path targetDir, Map<String, String> tokens) throws IOException {
        applyTemplate(templateDir, targetDir, tokens, true);
    }

    /**
     * Materialize a template tree into {@code targetDir}, substituting tokens and
     * resolving the template's inline sample/bare regions (see {@link SampleMarkers}).
     *
     * <p>Regions are resolved before token substitution, so a marker is a purely
     * template-level construct that no token can conjure into or out of existence.
     */
    static void applyTemplate(Path templateDir, Path targetDir, Map<String, String> tokens, boolean includeSamples) throws IOException {
        Files.walk(templateDir).forEach(source -> {
            try {
                Path relative = templateDir.relativize(source);
                String replacedPath = applyTokens(relative.toString(), tokens);
                Path target = targetDir.resolve(replacedPath);

                if (Files.isDirectory(source)) {
                    Files.createDirectories(target);
                }
                else {
                    String content = SampleMarkers.resolve(Files.readString(source), includeSamples, relative.toString());
                    String replacedContent = applyTokens(content, tokens);
                    Files.createDirectories(target.getParent());
                    Files.writeString(target, replacedContent);
                }
            }
            catch (IOException e) {
                throw new RuntimeException(e);
            }
        });
    }

    static String applyTokens(String input, Map<String, String> tokens) {
        String result = input;
        for (Map.Entry<String, String> entry: tokens.entrySet()) {
            result = result.replace(entry.getKey(), entry.getValue());
        }
        return result;
    }
}
