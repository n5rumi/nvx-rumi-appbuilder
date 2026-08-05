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

import com.neeve.appbuilder.test.TestAppFactory;

import org.junit.After;
import org.junit.Before;
import org.junit.Test;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.stream.Stream;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

/**
 * The sample-free scaffold (RUMI-382).
 *
 * <p>Agents driving the Dev MCP were scaffolding an app and then spending tokens
 * deleting the worked example code before they could add their own. These tests
 * pin the contract that removed that step: what bare mode strips, what it must
 * keep, and -- the part most likely to break by accident -- that an app's mode
 * survives in its {@code .rumi} so services added later inherit it.
 *
 * <p>They assert on the scaffold's <em>output</em> rather than on the marker
 * mechanism, so they stay honest if the mechanism is ever replaced.
 */
public class SampleFreeScaffoldTest {

    /** Identifiers that exist only to demonstrate; none may survive bare mode. */
    private static final String[] SAMPLE_IDENTIFIERS = {
        "EchoRequest", "EchoResponse", "onEchoRequest",
        "SampleConnectorMessage", "AlarmMessage", "EmptyMessage", "scheduleNextAlarm",
        "SendingThread", "UtlGovernor",
    };

    private Path tempDir;

    @Before
    public void setUp() throws IOException {
        tempDir = Files.createTempDirectory("appbuilder-sample-free-");
    }

    @After
    public void tearDown() throws IOException {
        TestAppFactory.deleteRecursive(tempDir);
    }

    // ---- bare mode ---------------------------------------------------

    @Test
    public void bareScaffoldContainsNoSampleArtifacts() throws Exception {
        final Path appRoot = scaffoldAllServiceTypes(false);
        final List<String> hits = new ArrayList<>();
        for (String identifier : SAMPLE_IDENTIFIERS) {
            for (Path file : sourceFiles(appRoot)) {
                if (Files.readString(file).contains(identifier)) {
                    hits.add(identifier + " in " + appRoot.relativize(file));
                }
            }
        }
        assertTrue("bare scaffold still carries sample code:\n  " + String.join("\n  ", hits),
                   hits.isEmpty());
    }

    @Test
    public void bareScaffoldKeepsTheWiringAndTheGuidance() throws Exception {
        final Path appRoot = scaffoldAllServiceTypes(false);

        // The injection points are the seams an agent writes against; stripping
        // the samples must not strip the wiring they were demonstrating.
        final String webserviceMain = read(appRoot, "test-bare-webservice/src/main/java/com/example/bare/webservice/Main.java");
        assertTrue(webserviceMain.contains("@AppStateFactoryAccessor"));
        assertTrue(webserviceMain.contains("onMessagingPrestart"));
        assertTrue(webserviceMain.contains("onEngineStopped"));
        // ...and the explanatory javadoc survives, since reading it once is far
        // cheaper for an agent than deleting sample code.
        assertTrue(webserviceMain.contains("injectRequestAndWaitForReply"));

        final String webMain = read(appRoot, "test-bare-webservice/src/main/java/com/example/bare/webservice/resources/WebMain.java");
        assertTrue("a JAX-RS resource with no methods at all is a shape we do not want to ship",
                   webMain.contains("@Path(\"/health\")"));
        assertTrue(webMain.contains("AepEngineProvider _engineProvider"));

        final String connectorMain = read(appRoot, "test-bare-connector/src/main/java/com/example/bare/connector/Main.java");
        assertTrue(connectorMain.contains("setEngine"));
        assertTrue(connectorMain.contains("setMessageSender"));

        final String driverMain = read(appRoot, "test-bare-driver/src/main/java/com/example/bare/driver/Main.java");
        assertTrue(driverMain.contains("setMessageSender"));
    }

    @Test
    public void bareModelsAreEmptyButStructurallyIntact() throws Exception {
        final Path appRoot = scaffoldAllServiceTypes(false);

        // The state entity itself is structural -- the app state factory returns
        // it -- so bare drops its sample field, not the entity.
        final String state = read(appRoot, "test-bare-webservice/src/main/models/com/example/bare/webservice/state/state.xml");
        assertTrue(state.contains("<entity name=\"Repository\""));
        assertFalse(state.contains("count"));

        // Factories are structural too: the generated MessageFactory is what
        // makes the messages package exist, which the wildcard imports need.
        final String messages = read(appRoot, "test-bare-connector/src/main/models/com/example/bare/connector/messages/messages.xml");
        assertTrue(messages.contains("<factory name=\"MessageFactory\""));
        assertFalse(messages.contains("<message "));
    }

    // ---- samples mode (the default) ----------------------------------

