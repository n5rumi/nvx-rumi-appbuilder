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

import com.neeve.appbuilder.ApplicationBuilder;
import com.neeve.appbuilder.ApplicationBuilder.BuildTool;
import com.neeve.appbuilder.ApplicationBuilder.EncodingType;
import com.neeve.appbuilder.ApplicationBuilder.MessagingProvider;
import com.neeve.appbuilder.ServiceBuilder;
import com.neeve.appbuilder.ServiceBuilder.ServiceHAModel;
import com.neeve.appbuilder.ServiceBuilder.ServiceType;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Comparator;
import java.util.stream.Stream;

/**
 * Convenience fixture for producing scaffolded Rumi apps in tests.
 * Instead of constructing an {@link ApplicationBuilder.AppParams} with
 * eleven positional arguments and then dealing with the
 * {@link ApplicationBuilder} / {@link ServiceBuilder} wiring manually,
 * tests (SDK integration tests or downstream consumer tests) can do:
 *
 * <pre>{@code
 * Path appRoot = TestAppFactory.newApp("trading").scaffoldAt(tempDir);
 * TestAppFactory.addProcessor(appRoot, "order-processor");
 * TestAppFactory.addDriver(appRoot, "feeder");
 * }</pre>
 *
 * <p>Lives in the SDK JAR under {@code com.neeve.appbuilder.test} so
 * downstream consumers can depend on the SDK at compile scope and use
 * the fixtures in their own tests. Has no test-framework dependency —
 * assertions are in {@link AppBuilderAssertions} and throw plain
 * {@link AssertionError}.
 *
 * <p>Paired with {@link #deleteRecursive(Path)} for tear-down.
 */
public final class TestAppFactory {
    private TestAppFactory() {}

    /**
     * Start building a test app. Reasonable defaults are pre-populated;
     * override with the {@link Builder} setters.
     */
    public static Builder newApp(String appName) {
        return new Builder(appName);
    }

    /**
     * Add a processor service with sensible defaults: STATE_REPLICATION HA,
     * unclustered, 1 partition. For a clustered or multi-partition service,
     * use the fuller {@link #addProcessor(Path, String, ServiceHAModel, boolean, int)}.
     */
    public static void addProcessor(Path appRoot, String serviceName) throws Exception {
        addProcessor(appRoot, serviceName, ServiceHAModel.STATE_REPLICATION, false, 1);
    }

    public static void addProcessor(Path appRoot,
                                    String serviceName,
                                    ServiceHAModel haModel,
                                    boolean clustered,
                                    int numPartitions) throws Exception {
        ServiceBuilder.ServiceParams params = new ServiceBuilder.ServiceParams(
            appRoot.toString(), serviceName, ServiceType.PROCESSOR, haModel, clustered, numPartitions);
        new ServiceBuilder().createService(params);
    }

    /** Add a driver service (non-clusterable, 1 partition). */
    public static void addDriver(Path appRoot, String serviceName) throws Exception {
        ServiceBuilder.ServiceParams params = new ServiceBuilder.ServiceParams(
            appRoot.toString(), serviceName, ServiceType.DRIVER, null, false, 1);
        new ServiceBuilder().createService(params);
    }

    /** Add a connector service (non-clusterable, 1 partition). */
    public static void addConnector(Path appRoot, String serviceName) throws Exception {
        ServiceBuilder.ServiceParams params = new ServiceBuilder.ServiceParams(
            appRoot.toString(), serviceName, ServiceType.CONNECTOR, null, false, 1);
        new ServiceBuilder().createService(params);
    }

    /** Add a webservice service (clusterable, state-replicated, 1 partition, non-clustered). */
    public static void addWebservice(Path appRoot, String serviceName) throws Exception {
        ServiceBuilder.ServiceParams params = new ServiceBuilder.ServiceParams(
            appRoot.toString(), serviceName, ServiceType.WEBSERVICE,
            ServiceBuilder.ServiceHAModel.STATE_REPLICATION, false, 1);
        new ServiceBuilder().createService(params);
    }

    /**
     * Recursively delete {@code root}. Safe to call on a non-existent
     * path (no-op). Useful for test tear-down.
     */
    public static void deleteRecursive(Path root) throws IOException {
        if (root == null || !Files.exists(root)) return;
        try (Stream<Path> walk = Files.walk(root)) {
            walk.sorted(Comparator.reverseOrder()).forEach(p -> {
                try { Files.deleteIfExists(p); } catch (IOException ignored) {}
            });
        }
    }

    /**
     * Builder for a {@link ApplicationBuilder.AppParams}-scaffolded test
     * app. All setters return {@code this} for chaining; {@link #scaffoldAt}
     * is the terminal operation.
     */
    public static final class Builder {
        private final String appName;
        private String packageName = "com.example.app";
        private String groupId = "com.example";
        private String artifactPrefix = "test";
        private String rumiVersion = "4.0.0";
        private String rumiBindingsVersion = "4.0.0";
        private String rumiMgmtVersion = "2.0.0";
        private EncodingType encodingType = EncodingType.QUARK;
        private MessagingProvider messagingProvider = MessagingProvider.ACTIVEMQ;
        private BuildTool buildTool = BuildTool.MAVEN;
        private boolean includeSamples = true;

        private Builder(String appName) {
            if (appName == null || appName.isBlank()) {
                throw new IllegalArgumentException("appName cannot be blank");
            }
            this.appName = appName;
            // Derive a conventional default package from the app name.
            this.packageName = "com.example." + appName.toLowerCase().replaceAll("\\s+", "");
        }

        public Builder packageName(String v) { this.packageName = v; return this; }
        public Builder groupId(String v) { this.groupId = v; return this; }
        public Builder artifactPrefix(String v) { this.artifactPrefix = v; return this; }
        public Builder rumiVersion(String v) { this.rumiVersion = v; return this; }
        public Builder rumiBindingsVersion(String v) { this.rumiBindingsVersion = v; return this; }
        public Builder rumiMgmtVersion(String v) { this.rumiMgmtVersion = v; return this; }
        public Builder encodingType(EncodingType v) { this.encodingType = v; return this; }
        public Builder messagingProvider(MessagingProvider v) { this.messagingProvider = v; return this; }
        public Builder buildTool(BuildTool v) { this.buildTool = v; return this; }

        /**
         * Scaffold with or without worked example code. The mode is recorded in
         * the app's {@code .rumi}, so the {@code addProcessor}/{@code addDriver}
         * /... helpers inherit it -- there is no per-service setting to remember.
         */
        public Builder includeSamples(boolean v) { this.includeSamples = v; return this; }

        /**
         * Scaffold the app under {@code parentDir}. Returns the app root
         * (which is {@code parentDir/<artifactPrefix>-<appTokenName>}).
         */
        public Path scaffoldAt(Path parentDir) throws IOException {
            Files.createDirectories(parentDir);
            ApplicationBuilder.AppParams params = new ApplicationBuilder.AppParams(
                appName, parentDir.toString(), packageName, groupId, artifactPrefix,
                rumiVersion, rumiBindingsVersion, rumiMgmtVersion,
                encodingType, messagingProvider, buildTool, includeSamples);
            new ApplicationBuilder().createApplication(params);
            return Path.of(params.getAppRoot());
        }
    }
}
