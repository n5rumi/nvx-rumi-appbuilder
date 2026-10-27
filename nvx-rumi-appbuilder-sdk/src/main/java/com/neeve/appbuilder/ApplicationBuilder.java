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

import java.io.BufferedReader;
import java.io.BufferedWriter;
import java.io.IOException;
import java.net.URISyntaxException;
import java.nio.file.*;
import java.util.*;
import java.util.stream.Collectors;

import com.google.gson.Gson;

public class ApplicationBuilder {
    public enum BuildTool {
        MAVEN("maven"),
        GRADLE("gradle");

        private final String name;

        BuildTool(String name) {
            this.name = name;
        }

        public String getName() {
            return name;
        }

        public static BuildTool fromString(String value) {
            for (BuildTool tool: BuildTool.values()) {
                if (tool.name.equalsIgnoreCase(value)) {
                    return tool;
                }
            }
            throw new IllegalArgumentException("Unsupported build tool: " + value);
        }
    }

    public enum EncodingType {
        QUARK("quark"),
        PROTOBUF("protobuf");

        private final String name;

        EncodingType(String name) {
            this.name = name;
        }

        public String getName() {
            return name;
        }

        public static EncodingType fromString(String value) {
            for (EncodingType tool: EncodingType.values()) {
                if (tool.name.equalsIgnoreCase(value)) {
                    return tool;
                }
            }
            throw new IllegalArgumentException("Unsupported encoding type: " + value);
        }
    }

    public enum MessagingProvider {
        SOLACE("solace"),
        ACTIVEMQ("activemq"),
        KAFKA("kafka");

        private final String name;

        MessagingProvider(String name) {
            this.name = name;
        }

        public String getName() {
            return name;
        }

        public static MessagingProvider fromString(String value) {
            for (MessagingProvider provider : MessagingProvider.values()) {
                if (provider.name.equalsIgnoreCase(value)) {
                    return provider;
                }
            }
            throw new IllegalArgumentException("Unsupported messaging provider: " + value);
        }
    }

    public static class AppParams {
        private final String appName;
        private final String appDir;
        private final String packageName;
        private final String groupId;
        private final String artifactPrefix;
        /**
         * An explicit artifact id, or null to derive one from the app name.
         *
         * <p>Nullable for the same reason {@code includeSamples} is: this class
         * is deserialized from the app's {@code .rumi} descriptor by Gson, and a
         * descriptor written before this option existed carries no such field.
         */
        private final String artifactId;
        private final String rumiVersion;
        private final String rumiBindingsVersion;
        private final String rumiMgmtVersion;
        private final EncodingType encodingType;
        private final MessagingProvider messagingProvider;
        private final BuildTool buildTool;
        /**
         * Whether scaffolds for this app carry worked example code.
         *
         * <p>Deliberately a nullable {@link Boolean} rather than a primitive.
         * This class is deserialized from the app's {@code .rumi} descriptor by
         * Gson, and a descriptor written before this option existed has no such
         * key -- which Gson would leave as {@code false} on a primitive, quietly
         * flipping every pre-existing app to sample-free. Null means "not
         * recorded", which {@link #isIncludeSamples()} reads as the historical
         * behaviour: samples on.
         */
        private final Boolean includeSamples;
        private final String appTokenName;
        private final Map<String, String> tokenMap;

        private static final String CONFIG_FILE_NAME = ".rumi";
        private static final Gson gson = new Gson();

        public AppParams(String appName,
                         String appDir,
                         String packageName,
                         String groupId,
                         String artifactPrefix,
                         String rumiVersion,
                         String rumiBindingsVersion,
                         String rumiMgmtVersion,
                         EncodingType encodingType,
                         MessagingProvider messagingProvider,
                         BuildTool buildTool) {
            this(appName, appDir, packageName, groupId, artifactPrefix, rumiVersion,
                 rumiBindingsVersion, rumiMgmtVersion, encodingType, messagingProvider,
                 buildTool, true);
        }