    @Test
    public void samplesRemainTheDefaultForEveryExistingCaller() throws Exception {
        final Path appRoot = scaffoldAllServiceTypes(true);
        final String webserviceMain = read(appRoot, "test-samples-webservice/src/main/java/com/example/samples/webservice/Main.java");
        assertTrue(webserviceMain.contains("onEchoRequest"));

        final String driverMain = read(appRoot, "test-samples-driver/src/main/java/com/example/samples/driver/Main.java");
        assertTrue(driverMain.contains("SendingThread"));

        final String connectorMessages = read(appRoot, "test-samples-connector/src/main/models/com/example/samples/connector/messages/messages.xml");
        assertTrue(connectorMessages.contains("SampleConnectorMessage"));
    }

    @Test
    public void neitherModeShipsAMarkerLine() throws Exception {
        for (boolean includeSamples : new boolean[] { true, false }) {
            final Path appRoot = scaffoldAllServiceTypes(includeSamples);
            for (Path file : sourceFiles(appRoot)) {
                final String content = Files.readString(file);
                assertFalse(file + " leaked a sample marker", content.contains("@sample-begin"));
                assertFalse(file + " leaked a sample marker", content.contains("@sample-end"));
                assertFalse(file + " leaked a bare marker", content.contains("@bare-begin"));
                assertFalse(file + " leaked a bare marker", content.contains("@bare-end"));
            }
            TestAppFactory.deleteRecursive(appRoot);
        }
    }

    // ---- the mode is a property of the app ---------------------------

    @Test
    public void servicesInheritTheModeTheAppWasScaffoldedIn() throws Exception {
        final Path appRoot = TestAppFactory.newApp("inherit")
            .packageName("com.example.inherit")
            .artifactPrefix("test")
            .includeSamples(false)
            .scaffoldAt(tempDir);

        // Note: no per-service flag. The app said bare, so the service is bare.
        TestAppFactory.addWebservice(appRoot, "web");

        final String main = read(appRoot, "test-inherit-web/src/main/java/com/example/inherit/web/Main.java");
        assertFalse(main.contains("onEchoRequest"));
        assertEquals(false, ApplicationBuilder.AppParams.read(appRoot).isIncludeSamples());
    }

    @Test
    public void anExplicitPerServiceFlagOverridesTheApp() throws Exception {
        final Path appRoot = TestAppFactory.newApp("override")
            .packageName("com.example.override")
            .artifactPrefix("test")
            .includeSamples(false)
            .scaffoldAt(tempDir);

        new ServiceBuilder().createService(new ServiceBuilder.ServiceParams(
            appRoot.toString(), "web", ServiceBuilder.ServiceType.WEBSERVICE,
            ServiceBuilder.ServiceHAModel.STATE_REPLICATION, false, 1, Boolean.TRUE));

        final String main = read(appRoot, "test-override-web/src/main/java/com/example/override/web/Main.java");
        assertTrue(main.contains("onEchoRequest"));
    }

    /**
     * The Gson trap. A {@code .rumi} written before this option existed has no
     * {@code includeSamples} key; had the field been a primitive boolean it would
     * deserialize to false and silently flip every pre-existing app to bare.
     */
    @Test
    public void aDescriptorPredatingTheOptionStillMeansSamplesOn() throws Exception {
        final Path appRoot = TestAppFactory.newApp("legacy")
            .packageName("com.example.legacy")
            .artifactPrefix("test")
            .scaffoldAt(tempDir);

        final Path descriptor = appRoot.resolve(".rumi");
        final String stripped = Files.readString(descriptor)
            .replaceAll(",?\"includeSamples\":(true|false)", "");
        assertFalse("fixture did not actually remove the key", stripped.contains("includeSamples"));
        Files.writeString(descriptor, stripped);

        assertTrue(ApplicationBuilder.AppParams.read(appRoot).isIncludeSamples());

        TestAppFactory.addWebservice(appRoot, "web");
        assertTrue(read(appRoot, "test-legacy-web/src/main/java/com/example/legacy/web/Main.java")
                       .contains("onEchoRequest"));
    }

    // ---- helpers -----------------------------------------------------

    private Path scaffoldAllServiceTypes(boolean includeSamples) throws Exception {
        final String name = includeSamples ? "samples" : "bare";
        final Path appRoot = TestAppFactory.newApp(name)
            .packageName("com.example." + name)
            .artifactPrefix("test")
            .includeSamples(includeSamples)
            .scaffoldAt(tempDir);
        TestAppFactory.addProcessor(appRoot, "processor");
        TestAppFactory.addDriver(appRoot, "driver");
        TestAppFactory.addConnector(appRoot, "connector");
        TestAppFactory.addWebservice(appRoot, "webservice");
        return appRoot;
    }

    private static String read(Path appRoot, String relative) throws IOException {
        return Files.readString(appRoot.resolve(relative));
    }

    private static List<Path> sourceFiles(Path appRoot) throws IOException {
        try (Stream<Path> walk = Files.walk(appRoot)) {
            final List<Path> files = new ArrayList<>();
            walk.filter(Files::isRegularFile)
                .filter(p -> {
                    final String n = p.getFileName().toString();
                    return n.endsWith(".java") || n.endsWith(".xml");
                })
                .forEach(files::add);
            return files;
        }
    }
}
