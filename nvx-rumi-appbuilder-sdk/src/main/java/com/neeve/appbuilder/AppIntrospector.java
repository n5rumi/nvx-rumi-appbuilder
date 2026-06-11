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
import java.nio.file.FileVisitResult;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.SimpleFileVisitor;
import java.nio.file.attribute.BasicFileAttributes;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.HashSet;
import java.util.List;
import java.util.Set;

/**
 * Path and metadata resolver for Rumi apps and their services. All methods
 * are read-only — no disk mutation. Used by every introspector and editor
 * added in later phases of the App Builder SDK gap-fill.
 *
 * <p>Method conventions:
 *
 * <ul>
 *   <li>{@code appRoot} is the directory containing the {@code .rumi}
 *       configuration file. Not the parent directory; not the Maven group
 *       directory.
 *   <li>{@code serviceName} is the user-provided service name as originally
 *       passed to {@link ServiceBuilder.ServiceParams}. It is kebab-cased
 *       internally (same as the scaffolder does) to derive the service
 *       artifact id and on-disk module directory name.
 *   <li>Path-resolution methods return the expected path <em>without</em>
 *       verifying it exists on disk. Callers that need existence checks can
 *       use {@link Files#exists(Path, java.nio.file.LinkOption...)}.
 *       Exceptions: {@link #resolveServiceType(Path, String)} inspects the
 *       filesystem because that's the whole point of the call.
 * </ul>
 */
public final class AppIntrospector {
    private AppIntrospector() {}

    private static final String RUMI_CONFIG = ".rumi";

    /**
     * Directory names skipped when walking for {@code .rumi} files. Kept
     * small and focused on directories that would never contain a Rumi app
     * root: version-control metadata, build output, node/Python package
     * caches, IDE state.
     */
    private static final Set<String> WALK_EXCLUDES = new HashSet<>(Arrays.asList(
            ".git", ".hg", ".svn",
            "target", "build", "out",
            "node_modules",
            ".m2", ".gradle", ".venv", "venv",
            ".idea", ".vscode"
    ));

    /**
     * Find every Rumi application under {@code baseDir}, recursively. An
     * app is identified by a {@code .rumi} file at its root. Walks into
     * every directory except those in {@link #WALK_EXCLUDES}. Stops
     * descending into a directory once a {@code .rumi} file is found there
     * — Rumi apps do not nest.
     *
     * @return list of app root directories (each directory contains a
     *         {@code .rumi} file). Empty list if none found. Order is
     *         filesystem-dependent; callers that want a stable order
     *         should sort.
     */
    public static List<Path> listRumiApps(Path baseDir) throws IOException {
        if (!Files.isDirectory(baseDir)) {
            throw new IllegalArgumentException("not a directory: " + baseDir);
        }
        List<Path> apps = new ArrayList<>();
        Files.walkFileTree(baseDir, new SimpleFileVisitor<Path>() {
            @Override
            public FileVisitResult preVisitDirectory(Path dir, BasicFileAttributes attrs) {
                if (!dir.equals(baseDir) && WALK_EXCLUDES.contains(dir.getFileName().toString())) {
                    return FileVisitResult.SKIP_SUBTREE;
                }
                // If this directory itself contains a .rumi file, record it and skip descent.
                if (Files.exists(dir.resolve(RUMI_CONFIG))) {
                    apps.add(dir);
                    return FileVisitResult.SKIP_SUBTREE;
                }
                return FileVisitResult.CONTINUE;
            }
        });
        return apps;
    }

    /**
     * Load the {@code .rumi} metadata for {@code appRoot}. Public wrapper
     * over {@link ApplicationBuilder.AppParams#read(Path)}.
     *
     * @throws IllegalArgumentException if {@code appRoot} is not a valid
     *         Rumi application root (no {@code .rumi} file present).
     */
    public static ApplicationBuilder.AppParams loadAppParams(Path appRoot) throws IOException {
        return ApplicationBuilder.AppParams.read(appRoot);
    }

    /**
     * Return the expected directory for the service module. Computed from
     * the parent artifact id (from {@code .rumi}) plus a kebab-cased
     * service name, matching what {@link ServiceBuilder} produces at scaffold
     * time. Does not verify existence.
     */
    public static Path resolveServiceModuleDir(Path appRoot, String serviceName) throws IOException {
        ApplicationBuilder.AppParams params = loadAppParams(appRoot);
        String parentArtifactId = params.getTokenMap().get(TokenUtils.toToken("ParentArtifactId"));
        String kebab = TokenUtils.toKebabCase(serviceName);
        return appRoot.resolve(parentArtifactId + "-" + kebab);
    }

    /**
     * Return the expected path to the service's top-level {@code Main.java}.
     * Note: connector services also have a {@code connector/Main.java} under
     * the same service module — use {@link #resolveConnectorMainJavaFile}
     * for that if needed. The top-level Main.java is where
     * {@code @EventHandler} methods live.
     */
    public static Path resolveMainJavaFile(Path appRoot, String serviceName) throws IOException {
        ApplicationBuilder.AppParams params = loadAppParams(appRoot);
        Path moduleDir = resolveServiceModuleDir(appRoot, serviceName);
        String appPkgPath = params.getTokenMap().get(TokenUtils.toToken("AppPackagePath"));
        String svcPkgPath = TokenUtils.toSlashCase(TokenUtils.toKebabCase(serviceName));
        return moduleDir.resolve("src/main/java")
                .resolve(appPkgPath)
                .resolve(svcPkgPath)
                .resolve("Main.java");
    }

