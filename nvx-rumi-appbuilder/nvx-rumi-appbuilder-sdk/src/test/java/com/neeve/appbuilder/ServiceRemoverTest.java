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

import com.neeve.appbuilder.model.ChangeSet;
import org.junit.After;
import org.junit.Before;
import org.junit.Test;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.stream.Stream;

import static org.junit.Assert.*;

public class ServiceRemoverTest {

    private Path tempDir;
    private Path appRoot;

    @Before
    public void setUp() throws IOException {
        tempDir = Files.createTempDirectory("svcremove-");
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
    public void removeService_noopWhenServiceAbsent() throws Exception {
        PhaseBTestSupport.writeParentPom(appRoot,
            "test-trading-roe", "test-trading-system");
        ChangeSet r = ServiceRemover.removeService(appRoot, "never-existed", false);
        assertTrue(r.isNoop());
    }

    @Test
    public void removeService_stripsModuleFromParentPom() throws Exception {
        PhaseBTestSupport.writeParentPom(appRoot,
            "test-trading-roe", "test-trading-system", "test-trading-feeder");
        Path moduleDir = appRoot.resolve("test-trading-feeder");
        Files.createDirectories(moduleDir);

        ChangeSet r = ServiceRemover.removeService(appRoot, "feeder", false);
        assertTrue(r.isApplied());

        String pom = Files.readString(appRoot.resolve("pom.xml"));
        assertFalse("module entry removed", pom.contains("<module>test-trading-feeder</module>"));
        assertTrue("other modules preserved", pom.contains("<module>test-trading-roe</module>"));
        assertFalse("module dir deleted", Files.exists(moduleDir));
    }

    @Test
    public void removeService_removesSystemPomDependency() throws Exception {
        PhaseBTestSupport.writeParentPom(appRoot,
            "test-trading-roe", "test-trading-system", "test-trading-feeder");
        Files.createDirectories(appRoot.resolve("test-trading-feeder"));

        Path systemPom = appRoot.resolve("test-trading-system").resolve("pom.xml");
        Files.createDirectories(systemPom.getParent());
        Files.writeString(systemPom,
            "<project>\n" +
            "  <dependencies>\n" +
            "    <dependency>\n" +
            "      <groupId>com.example</groupId>\n" +
            "      <artifactId>test-trading-feeder</artifactId>\n" +
            "      <version>${project.version}</version>\n" +
            "    </dependency>\n" +
            "    <dependency>\n" +
            "      <groupId>com.example</groupId>\n" +
            "      <artifactId>test-trading-roe</artifactId>\n" +
            "      <version>${project.version}</version>\n" +
            "    </dependency>\n" +
            "  </dependencies>\n" +
            "</project>\n");

        ServiceRemover.removeService(appRoot, "feeder", false);

        String written = Files.readString(systemPom);
        assertFalse("feeder dependency gone", written.contains("test-trading-feeder"));
        assertTrue("roe dependency retained", written.contains("test-trading-roe"));
    }

    @Test
    public void removeService_stripsConfigFragments() throws Exception {
        PhaseBTestSupport.writeParentPom(appRoot,
            "test-trading-roe", "test-trading-system", "test-trading-feeder");
        Files.createDirectories(appRoot.resolve("test-trading-feeder"));

        // Config with fragments named after the service.
        PhaseBTestSupport.writeConfigXml(appRoot,
            "<model xmlns=\"http://www.neeveresearch.com/schema/x-ddl\">" +
            "<apps>" +
            "  <templates>" +
            "    <app name=\"trading-feeder-template\"/>" +
            "    <app name=\"other-template\"/>" +
            "  </templates>" +
            "</apps>" +
            "<profiles><profile name=\"cloud\">" +
            "  <xvms><templates>" +
            "    <xvm name=\"trading-feeder-xvm-template\"/>" +
            "  </templates></xvms>" +
            "</profile></profiles>" +
            "</model>");

        ServiceRemover.removeService(appRoot, "feeder", false);

        Path configPath = appRoot.resolve("test-trading-system").resolve("conf/config.xml");
        String config = Files.readString(configPath);
        assertFalse("service-named app template removed",
            config.contains("trading-feeder-template"));
        assertFalse("profile xvm template for service removed",
            config.contains("trading-feeder-xvm-template"));
        assertTrue("unrelated template preserved",
            config.contains("other-template"));
    }

    @Test
    public void removeService_reportsFactoryIdsReleasedInChangeSet() throws Exception {
        PhaseBTestSupport.writeParentPom(appRoot,
            "test-trading-roe", "test-trading-system", "test-trading-feeder");
        Files.createDirectories(appRoot.resolve("test-trading-feeder"));

        // Give the service a messages.xml with one factory to make its IDs discoverable.
        PhaseBTestSupport.writeMessagesXml(appRoot, "feeder",
            "<model xmlns=\"http://www.neeveresearch.com/schema/x-adml\">" +
            "<factories><factory name=\"MessageFactory\" id=\"7\" className=\"com.example.trading.feeder.messages.MessageFactory\"/></factories>" +
            "<messages/>" +
            "</model>");

        ChangeSet r = ServiceRemover.removeService(appRoot, "feeder", false);
        assertTrue(r.isApplied());
        assertTrue("factory ID 7 released", r.getFactoryIdsReleased().contains(7));
    }

    @Test
    public void removeService_dryRunDoesNotTouchDisk() throws Exception {
        PhaseBTestSupport.writeParentPom(appRoot,
            "test-trading-roe", "test-trading-system", "test-trading-feeder");
        Path moduleDir = appRoot.resolve("test-trading-feeder");
        Files.createDirectories(moduleDir);
        Files.writeString(moduleDir.resolve("pom.xml"), "<project/>");
        String pomBefore = Files.readString(appRoot.resolve("pom.xml"));

        ChangeSet r = ServiceRemover.removeService(appRoot, "feeder", true);
        assertFalse(r.isApplied());

        assertEquals("parent pom untouched on dry run", pomBefore, Files.readString(appRoot.resolve("pom.xml")));
        assertTrue("module dir retained on dry run", Files.exists(moduleDir));
        assertFalse("but modified-files list includes it", r.getFilesModified().isEmpty());
    }

    @Test
    public void removeService_removesAllAppNamedFragmentsAcrossScopes() throws Exception {
        PhaseBTestSupport.writeParentPom(appRoot,
            "test-trading-roe", "test-trading-system", "test-trading-proc");
        Files.createDirectories(appRoot.resolve("test-trading-proc"));

        PhaseBTestSupport.writeConfigXml(appRoot,
            "<model xmlns=\"http://www.neeveresearch.com/schema/x-ddl\">" +
            "<apps><templates>" +
            "  <app name=\"trading-proc-template\"/>" +
            "</templates></apps>" +
            "<xvms><templates>" +
            "  <xvm name=\"trading-proc-xvm-template\"/>" +
            "</templates></xvms>" +
            "<profiles>" +
            "  <profile name=\"cloud\">" +
            "    <apps><templates><app name=\"trading-proc-cloud-template\"/></templates></apps>" +
            "    <xvms><templates><xvm name=\"trading-proc-cloud-xvm-template\"/></templates></xvms>" +
            "  </profile>" +
            "  <profile name=\"standalone\">" +
            "    <apps><templates><app name=\"trading-proc-sa-template\"/></templates></apps>" +
            "  </profile>" +
            "</profiles>" +
            "</model>");

        ServiceRemover.removeService(appRoot, "proc", false);

        String config = Files.readString(appRoot.resolve("test-trading-system").resolve("conf/config.xml"));
        assertFalse(config.contains("trading-proc-template"));
        assertFalse(config.contains("trading-proc-xvm-template"));
        assertFalse(config.contains("trading-proc-cloud-template"));
        assertFalse(config.contains("trading-proc-cloud-xvm-template"));
        assertFalse(config.contains("trading-proc-sa-template"));
    }
}