        public AppParams(String appName,
                         String appDir,
                         String packageName,
                         String groupId,
                         String artifactPrefix,
                         String rumiVersion,
                         String rumiBindingsVersion,
                         String rumiMgmtVersion,
                         EncodingType encodingType,
                         MessagingProvider messagingProvider,
                         BuildTool buildTool,
                         boolean includeSamples) {
            this(appName, appDir, packageName, groupId, artifactPrefix, rumiVersion,
                 rumiBindingsVersion, rumiMgmtVersion, encodingType, messagingProvider,
                 buildTool, includeSamples, null);
        }

        public AppParams(String appName,
                         String appDir,
                         String packageName,
                         String groupId,
                         String artifactPrefix,
                         String rumiVersion,
                         String rumiBindingsVersion,
                         String rumiMgmtVersion,
                         EncodingType encodingType,
                         MessagingProvider messagingProvider,
                         BuildTool buildTool,
                         boolean includeSamples,
                         String artifactId) {
            if (appName == null) {
                throw new IllegalArgumentException("app name cannot be null");
            }
            if (appDir == null) {
                throw new IllegalArgumentException("app dir cannot be null");
            }
            // Validated BEFORE the directory below, which has a side effect:
            // a rejected call must not leave a directory behind.
            // Reject an unusable id HERE rather than writing it into the POM.
            // Maven's own artifactId grammar is [A-Za-z0-9_.-]+; a value with a
            // space or a slash produces a POM Maven refuses to read, and the
            // caller would meet that as an opaque build failure several calls
            // later with nothing pointing back at this argument. Accept-and-break
            // is the trap this repo keeps paying for (RUMI-424), so: refuse, and
            // say what is wrong.
            if (artifactId != null && !artifactId.trim().isEmpty()
                && !artifactId.trim().matches("[A-Za-z0-9_.-]+")) {
                throw new IllegalArgumentException(
                    "artifact id '" + artifactId.trim() + "' is not a valid Maven artifactId;"
                    + " use letters, digits, '-', '_' and '.' only (no spaces or slashes)");
            }

            // CREATE it rather than refuse. A scaffolder that is about to make
            // dozens of directories declining to make the first is a surprising
            // asymmetry, and it cost a round trip on every single app: the caller
            // gets a 400, runs one `mkdir -p`, and calls again (RUMI-431 / I-01).
            Path appDirPath = Paths.get(appDir).toAbsolutePath();
            if (Files.notExists(appDirPath)) {
                try {
                    Files.createDirectories(appDirPath);
                }
                catch (IOException e) {
                    throw new IllegalArgumentException(
                        "app dir '" + appDir + "' does not exist and could not be created: "
                        + e.getMessage(), e);
                }
            }
            else if (!Files.isDirectory(appDirPath)) {
                throw new IllegalArgumentException("app dir '" + appDir + "' is not a directory");
            }
            if (packageName == null) {
                throw new IllegalArgumentException("package name cannot be null");
            }
            if (groupId == null) {
                throw new IllegalArgumentException("group id cannot be null");
            }
            if (artifactPrefix == null) {
                throw new IllegalArgumentException("artifact prefix  cannot be null");
            }
            if (rumiVersion == null) {
                throw new IllegalArgumentException("Rumi runtime version cannot be null");
            }
            if (rumiBindingsVersion == null) {
                throw new IllegalArgumentException("Rumi bindings version cannot be null");
            }
            if (rumiMgmtVersion == null) {
                throw new IllegalArgumentException("Rumi management version cannot be null");
            }
            this.appName = appName;
            this.appDir = appDir;
            this.packageName = packageName;
            this.groupId = groupId;
            this.artifactPrefix = artifactPrefix;
            this.rumiVersion = rumiVersion;
            this.rumiBindingsVersion = rumiBindingsVersion;
            this.rumiMgmtVersion = rumiMgmtVersion;
            this.encodingType = encodingType != null ? encodingType : EncodingType.QUARK;
            this.messagingProvider = messagingProvider != null ? messagingProvider : MessagingProvider.ACTIVEMQ;
            this.buildTool = buildTool != null ? buildTool : BuildTool.MAVEN;
            this.includeSamples = includeSamples;
            this.artifactId = artifactId;
            this.appTokenName = appName.toLowerCase().replaceAll("\\s+", "");
            this.tokenMap = toTokenMap();
        }

