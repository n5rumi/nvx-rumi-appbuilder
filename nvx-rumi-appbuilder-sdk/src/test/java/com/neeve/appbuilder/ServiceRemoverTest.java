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
            "    <xvm name=\"trading-feeder-template\"/>" +
            "  </templates></xvms>" +
            "</profile></profiles>" +
            "</model>");

        ServiceRemover.removeService(appRoot, "feeder", false);

        Path configPath = appRoot.resolve("test-trading-system").resolve("conf/config.xml");
        String config = Files.readString(configPath);
        assertFalse("service-named app template removed",
            config.contains("trading-feeder-template"));
        assertFalse("profile xvm template for service removed",
            config.contains("trading-feeder-template"));
        assertTrue("unrelated template preserved",
            config.contains("other-template"));
    }

    /**
     * RUMI-422. Removing the templates but not the instances that point at them
     * left the app unbootable: valid XML, a success result, and a first-boot
     * failure naming the template rather than the removal that orphaned it.
     */
    @Test
    public void removeService_stripsInstancesNotJustTemplates() throws Exception {
        PhaseBTestSupport.writeParentPom(appRoot,
            "test-trading-roe", "test-trading-system", "test-trading-feeder");
        Files.createDirectories(appRoot.resolve("test-trading-feeder"));

        PhaseBTestSupport.writeConfigXml(appRoot,
            "<model xmlns=\"http://www.neeveresearch.com/schema/x-ddl\">" +
            "<apps>" +
            "  <templates>" +
            "    <app name=\"trading-feeder-template\"/>" +
            "    <app name=\"trading-keeper-template\"/>" +
            "  </templates>" +
            "  <app name=\"trading-feeder-1\" template=\"trading-feeder-template\"/>" +
            "  <app name=\"trading-keeper-1\" template=\"trading-keeper-template\"/>" +
            "</apps>" +
            "<xvms>" +
            "  <templates>" +
            "    <xvm name=\"trading-feeder-template\"/>" +
            "  </templates>" +
            "  <xvm name=\"trading-feeder-1-1\" template=\"trading-feeder-template\"/>" +
            "  <xvm name=\"trading-keeper-1-1\" template=\"trading-keeper-template\"/>" +
            "</xvms>" +
            "<profiles>" +
            "  <profile name=\"cloud\">" +
            "    <xvms><xvm name=\"trading-feeder-1-1\" template=\"trading-feeder-template\"/></xvms>" +
            "  </profile>" +
            "  <profile name=\"standalone\">" +
            "    <xvms><xvm name=\"trading-feeder-1-1\" template=\"trading-feeder-template\"/></xvms>" +
            "  </profile>" +
            "</profiles>" +
            "</model>");

        ServiceRemover.removeService(appRoot, "feeder", false);

        Path configPath = appRoot.resolve("test-trading-system").resolve("conf/config.xml");
        String config = Files.readString(configPath);

        assertFalse("no reference to the removed service survives anywhere",
            config.contains("trading-feeder"));
        assertTrue("the other service's template is untouched",
            config.contains("trading-keeper-template"));
        assertTrue("the other service's app instance is untouched",
            config.contains("trading-keeper-1"));
        assertTrue("the other service's xvm instance is untouched",
            config.contains("trading-keeper-1-1"));
    }

    /**
     * An instance someone renamed off the scaffolder's convention is still
     * orphaned by the removal, so it is matched on the template it references
     * rather than only on its own name.
     */
    @Test
    public void removeService_stripsRenamedInstanceByTemplateReference() throws Exception {
        PhaseBTestSupport.writeParentPom(appRoot,
            "test-trading-roe", "test-trading-system", "test-trading-feeder");
        Files.createDirectories(appRoot.resolve("test-trading-feeder"));

        PhaseBTestSupport.writeConfigXml(appRoot,
            "<model xmlns=\"http://www.neeveresearch.com/schema/x-ddl\">" +
            "<apps>" +
            "  <templates><app name=\"trading-feeder-template\"/></templates>" +
            "  <app name=\"market-data-primary\" template=\"trading-feeder-template\"/>" +
            "</apps>" +
            "</model>");

        ServiceRemover.removeService(appRoot, "feeder", false);

        String config = Files.readString(
            appRoot.resolve("test-trading-system").resolve("conf/config.xml"));
        assertFalse("instance removed via its template reference, despite the name",
            config.contains("market-data-primary"));
        assertFalse("template removed", config.contains("trading-feeder-template"));
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

        // Names as the scaffolder actually emits them (templates/maven/config/**):
        // {service}, {service}-template, {service}-{partition}[-{instance}].
        // Profiles carry INSTANCE overrides, not template overrides, and those
        // overrides carry no template attribute -- so they are only reachable
        // by name.
        PhaseBTestSupport.writeConfigXml(appRoot,
            "<model xmlns=\"http://www.neeveresearch.com/schema/x-ddl\">" +
            "<apps>" +
            "  <templates><app name=\"trading-proc-template\"/></templates>" +
            "  <app name=\"trading-proc-1\" template=\"trading-proc-template\"/>" +
            "</apps>" +
            "<xvms>" +
            "  <templates><xvm name=\"trading-proc-template\"/></templates>" +
            "  <xvm name=\"trading-proc-1-1\" template=\"trading-proc-template\"/>" +
            "</xvms>" +
            "<profiles>" +
            "  <profile name=\"cloud\">" +
            "    <xvms><xvm name=\"trading-proc-1-1\" enabled=\"true\"/></xvms>" +
            "  </profile>" +
            "  <profile name=\"standalone\">" +
            "    <xvms><xvm name=\"trading-proc-1-1\" enabled=\"true\"/></xvms>" +
            "  </profile>" +
            "</profiles>" +
            "</model>");

        ServiceRemover.removeService(appRoot, "proc", false);

        String config = Files.readString(appRoot.resolve("test-trading-system").resolve("conf/config.xml"));
        assertFalse("nothing named for the service survives in any scope",
            config.contains("trading-proc"));
    }

    /**
     * RUMI-422 review. A sibling service whose kebab name extends the removed
     * one shares its prefix, so a startsWith test takes the sibling's fragments
     * too -- and reports success, leaving a service with a module and a POM
     * entry but no config and no XVM.
     */
    @Test
    public void removeService_leavesSiblingWhoseNameExtendsIt() throws Exception {
        PhaseBTestSupport.writeParentPom(appRoot,
            "test-trading-roe", "test-trading-system",
            "test-trading-order-feed", "test-trading-order-feed-replay");
        Files.createDirectories(appRoot.resolve("test-trading-order-feed"));
        Files.createDirectories(appRoot.resolve("test-trading-order-feed-replay"));

        PhaseBTestSupport.writeConfigXml(appRoot,
            "<model xmlns=\"http://www.neeveresearch.com/schema/x-ddl\">" +
            "<apps>" +
            "  <templates>" +
            "    <app name=\"trading-order-feed-template\"/>" +
            "    <app name=\"trading-order-feed-replay-template\"/>" +
            "  </templates>" +
            "  <app name=\"trading-order-feed-1\" template=\"trading-order-feed-template\"/>" +
            "  <app name=\"trading-order-feed-replay-1\" template=\"trading-order-feed-replay-template\"/>" +
            "</apps>" +
            "<xvms>" +
            "  <xvm name=\"trading-order-feed-1-1\" template=\"trading-order-feed-template\"/>" +
            "  <xvm name=\"trading-order-feed-replay-1-1\" template=\"trading-order-feed-replay-template\"/>" +
            "</xvms>" +
            "</model>");

        ServiceRemover.removeService(appRoot, "orderFeed", false);

        String config = Files.readString(appRoot.resolve("test-trading-system").resolve("conf/config.xml"));
        assertFalse("the removed service is gone",
            config.contains("trading-order-feed-template\""));
        assertTrue("sibling template survives",
            config.contains("trading-order-feed-replay-template"));
        assertTrue("sibling app instance survives",
            config.contains("trading-order-feed-replay-1"));
        assertTrue("sibling xvm survives",
            config.contains("trading-order-feed-replay-1-1"));
    }

    /**
     * A connector service injects a bus named exactly after the service. Left
     * behind, it names a class that no longer exists.
     */
    @Test
    public void removeService_stripsTheServiceBus() throws Exception {
        PhaseBTestSupport.writeParentPom(appRoot,
            "test-trading-roe", "test-trading-system", "test-trading-feeder");
        Files.createDirectories(appRoot.resolve("test-trading-feeder"));

        PhaseBTestSupport.writeConfigXml(appRoot,
            "<model xmlns=\"http://www.neeveresearch.com/schema/x-ddl\">" +
            "<buses>" +
            "  <bus name=\"trading-feeder\" descriptor=\"connector://.&amp;classname=x.feeder.connector.Main\"/>" +
            "  <bus name=\"trading-main\" descriptor=\"loopback://x\"/>" +
            "</buses>" +
            "</model>");

        ServiceRemover.removeService(appRoot, "feeder", false);

        String config = Files.readString(appRoot.resolve("test-trading-system").resolve("conf/config.xml"));
        assertFalse("the service's own bus is removed", config.contains("trading-feeder"));
        assertTrue("the shared bus is untouched", config.contains("trading-main"));
    }

    /**
     * An XVM that survives can still host an app of the removed service:
     * xvms/config.xml nests &lt;apps&gt;&lt;app name="{service}-{n}"/&gt;. That is the
     * same dangling reference one level down.
     */
    @Test
    public void removeService_stripsNestedAppRefInsideASurvivingXvm() throws Exception {
        PhaseBTestSupport.writeParentPom(appRoot,
            "test-trading-roe", "test-trading-system", "test-trading-feeder");
        Files.createDirectories(appRoot.resolve("test-trading-feeder"));

        PhaseBTestSupport.writeConfigXml(appRoot,
            "<model xmlns=\"http://www.neeveresearch.com/schema/x-ddl\">" +
            "<xvms>" +
            "  <xvm name=\"trading-shared-1-1\">" +
            "    <apps>" +
            "      <app name=\"trading-feeder-1\" autoStart=\"true\"/>" +
            "      <app name=\"trading-keeper-1\" autoStart=\"true\"/>" +
            "    </apps>" +
            "  </xvm>" +
            "</xvms>" +
            "</model>");

        ServiceRemover.removeService(appRoot, "feeder", false);

        String config = Files.readString(appRoot.resolve("test-trading-system").resolve("conf/config.xml"));
        assertTrue("the shared xvm itself survives", config.contains("trading-shared-1-1"));
        assertFalse("its reference to the removed app is gone",
            config.contains("trading-feeder-1"));
        assertTrue("the other app it hosts is untouched",
            config.contains("trading-keeper-1"));
    }
}
