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

import com.google.gson.Gson;

import java.io.BufferedWriter;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;

/**
 * Shared fixture helpers for Phase B introspector tests. Builds an on-disk
 * skeleton matching what ServiceBuilder would produce, without running the
 * actual scaffolder (faster and keeps tests focused on the introspector
 * logic).
 */
final class PhaseBTestSupport {

    private PhaseBTestSupport() {}

    /**
     * Create an app root with a .rumi file and an empty pom.xml under
     * {@code tempDir}. Returns the app root. Uses the same AppParams
     * fixture fields as AppIntrospectorTest so the resolved module names
     * are predictable ("test-{appTokenName}-{kebab-service}").
     */
    static Path scaffoldApp(Path tempDir, String appName, String packageName) throws IOException {
        ApplicationBuilder.AppParams params = new ApplicationBuilder.AppParams(
                appName,
                tempDir.toString(),
                packageName,
                "com.example",
                "test",
                "4.0.0", "4.0.0", "2.0.0",
                ApplicationBuilder.EncodingType.QUARK,
                ApplicationBuilder.MessagingProvider.ACTIVEMQ,
                ApplicationBuilder.BuildTool.MAVEN);
        String parentArtifactId = params.getTokenMap().get(TokenUtils.toToken("ParentArtifactId"));
        Path appRoot = tempDir.resolve(parentArtifactId);
        Files.createDirectories(appRoot);
        try (BufferedWriter w = Files.newBufferedWriter(appRoot.resolve(".rumi"))) {
            new Gson().toJson(params, w);
        }
        return appRoot;
    }

    /** Write a fake parent pom with the given {@code <module>} list. */
    static void writeParentPom(Path appRoot, String... modules) throws IOException {
        StringBuilder sb = new StringBuilder();
        sb.append("<project>\n  <modules>\n");
        for (String m : modules) {
            sb.append("    <module>").append(m).append("</module>\n");
        }
        sb.append("  </modules>\n</project>\n");
        Files.writeString(appRoot.resolve("pom.xml"), sb.toString());
    }

    /** Write a messages.xml at the canonical path for {@code serviceName}. */
    static void writeMessagesXml(Path appRoot, String serviceName, String body) throws IOException {
        Path messages = AppIntrospector.resolveMessagesXmlFile(appRoot, serviceName);
        Files.createDirectories(messages.getParent());
        Files.writeString(messages, body);
    }

    /** Write a state.xml at the canonical path for {@code serviceName}. */
    static void writeStateXml(Path appRoot, String serviceName, String body) throws IOException {
        Path state = AppIntrospector.resolveStateXmlFile(appRoot, serviceName);
        Files.createDirectories(state.getParent());
        Files.writeString(state, body);
    }

    /** Write a config.xml at the canonical system-module path. */
    static void writeConfigXml(Path appRoot, String body) throws IOException {
        ApplicationBuilder.AppParams params = AppIntrospector.loadAppParams(appRoot);
        String systemArtifactId = params.getTokenMap().get(TokenUtils.toToken("SystemArtifactId"));
        Path cfg = appRoot.resolve(systemArtifactId).resolve("conf/config.xml");
        Files.createDirectories(cfg.getParent());
        Files.writeString(cfg, body);
    }
}