        private String getConnectionStringForProvider(MessagingProvider provider) {
            switch (provider) {
                case SOLACE:
                    return "solace://solace.rumi.local:55555";
                case ACTIVEMQ:
                    return "activemq://activemq.rumi.local:61616?wireFormat.maxInactivityDuration=0";
                case KAFKA:
                    return "kafka://kafka.rumi.local:9092";
                default:
                    throw new IllegalStateException("Unhandled messaging provider: " + provider);
            }
        }

        private String getMessagingDependencySnippet(MessagingProvider provider) {
            String indent = "        "; // 8 spaces to match existing <dependency> blocks
            switch (provider) {
                case SOLACE:
                    return indent + "\n" +
                           indent + "<dependency>\n" +
                           indent + "    <groupId>com.neeve</groupId>\n" +
                           indent + "    <artifactId>nvx-rumi-solace</artifactId>\n" +
                           indent + "</dependency>\n";
                case KAFKA:
                    return indent + "\n" +
                           indent + "<dependency>\n" +
                           indent + "    <groupId>com.neeve</groupId>\n" +
                           indent + "    <artifactId>nvx-rumi-kafka</artifactId>\n" +
                           indent + "</dependency>\n";
                case ACTIVEMQ:
                    return ""; // nothing needed
                default:
                    throw new IllegalStateException("Unhandled messaging provider: " + provider);
            }
        }

        private Map<String, String> toTokenMap() {
            Map<String, String> map = new HashMap<>();
            map.put(TokenUtils.toToken("AppDisplayName"), TokenUtils.forDisplay(appName));
            map.put(TokenUtils.toToken("AppDir"), appDir);
            map.put(TokenUtils.toToken("AppTokenName"), appTokenName);
            map.put(TokenUtils.toToken("AppPackageName"), packageName);
            map.put(TokenUtils.toToken("AppPackagePath"), packageName.replace('.', '/'));
            map.put(TokenUtils.toToken("GroupId"), groupId);
            map.put(TokenUtils.toToken("ArtifactPrefix"), artifactPrefix);
            // The artifact id is DERIVED from the app name unless the caller names
            // it. Derivation strips punctuation and spaces, so a natural app name
            // stutters: "Paywhere Prospect Portal" with prefix "paywhere" became
            // `paywhere-paywhereprospectportal`, recoverable only by deleting the
            // app and starting again. The derived value is returned either way, so
            // a caller can see what it got before building on it (RUMI-431 / I-02).
            String baseArtifactId = artifactId != null && !artifactId.trim().isEmpty()
                ? artifactId.trim()
                : artifactPrefix + "-" + appTokenName;
            map.put(TokenUtils.toToken("ParentArtifactId"), baseArtifactId);
            map.put(TokenUtils.toToken("RoeArtifactId"), baseArtifactId + "-roe");
            map.put(TokenUtils.toToken("SystemArtifactId"), baseArtifactId + "-system");
            map.put(TokenUtils.toToken("BusName"), appTokenName);
            map.put(TokenUtils.toToken("RumiVersion"), rumiVersion);
            map.put(TokenUtils.toToken("RumiBindingsVersion"), rumiBindingsVersion);
            map.put(TokenUtils.toToken("RumiMgmtVersion"), rumiMgmtVersion);
            map.put(TokenUtils.toToken("EncodingType"), encodingType.getName());
            map.put(TokenUtils.toToken("MessagingProvider"), messagingProvider.getName());
            map.put(TokenUtils.toToken("MessagingConnectionString"), getConnectionStringForProvider(messagingProvider));
            map.put(TokenUtils.toToken("MessagingProviderDependency"), getMessagingDependencySnippet(messagingProvider));
            map.put(TokenUtils.toToken("BuildTool"), buildTool.getName());
            return map;
        }

