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

import java.io.IOException;
import java.nio.file.*;
import java.util.List;
import java.util.Map;

public class ScriptInjector {
    static void injectIntoScripts(Path appRoot, ServiceBuilder.ServiceParams params) throws Exception {
        // get the token map
        Map<String, String> tokens = params.getTokenMap();

        // get the directory containing the scripts to inject into
        Path scriptPath = appRoot
                .resolve(tokens.get(TokenUtils.toToken("SystemArtifactId")))
                .resolve("scripts");

        // extract script templates
        String templateRoot = String.format("templates/%s/scripts/%s",
                tokens.get(TokenUtils.toToken("BuildTool")),
                params.getServiceType().getName());
        Path scriptTemplatesDir = TemplateProcessor.extractTemplateDirectory("rumi-service-script-template", templateRoot, true);

        // inject if template path was found
        if (scriptTemplatesDir != null) {
            try {
                int numInstances = params.getNumPartitions() * (params.isClustered() ? 2 : 1);
                for (int i = 0 ; i < numInstances ; i++) {
                    params.getTokenMap().put(TokenUtils.toToken("ServicePartition"), String.valueOf((params.isClustered() ? i/2 : i) + 1));
                    params.getTokenMap().put(TokenUtils.toToken("ServiceInstance"), String.valueOf(i+1));
                    Files.walk(scriptTemplatesDir).forEach(snippetPath -> {
                        try {
                            // only process regular files
                            if (Files.isRegularFile(snippetPath)) {
                                // resolve the file relative the to the script directory
                                Path relative = scriptTemplatesDir.relativize(snippetPath);
                                Path targetScriptPath = scriptPath.resolve(relative);
                                if (!Files.exists(targetScriptPath)) return;

                                // inject (only if not already injected into)
                                List<String> scriptLines = Files.readAllLines(targetScriptPath);
                                String snippetTemplate = Files.readString(snippetPath);
                                String snippet = TemplateProcessor.applyTokens(snippetTemplate, tokens);
                                List<String> snippetLines = List.of(snippet.split("\\r?\\n"));
                                if (!scriptLines.containsAll(snippetLines)) {
                                    scriptLines.addAll(snippetLines);
                                    Files.write(targetScriptPath, scriptLines);
                                }
                            }
                        } catch (IOException e) {
                            throw new RuntimeException(e);
                        }
                    });
                }
            }
            finally { 
                params.getTokenMap().remove(TokenUtils.toToken("ServicePartition"));
                params.getTokenMap().remove(TokenUtils.toToken("ServiceInstance"));
            }
        }
    }
}

