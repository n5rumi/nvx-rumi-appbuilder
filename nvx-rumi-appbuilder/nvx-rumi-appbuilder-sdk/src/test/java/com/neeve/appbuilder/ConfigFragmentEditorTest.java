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
import com.neeve.appbuilder.model.ConfigFragment;
import com.neeve.appbuilder.model.ElementSelector;
import org.junit.After;
import org.junit.Before;
import org.junit.Test;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.stream.Stream;

import static org.junit.Assert.*;

public class ConfigFragmentEditorTest {

    private Path tempDir;
    private Path appRoot;

    private static final String EMPTY_CONFIG =
        "<model xmlns=\"http://www.neeveresearch.com/schema/x-ddl\">" +
        "<env/>" +
        "<buses/>" +
        "<apps><templates/></apps>" +
        "<xvms><templates/></xvms>" +
        "<profiles><profile name=\"cloud\"><apps><templates/></apps></profile></profiles>" +
        "</model>";

    @Before
    public void setUp() throws IOException {
        tempDir = Files.createTempDirectory("cfgedit-");
        appRoot = PhaseBTestSupport.scaffoldApp(tempDir, "trading", "com.example.trading");
        PhaseBTestSupport.writeConfigXml(appRoot, EMPTY_CONFIG);
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
    public void addFragment_insertsAtRootAppsTemplates() throws Exception {
        ChangeSet r = ConfigFragmentEditor.addFragment(appRoot,
            List.of("apps", "templates"),
            "<app xmlns=\"http://www.neeveresearch.com/schema/x-ddl\" name=\"orders-template\"/>",
            false);
        assertTrue(r.isApplied());

        List<ConfigFragment> frags = ConfigIntrospector.listFragments(appRoot, null);
        assertTrue(frags.stream().anyMatch(f -> "orders-template".equals(f.getName())));
    }

    @Test
    public void addFragment_isIdempotentOnEquivalentFragment() throws Exception {
        String fragment = "<app xmlns=\"http://www.neeveresearch.com/schema/x-ddl\" name=\"dup\"/>";
        ConfigFragmentEditor.addFragment(appRoot, List.of("apps", "templates"), fragment, false);
        ChangeSet second = ConfigFragmentEditor.addFragment(appRoot,
            List.of("apps", "templates"), fragment, false);
        assertTrue(second.isNoop());
    }

    @Test
    public void addFragment_createsProfileScopeOnDemand() throws Exception {
        ChangeSet r = ConfigFragmentEditor.addFragment(appRoot,
            List.of("profiles", "standalone", "apps", "templates"),
            "<app xmlns=\"http://www.neeveresearch.com/schema/x-ddl\" name=\"sa-t\"/>",
            false);
        assertTrue(r.isApplied());
        // Profile "standalone" didn't exist; it was created.
        List<ConfigFragment> saOnly = ConfigIntrospector.listFragments(appRoot, "standalone");
        assertTrue(saOnly.stream().anyMatch(f -> "sa-t".equals(f.getName())));
    }

    @Test
    public void removeFragment_byTagAndName() throws Exception {
        ConfigFragmentEditor.addFragment(appRoot,
            List.of("apps", "templates"),
            "<app xmlns=\"http://www.neeveresearch.com/schema/x-ddl\" name=\"to-go\"/>",
            false);
        ConfigFragmentEditor.addFragment(appRoot,
            List.of("apps", "templates"),
            "<app xmlns=\"http://www.neeveresearch.com/schema/x-ddl\" name=\"to-stay\"/>",
            false);

        ChangeSet r = ConfigFragmentEditor.removeFragment(appRoot,
            List.of("apps", "templates"),
            ElementSelector.byTagAndName("app", "to-go"),
            false);
        assertTrue(r.isApplied());

        List<ConfigFragment> frags = ConfigIntrospector.listFragments(appRoot, null);
        assertTrue(frags.stream().noneMatch(f -> "to-go".equals(f.getName())));
        assertTrue(frags.stream().anyMatch(f -> "to-stay".equals(f.getName())));
    }

    @Test
    public void removeFragment_noopWhenNothingMatches() throws Exception {
        ChangeSet r = ConfigFragmentEditor.removeFragment(appRoot,
            List.of("apps", "templates"),
            ElementSelector.byTagAndName("app", "never-there"),
            false);
        assertTrue(r.isNoop());
    }

    @Test
    public void removeFragment_noopWhenScopeMissing() throws Exception {
        ChangeSet r = ConfigFragmentEditor.removeFragment(appRoot,
            List.of("profiles", "nonexistent", "apps", "templates"),
            ElementSelector.byTag("app"),
            false);
        assertTrue(r.isNoop());
    }

    @Test
    public void addFragment_dryRunDoesNotTouchDisk() throws Exception {
        Path configPath = ConfigFragmentEditor.resolveConfigPath(appRoot);
        String before = Files.readString(configPath);
        ChangeSet r = ConfigFragmentEditor.addFragment(appRoot,
            List.of("apps", "templates"),
            "<app xmlns=\"http://www.neeveresearch.com/schema/x-ddl\" name=\"preview\"/>",
            true);
        assertFalse(r.isApplied());
        assertEquals(before, Files.readString(configPath));
    }
}