        private static void write(Path appRoot, AppParams params) throws IOException {
            Path configFile = appRoot.resolve(CONFIG_FILE_NAME);
            try (BufferedWriter writer = Files.newBufferedWriter(configFile)) {
                gson.toJson(params, writer);
            }
        }

        public static AppParams read(Path appRoot) throws IOException {
            Path configFile = appRoot.resolve(CONFIG_FILE_NAME);
            if (!Files.exists(configFile)) {
                throw new IllegalArgumentException(appRoot.toAbsolutePath().normalize() + " is not a valid Rumi application root");
            }
            try (BufferedReader reader = Files.newBufferedReader(configFile)) {
                return gson.fromJson(reader, ApplicationBuilder.AppParams.class);
            }
        }

        public String getAppName() {
            return appName;
        }

        public String getAppDir() {
            return appDir;
        }

        public String getAppRoot() {
            return Paths.get(getAppDir()).resolve(getTokenMap().get(TokenUtils.toToken("ParentArtifactId"))).toAbsolutePath().normalize().toString();
        }

        public String getPackageName() {
            return packageName;
        }

        public String getGroupId() {
            return groupId;
        }

        public String getArtifactPrefix() {
            return artifactPrefix;
        }

        public String getRumiVersion() {
            return rumiVersion;
        }

        public String getRumiBindingsVersion() {
            return rumiBindingsVersion;
        }

        public String getRumiMgmtVersion() {
            return rumiMgmtVersion;
        }

        public EncodingType getEncodingType() {
            return encodingType;
        }

        public MessagingProvider getMessagingProvider() {
            return messagingProvider;
        }

        public String getAppTokenName() {
            return appTokenName;
        }

        public BuildTool getBuildTool() {
            return buildTool;
        }

        /**
         * Whether scaffolds for this app carry worked example code. An app whose
         * {@code .rumi} predates this option reads as true, its original behaviour.
         */
        public boolean isIncludeSamples() {
            return includeSamples == null || includeSamples;
        }

        /** The explicit artifact id, or null if it was derived from the app name. */
        public String getArtifactId() {
            return artifactId;
        }

        public Map<String, String> getTokenMap() {
            return tokenMap;
        }
    }

    private void validateRumiAppDoesNotExist(Path appRoot) {
        if (Files.exists(appRoot)) {
            Path appConfig = appRoot.resolve(".rumi");
            if (Files.exists(appConfig)) {
                throw new IllegalArgumentException("A Rumi application already exists at: " + appRoot.toAbsolutePath().normalize());
            } 
            else {
                throw new IllegalArgumentException("Directory '" + appRoot.toAbsolutePath().normalize() + "' already exists but is not a Rumi application");
            }
        }
    }

    final public ApplicationBuilder createApplication(AppParams params) throws IOException {
        if (params == null) {
            throw new IllegalArgumentException("params cannot be null");
        }
        Path appDir = Paths.get(params.getAppDir()).toAbsolutePath();
        Path appRoot = Paths.get(params.getAppRoot()).toAbsolutePath();
        validateRumiAppDoesNotExist(appRoot);
        BuildTool buildTool = params.getBuildTool();
        Path templateDir;
        try {
            String templatePath = String.format("templates/%s/app", buildTool.getName());
            templateDir = TemplateProcessor.extractTemplateDirectory("rumi-app-template", templatePath, false);
        }
        catch (IOException e) {
            throw new IOException("Failed to extract template for build tool: " + buildTool, e);
        }
        TemplateProcessor.applyTemplate(templateDir, appDir, params.getTokenMap(), params.isIncludeSamples());
        AppParams.write(appRoot, params);
        // Seed the app-global factory-id ledger with the ids the scaffold created
        // (e.g. the shared ROE factory) so they are never reused.
        FactoryIdCollector.recordAllocatedIds(appRoot);
        return this;
    }

    final public ServiceBuilder newServiceBuilder() {
        return new ServiceBuilder();
    }
}
