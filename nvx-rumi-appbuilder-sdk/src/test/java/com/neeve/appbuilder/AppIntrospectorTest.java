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
import org.junit.After;
import org.junit.Before;
import org.junit.Test;

import java.io.BufferedWriter;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.stream.Stream;

import static org.junit.Assert.*;

public class AppIntrospectorTest {

    private Path tempDir;

    @Before
    public void setUp() throws IOException {
        tempDir = Files.createTempDirectory("appintrospector-test-");
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

    // ---- listRumiApps ---------------------------------------------------

    @Test
    public void listRumiApps_findsAppsUnderBaseDir() throws Exception {
        Path a = scaffoldAppRoot("appOne", "com.example.one");
        Path b = scaffoldAppRoot("appTwo", "com.example.two");

        List<Path> apps = AppIntrospector.listRumiApps(tempDir);
        assertEquals(2, apps.size());
        assertTrue(apps.contains(a));
        assertTrue(apps.contains(b));
    }

    @Test
    public void listRumiApps_emptyWhenNoRumiFiles() throws Exception {
        Files.createDirectory(tempDir.resolve("not-a-rumi-app"));
        List<Path> apps = AppIntrospector.listRumiApps(tempDir);
        assertTrue(apps.isEmpty());
    }

    @Test
    public void listRumiApps_findsAppRootWhenBaseDirIsTheAppRoot() throws Exception {
        Path a = scaffoldAppRoot("soloApp", "com.example.solo");
        List<Path> apps = AppIntrospector.listRumiApps(a);
        assertEquals(1, apps.size());
        assertEquals(a, apps.get(0));
    }

    @Test
    public void listRumiApps_skipsExcludedDirectories() throws Exception {
        // A valid app inside .git/ or target/ should not be picked up.
        Path excluded = tempDir.resolve("target").resolve("hidden-app");
        Files.createDirectories(excluded);
        writeRumiJson(excluded, fixtureAppParams("hiddenApp", "com.example.hidden"));

        Path visible = scaffoldAppRoot("visibleApp", "com.example.visible");

        List<Path> apps = AppIntrospector.listRumiApps(tempDir);
        assertEquals(1, apps.size());
        assertEquals(visible, apps.get(0));
    }

    @Test
    public void listRumiApps_doesNotNestIntoAppRoot() throws Exception {
        // A .rumi file inside an app root should not be treated as a second app even if it's present in a subdir.
        Path outer = scaffoldAppRoot("outerApp", "com.example.outer");
        Path nested = outer.resolve("some-service").resolve("embedded-app");
        Files.createDirectories(nested);
        writeRumiJson(nested, fixtureAppParams("nestedApp", "com.example.nested"));

        List<Path> apps = AppIntrospector.listRumiApps(tempDir);
        assertEquals("nested .rumi ignored once a parent app root was found", 1, apps.size());
        assertEquals(outer, apps.get(0));
    }

    @Test
    public void listRumiApps_rejectsNonDirectory() throws Exception {
        Path file = Files.createFile(tempDir.resolve("a-file"));
        try {
            AppIntrospector.listRumiApps(file);
            fail("expected IllegalArgumentException");
        } catch (IllegalArgumentException expected) {
            // ok
        }
    }

    // ---- loadAppParams --------------------------------------------------

    @Test
    public void loadAppParams_readsRumiJson() throws Exception {
        Path appRoot = scaffoldAppRoot("loadTest", "com.example.load");
        ApplicationBuilder.AppParams loaded = AppIntrospector.loadAppParams(appRoot);
        assertEquals("loadTest", loaded.getAppName());
        assertEquals("com.example.load", loaded.getPackageName());
        assertEquals("com.example", loaded.getGroupId());
    }

    @Test
    public void loadAppParams_throwsWhenNotAnApp() throws IOException {
        Path nonApp = Files.createDirectory(tempDir.resolve("not-an-app"));
        try {
            AppIntrospector.loadAppParams(nonApp);
            fail("expected IllegalArgumentException");
        } catch (IllegalArgumentException expected) {
            // ok
        }
    }

    // ---- resolveServiceModuleDir ---------------------------------------

    @Test
    public void resolveServiceModuleDir_returnsKebabCasedArtifactDir() throws Exception {
        Path appRoot = scaffoldAppRoot("trading", "com.example.trading");
        // parent artifact id = "test-trading" (prefix "test" + appTokenName "trading")
        Path moduleDir = AppIntrospector.resolveServiceModuleDir(appRoot, "orderProcessor");
        assertEquals(appRoot.resolve("test-trading-order-processor"), moduleDir);
    }

    @Test
    public void resolveServiceModuleDir_handlesAlreadyKebabCasedNames() throws Exception {
        Path appRoot = scaffoldAppRoot("trading", "com.example.trading");
        Path moduleDir = AppIntrospector.resolveServiceModuleDir(appRoot, "order-processor");
        assertEquals(appRoot.resolve("test-trading-order-processor"), moduleDir);
    }

    // ---- resolveMainJavaFile --------------------------------------------

    @Test
    public void resolveMainJavaFile_returnsPathUnderAppPackageAndServicePackage() throws Exception {
        Path appRoot = scaffoldAppRoot("trading", "com.example.trading");
        Path mainJava = AppIntrospector.resolveMainJavaFile(appRoot, "orderProcessor");
        Path expected = appRoot.resolve("test-trading-order-processor")
                .resolve("src/main/java/com/example/trading/order/processor/Main.java");
        assertEquals(expected, mainJava);
    }

    @Test
    public void resolveConnectorMainJavaFile_siblingOfTopLevelMain() throws Exception {
        Path appRoot = scaffoldAppRoot("trading", "com.example.trading");
        Path connectorMain = AppIntrospector.resolveConnectorMainJavaFile(appRoot, "csvOut");
        Path expected = appRoot.resolve("test-trading-csv-out")
                .resolve("src/main/java/com/example/trading/csv/out/connector/Main.java");
        assertEquals(expected, connectorMain);
    }

    // ---- resolveMessagesXmlFile / resolveStateXmlFile / resolveApiXmlFile

    @Test
    public void resolveMessagesXmlFile_returnsCorrectPath() throws Exception {
        Path appRoot = scaffoldAppRoot("trading", "com.example.trading");
        Path messages = AppIntrospector.resolveMessagesXmlFile(appRoot, "orderProcessor");
        Path expected = appRoot.resolve("test-trading-order-processor")
                .resolve("src/main/models/com/example/trading/order/processor/messages/messages.xml");
        assertEquals(expected, messages);
    }

    @Test
    public void resolveStateXmlFile_returnsCorrectPath() throws Exception {
        Path appRoot = scaffoldAppRoot("trading", "com.example.trading");
        Path state = AppIntrospector.resolveStateXmlFile(appRoot, "orderProcessor");
        Path expected = appRoot.resolve("test-trading-order-processor")
                .resolve("src/main/models/com/example/trading/order/processor/state/state.xml");
        assertEquals(expected, state);
    }

    @Test
    public void resolveApiXmlFile_returnsCorrectPath() throws Exception {
        Path appRoot = scaffoldAppRoot("trading", "com.example.trading");
        Path api = AppIntrospector.resolveApiXmlFile(appRoot, "orderProcessor");
        Path expected = appRoot.resolve("test-trading-order-processor")
                .resolve("src/main/models/com/example/trading/order/processor/api.xml");
        assertEquals(expected, api);
    }

    // ---- resolveServiceType --------------------------------------------

    @Test
    public void resolveServiceType_detectsProcessorByStateXml() throws Exception {
        Path appRoot = scaffoldAppRoot("trading", "com.example.trading");
        Path moduleDir = appRoot.resolve("test-trading-order-processor");
        Files.createDirectories(moduleDir);
        // Create state.xml in the expected location
        Path stateXml = AppIntrospector.resolveStateXmlFile(appRoot, "orderProcessor");
        Files.createDirectories(stateXml.getParent());
        Files.writeString(stateXml, "<state/>");

        assertEquals(ServiceBuilder.ServiceType.PROCESSOR,
                AppIntrospector.resolveServiceType(appRoot, "orderProcessor"));
    }

    @Test
    public void resolveServiceType_detectsCsvwriterByConnectorSubdir() throws Exception {
        Path appRoot = scaffoldAppRoot("trading", "com.example.trading");
        Path moduleDir = appRoot.resolve("test-trading-csv-out");
        Files.createDirectories(moduleDir);
        // No state.xml, but connector/ subdir exists under the service's java package
        Path mainJavaDir = AppIntrospector.resolveMainJavaFile(appRoot, "csvOut").getParent();
        Files.createDirectories(mainJavaDir.resolve("connector"));

        assertEquals(ServiceBuilder.ServiceType.CSVWRITER,
                AppIntrospector.resolveServiceType(appRoot, "csvOut"));
    }

    @Test
    public void resolveServiceType_fallsBackToDriver() throws Exception {
        Path appRoot = scaffoldAppRoot("trading", "com.example.trading");
        Path moduleDir = appRoot.resolve("test-trading-feeder");
        Files.createDirectories(moduleDir);
        // No state.xml, no connector/ subdir
        assertEquals(ServiceBuilder.ServiceType.DRIVER,
                AppIntrospector.resolveServiceType(appRoot, "feeder"));
    }

    @Test
    public void resolveServiceType_throwsWhenServiceModuleMissing() throws Exception {
        Path appRoot = scaffoldAppRoot("trading", "com.example.trading");
        try {
            AppIntrospector.resolveServiceType(appRoot, "doesNotExist");
            fail("expected IllegalArgumentException");
        } catch (IllegalArgumentException expected) {
            // ok
        }
    }

    // ---- fixtures ------------------------------------------------------

    /**
     * Build a realistic AppParams and write the {@code .rumi} file to a new
     * app root under tempDir. Returns the app root directory.
     */
    private Path scaffoldAppRoot(String appName, String packageName) throws IOException {
        ApplicationBuilder.AppParams params = fixtureAppParams(appName, packageName);
        String parentArtifactId = params.getTokenMap().get(TokenUtils.toToken("ParentArtifactId"));
        Path appRoot = tempDir.resolve(parentArtifactId);
        Files.createDirectories(appRoot);
        writeRumiJson(appRoot, params);
        return appRoot;
    }

    private ApplicationBuilder.AppParams fixtureAppParams(String appName, String packageName) {
        return new ApplicationBuilder.AppParams(
                appName,
                tempDir.toString(),
                packageName,
                "com.example",
                "test",
                "4.0.0", "4.0.0", "2.0.0",
                ApplicationBuilder.EncodingType.QUARK,
                ApplicationBuilder.MessagingProvider.ACTIVEMQ,
                ApplicationBuilder.BuildTool.MAVEN);
    }

    private void writeRumiJson(Path appRoot, ApplicationBuilder.AppParams params) throws IOException {
        try (BufferedWriter w = Files.newBufferedWriter(appRoot.resolve(".rumi"))) {
            new Gson().toJson(params, w);
        }
    }
}
