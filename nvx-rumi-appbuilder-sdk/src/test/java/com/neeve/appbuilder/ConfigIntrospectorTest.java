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

import com.neeve.appbuilder.model.ConfigFragment;
import com.neeve.appbuilder.model.ElementSelector;
import org.junit.After;
import org.junit.Before;
import org.junit.Test;
import org.w3c.dom.Document;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.stream.Collectors;
import java.util.stream.Stream;

import static org.junit.Assert.*;

public class ConfigIntrospectorTest {

    private Path tempDir;
    private Path appRoot;

    @Before
    public void setUp() throws IOException {
        tempDir = Files.createTempDirectory("cfgintr-");
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
    public void getConfig_nullWhenMissing() throws Exception {
        assertNull(ConfigIntrospector.getConfig(appRoot));
    }

    @Test
    public void getConfig_returnsDocumentWhenPresent() throws Exception {
        PhaseBTestSupport.writeConfigXml(appRoot,
            "<model xmlns=\"http://www.neeveresearch.com/schema/x-ddl\"><env/></model>");
        Document doc = ConfigIntrospector.getConfig(appRoot);
        assertNotNull(doc);
        assertEquals("model", doc.getDocumentElement().getLocalName());
    }

    @Test
    public void listFragments_collectsRootLevelScopes() throws Exception {
        PhaseBTestSupport.writeConfigXml(appRoot,
            "<model xmlns=\"http://www.neeveresearch.com/schema/x-ddl\">" +
            "  <env><TRADING_ROOT>/tmp</TRADING_ROOT></env>" +
            "  <buses><bus name=\"trading\" descriptor=\"...\"/></buses>" +
            "  <apps><templates><app name=\"orders-template\"/><app name=\"trades-template\"/></templates></apps>" +
            "  <xvms><templates><xvm name=\"xvm-template\"/></templates></xvms>" +
            "</model>");

        List<ConfigFragment> frags = ConfigIntrospector.listFragments(appRoot, null);
        // 1 env child + 1 bus + 2 app templates + 1 xvm template = 5
        assertEquals(5, frags.size());
        assertEquals(1, countByScope(frags, "env"));
        assertEquals(1, countByScope(frags, "buses"));
        assertEquals(2, countByScope(frags, "apps/templates"));
        assertEquals(1, countByScope(frags, "xvms/templates"));
    }

    @Test
    public void listFragments_collectsProfileScopes() throws Exception {
        PhaseBTestSupport.writeConfigXml(appRoot,
            "<model xmlns=\"http://www.neeveresearch.com/schema/x-ddl\">" +
            "  <profiles>" +
            "    <profile name=\"cloud\">" +
            "      <env><C>1</C></env>" +
            "      <apps><templates><app name=\"cloud-app-t\"/></templates></apps>" +
            "      <xvms><templates><xvm name=\"cloud-xvm-t\"/></templates></xvms>" +
            "    </profile>" +
            "    <profile name=\"standalone\">" +
            "      <apps><templates><app name=\"sa-app-t\"/></templates></apps>" +
            "    </profile>" +
            "  </profiles>" +
            "</model>");

        List<ConfigFragment> all = ConfigIntrospector.listFragments(appRoot, null);
        assertEquals(4, all.size());
        assertEquals(1, countByScope(all, "profiles/cloud/env"));
        assertEquals(1, countByScope(all, "profiles/cloud/apps/templates"));
        assertEquals(1, countByScope(all, "profiles/cloud/xvms/templates"));
        assertEquals(1, countByScope(all, "profiles/standalone/apps/templates"));
    }

    @Test
    public void listFragments_filtersByProfileName() throws Exception {
        PhaseBTestSupport.writeConfigXml(appRoot,
            "<model xmlns=\"http://www.neeveresearch.com/schema/x-ddl\">" +
            "  <apps><templates><app name=\"root-t\"/></templates></apps>" +
            "  <profiles>" +
            "    <profile name=\"cloud\">" +
            "      <apps><templates><app name=\"cloud-t\"/></templates></apps>" +
            "    </profile>" +
            "    <profile name=\"standalone\">" +
            "      <apps><templates><app name=\"sa-t\"/></templates></apps>" +
            "    </profile>" +
            "  </profiles>" +
            "</model>");

        List<ConfigFragment> cloudOnly = ConfigIntrospector.listFragments(appRoot, "cloud");
        // root-level apps/templates plus cloud's apps/templates; standalone omitted.
        assertEquals(2, cloudOnly.size());
        assertTrue(cloudOnly.stream().anyMatch(f -> "root-t".equals(f.getName())));
        assertTrue(cloudOnly.stream().anyMatch(f -> "cloud-t".equals(f.getName())));
        assertTrue(cloudOnly.stream().noneMatch(f -> "sa-t".equals(f.getName())));
    }

    @Test
    public void listFragments_fragmentsCarryNameAndTag() throws Exception {
        PhaseBTestSupport.writeConfigXml(appRoot,
            "<model xmlns=\"http://www.neeveresearch.com/schema/x-ddl\">" +
            "  <apps><templates><app name=\"orders-template\"/></templates></apps>" +
            "  <buses><bus name=\"trading\"/></buses>" +
            "</model>");
        List<ConfigFragment> frags = ConfigIntrospector.listFragments(appRoot, null);
        ConfigFragment app = frags.stream()
            .filter(f -> "apps/templates".equals(scope(f))).findFirst().orElseThrow(AssertionError::new);
        assertEquals("app", app.getTagName());
        assertEquals("orders-template", app.getName());

        ConfigFragment bus = frags.stream()
            .filter(f -> "buses".equals(scope(f))).findFirst().orElseThrow(AssertionError::new);
        assertEquals("bus", bus.getTagName());
        assertEquals("trading", bus.getName());
    }

    @Test
    public void listFragments_emptyWhenFileMissing() throws Exception {
        assertTrue(ConfigIntrospector.listFragments(appRoot, null).isEmpty());
    }

    private static long countByScope(List<ConfigFragment> frags, String joined) {
        return frags.stream().filter(f -> joined.equals(scope(f))).count();
    }

    private static String scope(ConfigFragment f) {
        return f.getScopePath().stream().collect(Collectors.joining("/"));
    }

    // ---- narrowed reads (RUMI-413) --------------------------------------

    private void writeTwoXvms() throws Exception {
        PhaseBTestSupport.writeConfigXml(appRoot,
            "<model xmlns=\"http://www.neeveresearch.com/schema/x-ddl\">"
          + "  <env><a>1</a></env>"
          + "  <xvms><templates>"
          + "    <xvm name=\"inference\"><env><HEAP>2g</HEAP></env></xvm>"
          + "    <xvm name=\"web\"><env><HEAP>512m</HEAP></env></xvm>"
          + "  </templates></xvms>"
          + "</model>");
    }

    @Test
    public void listFragments_narrowsToOneScopePath() throws Exception {
        writeTwoXvms();
        List<ConfigFragment> all = ConfigIntrospector.listFragments(appRoot, null);
        List<ConfigFragment> scoped = ConfigIntrospector.listFragments(
            appRoot, null, List.of("xvms", "templates"), null);

        assertTrue("the unnarrowed read sees more than the scope", all.size() > scoped.size());
        assertEquals(2, scoped.size());
        for (ConfigFragment f : scoped) {
            assertEquals(List.of("xvms", "templates"), f.getScopePath());
        }
    }

    /**
     * The case this ticket exists for: answering "what is in this one xvm's env
     * block?" without reading the whole config to find it.
     */
    @Test
    public void listFragments_narrowsToOneNamedFragmentCarryingItsEnv() throws Exception {
        writeTwoXvms();
        List<ConfigFragment> hit = ConfigIntrospector.listFragments(
            appRoot, null, List.of("xvms", "templates"),
            ElementSelector.byTagAndName("xvm", "inference"));

        assertEquals(1, hit.size());
        assertEquals("inference", hit.get(0).getName());
        // The selected fragment carries its own env subtree, which is what makes
        // a targeted read a substitute for reading the file.
        assertEquals("2g", hit.get(0).getElement()
            .getElementsByTagName("HEAP").item(0).getTextContent());
    }

    @Test
    public void listFragments_selectorWithoutScopePathStillNarrows() throws Exception {
        writeTwoXvms();
        List<ConfigFragment> hit = ConfigIntrospector.listFragments(
            appRoot, null, null, ElementSelector.byName("web"));
        assertEquals(1, hit.size());
        assertEquals("web", hit.get(0).getName());
    }

    @Test
    public void listFragments_bothNullIsTheUnnarrowedRead() throws Exception {
        writeTwoXvms();
        assertEquals(ConfigIntrospector.listFragments(appRoot, null).size(),
                     ConfigIntrospector.listFragments(appRoot, null, null, null).size());
    }

    @Test
    public void listFragments_aScopePathThatMatchesNothingReturnsEmpty() throws Exception {
        writeTwoXvms();
        assertTrue(ConfigIntrospector.listFragments(
            appRoot, null, List.of("apps", "templates"), null).isEmpty());
    }
}