    /**
     * Return the expected path to a CONNECTOR service's connector
     * {@code Main.java}. Exists only for connector services; calling for
     * other service types yields a path that won't exist on disk.
     */
    public static Path resolveConnectorMainJavaFile(Path appRoot, String serviceName) throws IOException {
        Path mainJava = resolveMainJavaFile(appRoot, serviceName);
        return mainJava.getParent().resolve("connector").resolve("Main.java");
    }

    /**
     * Return the expected path to a WEBSERVICE service's {@code HttpServer.java}.
     * Exists only for webservice services; its presence is the signal used by
     * {@link #resolveServiceType} to distinguish a webservice (which also has a
     * state.xml) from a plain processor.
     */
    public static Path resolveHttpServerJavaFile(Path appRoot, String serviceName) throws IOException {
        Path mainJava = resolveMainJavaFile(appRoot, serviceName);
        return mainJava.getParent().resolve("HttpServer.java");
    }

    /**
     * Return the expected path to the service's {@code messages.xml}.
     */
    public static Path resolveMessagesXmlFile(Path appRoot, String serviceName) throws IOException {
        return resolveServiceModelsDir(appRoot, serviceName).resolve("messages").resolve("messages.xml");
    }

    /**
     * Return the expected path to the service's {@code state.xml}. The
     * file exists for PROCESSOR and WEBSERVICE services; {@link Files#exists}
     * will return {@code false} for DRIVER and CONNECTOR services.
     */
    public static Path resolveStateXmlFile(Path appRoot, String serviceName) throws IOException {
        return resolveServiceModelsDir(appRoot, serviceName).resolve("state").resolve("state.xml");
    }

    /**
     * Return the expected path to the service's {@code api.xml}.
     */
    public static Path resolveApiXmlFile(Path appRoot, String serviceName) throws IOException {
        return resolveServiceModelsDir(appRoot, serviceName).resolve("api.xml");
    }

    /**
     * Return the expected path to the app's shared ROE {@code messages.xml}
     * (the "realm of entities" model under the roe module). App-level, not
     * service-scoped — the same file is imported by every service.
     */
    public static Path resolveRoeMessagesXmlFile(Path appRoot) throws IOException {
        ApplicationBuilder.AppParams params = loadAppParams(appRoot);
        String roeArtifactId = params.getTokenMap().get(TokenUtils.toToken("RoeArtifactId"));
        String appPkgPath = params.getTokenMap().get(TokenUtils.toToken("AppPackagePath"));
        return appRoot.resolve(roeArtifactId)
                .resolve("src/main/models")
                .resolve(appPkgPath)
                .resolve("roe")
                .resolve("messages.xml");
    }

    /**
     * Infer a service's type from its on-disk structure.
     *
     * <p>Signals used, in order (webservice is checked before processor
     * because a webservice is also stateful and would otherwise look like a
     * processor):
     *
     * <ul>
     *   <li>If {@code HttpServer.java} exists alongside the service's
     *       top-level {@code Main.java}, it's a
     *       {@link ServiceBuilder.ServiceType#WEBSERVICE WEBSERVICE}.
     *   <li>Else if {@code state.xml} exists under the service's models dir,
     *       it's a {@link ServiceBuilder.ServiceType#PROCESSOR PROCESSOR}.
     *   <li>Else if a {@code connector} subpackage exists alongside the
     *       service's top-level {@code Main.java}, it's a
     *       {@link ServiceBuilder.ServiceType#CONNECTOR CONNECTOR}.
     *   <li>Otherwise it's a {@link ServiceBuilder.ServiceType#DRIVER DRIVER}.
     * </ul>
     *
     * These signals match the templates that
     * {@link ServiceBuilder#createService} uses: webservices get an embedded
     * {@code HttpServer}, processors get a state model, connectors get a
     * connector subpackage, drivers get none of these.
     *
     * @throws IllegalArgumentException if the service module directory does
     *         not exist.
     */
    public static ServiceBuilder.ServiceType resolveServiceType(Path appRoot, String serviceName) throws IOException {
        Path moduleDir = resolveServiceModuleDir(appRoot, serviceName);
        if (!Files.isDirectory(moduleDir)) {
            throw new IllegalArgumentException(
                "service '" + serviceName + "' not found under " + appRoot + " (expected module dir: " + moduleDir + ")");
        }
        if (Files.exists(resolveHttpServerJavaFile(appRoot, serviceName))) {
            return ServiceBuilder.ServiceType.WEBSERVICE;
        }
        if (Files.exists(resolveStateXmlFile(appRoot, serviceName))) {
            return ServiceBuilder.ServiceType.PROCESSOR;
        }
        Path mainJava = resolveMainJavaFile(appRoot, serviceName);
        if (Files.isDirectory(mainJava.getParent().resolve("connector"))) {
            return ServiceBuilder.ServiceType.CONNECTOR;
        }
        return ServiceBuilder.ServiceType.DRIVER;
    }

    // --- internal -----------------------------------------------------

    private static Path resolveServiceModelsDir(Path appRoot, String serviceName) throws IOException {
        ApplicationBuilder.AppParams params = loadAppParams(appRoot);
        Path moduleDir = resolveServiceModuleDir(appRoot, serviceName);
        String appPkgPath = params.getTokenMap().get(TokenUtils.toToken("AppPackagePath"));
        String svcPkgPath = TokenUtils.toSlashCase(TokenUtils.toKebabCase(serviceName));
        return moduleDir.resolve("src/main/models")
                .resolve(appPkgPath)
                .resolve(svcPkgPath);
    }
}
