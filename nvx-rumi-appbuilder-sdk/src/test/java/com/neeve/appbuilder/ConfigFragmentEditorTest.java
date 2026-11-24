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

    // ---- RUMI-442: a MODIFIED fragment replaces, and says so --------------

    private static final String NS = "http://www.neeveresearch.com/schema/x-ddl";

    private String configXml() throws Exception {
        return Files.readString(ConfigFragmentEditor.resolveConfigPath(appRoot));
    }

    private static int countOccurrences(String haystack, String needle) {
        int c = 0, i = 0;
        while ((i = haystack.indexOf(needle, i)) >= 0) { c++; i += needle.length(); }
        return c;
    }

    /**
     * The defect: a fragment whose name already exists was APPENDED, so
     * config.xml ended up with two competing definitions, `applied: true` came
     * back, and it surfaced at boot as whichever copy the loader picked.
     */
    @Test
    public void addFragment_replacesAModifiedFragmentWithTheSameName() throws Exception {
        ConfigFragmentEditor.addFragment(appRoot, List.of("apps", "templates"),
            "<app xmlns=\"" + NS + "\" name=\"x-template\"/>", false);
        ChangeSet second = ConfigFragmentEditor.addFragment(appRoot, List.of("apps", "templates"),
            "<app xmlns=\"" + NS + "\" name=\"x-template\">"
            + "<inboundEventMultiplexing enabled=\"true\"/></app>", false);

        assertTrue("a changed fragment is a real change, not a no-op", second.isApplied());
        assertFalse(second.isNoop());
        assertEquals("exactly one <app name=\"x-template\"> must remain",
                     1, countOccurrences(configXml(), "name=\"x-template\""));
        assertTrue("the replacement must actually carry the new content",
                   configXml().contains("inboundEventMultiplexing"));
    }

    /** AC2: the caller is told which of the three outcomes happened. */
    @Test
    public void addFragment_saysWhetherItAddedOrReplaced() throws Exception {
        ChangeSet added = ConfigFragmentEditor.addFragment(appRoot, List.of("apps", "templates"),
            "<app xmlns=\"" + NS + "\" name=\"r-template\"/>", false);
        assertNotNull("an applied change must carry a reason (RUMI-442 AC2)", added.getReason());
        assertTrue("the reason must say it ADDED: " + added.getReason(),
                   added.getReason().startsWith("added"));

        ChangeSet replaced = ConfigFragmentEditor.addFragment(appRoot, List.of("apps", "templates"),
            "<app xmlns=\"" + NS + "\" name=\"r-template\"><inboundEventMultiplexing enabled=\"true\"/></app>",
            false);
        assertNotNull(replaced.getReason());
        assertTrue("replacing is destructive, so it must say so: " + replaced.getReason(),
                   replaced.getReason().startsWith("replaced"));
        assertTrue("and name what it displaced: " + replaced.getReason(),
                   replaced.getReason().contains("r-template"));
    }

    /**
     * Position is preserved. Order is meaningful in X-DDL, and replacing by
     * remove-then-append would silently move an app to the end of its scope --
     * a second surprise inside the fix for the first.
     */
    @Test
    public void addFragment_replacesInPlaceRatherThanMovingToTheEnd() throws Exception {
        for (String nm : List.of("first", "middle", "last")) {
            ConfigFragmentEditor.addFragment(appRoot, List.of("apps", "templates"),
                "<app xmlns=\"" + NS + "\" name=\"" + nm + "\"/>", false);
        }
        ConfigFragmentEditor.addFragment(appRoot, List.of("apps", "templates"),
            "<app xmlns=\"" + NS + "\" name=\"middle\"><inboundEventMultiplexing enabled=\"true\"/></app>",
            false);

        String xml = configXml();
        assertTrue("middle must stay between first and last",
                   xml.indexOf("\"first\"") < xml.indexOf("\"middle\"")
                   && xml.indexOf("\"middle\"") < xml.indexOf("\"last\""));
    }

    /**
     * Identity is tag PLUS name. Two differently-named apps must coexist.
     *
     * <p>This is the test that actually pins it. A mutation making
     * {@code sameIdentity} match on tag alone slipped past the unnamed-siblings
     * test below, because that one replaces a whole {@code <app>} whose
     * replacement happens to carry the same children -- so it passed while the
     * identity rule was broken. Two distinct names cannot pass that way.
     */
    @Test
    public void addFragment_doesNotReplaceADifferentlyNamedSibling() throws Exception {
        ConfigFragmentEditor.addFragment(appRoot, List.of("apps", "templates"),
            "<app xmlns=\"" + NS + "\" name=\"alpha\"/>", false);
        ChangeSet second = ConfigFragmentEditor.addFragment(appRoot, List.of("apps", "templates"),
            "<app xmlns=\"" + NS + "\" name=\"beta\"/>", false);

        assertTrue(second.getReason().startsWith("added"));
        String xml = configXml();
        assertTrue("alpha must survive adding beta", xml.contains("name=\"alpha\""));
        assertTrue("beta must be added", xml.contains("name=\"beta\""));
    }

    /**
     * An element with no name is not an identity. Several unnamed siblings of
     * one tag are legitimate X-DDL, so matching on tag alone would make the
     * second silently replace the first.
     */
    @Test
    public void addFragment_doesNotReplaceUnnamedSiblingsOfTheSameTag() throws Exception {
        ConfigFragmentEditor.addFragment(appRoot, List.of("apps", "templates"),
            "<app xmlns=\"" + NS + "\" name=\"holder\"><factory id=\"1\"/></app>", false);
        ChangeSet second = ConfigFragmentEditor.addFragment(appRoot, List.of("apps", "templates"),
            "<app xmlns=\"" + NS + "\" name=\"holder\"><factory id=\"1\"/><factory id=\"2\"/></app>",
            false);
        assertTrue(second.isApplied());
        assertEquals("both unnamed <factory> children must survive the replacement",
                     2, countOccurrences(configXml(), "<factory"));
    }

    /** A dry run must decide the same way without writing. */
    @Test
    public void addFragment_dryRunReportsTheReplacementWithoutWriting() throws Exception {
        ConfigFragmentEditor.addFragment(appRoot, List.of("apps", "templates"),
            "<app xmlns=\"" + NS + "\" name=\"d-template\"/>", false);
        String before = configXml();
        ChangeSet dry = ConfigFragmentEditor.addFragment(appRoot, List.of("apps", "templates"),
            "<app xmlns=\"" + NS + "\" name=\"d-template\"><inboundEventMultiplexing enabled=\"true\"/></app>",
            true);
        assertFalse("a dry run must not report itself as applied", dry.isApplied());
        assertTrue("a dry run must still say what it WOULD do: " + dry.getReason(),
                   dry.getReason() != null && dry.getReason().startsWith("replaced"));
        assertEquals("a dry run must not touch the file", before, configXml());
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
