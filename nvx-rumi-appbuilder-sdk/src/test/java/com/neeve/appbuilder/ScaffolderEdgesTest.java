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

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertTrue;
import static org.junit.Assert.fail;

/**
 * RUMI-431 -- the two scaffolder edges that are SDK logic rather than template
 * content (I-01 and I-02).
 *
 * <p>These live here and not only in {@code ci/verify-generated-app.sh} because
 * the gate could not prove either one. For I-01 the gate goes through
 * {@link TestAppFactory#scaffoldAt}, which used to {@code createDirectories} the
 * parent itself, so a gate check for "create_app makes its own directory" passed
 * with the fix torn out -- it was testing the fixture. For I-02 there is nothing
 * to run: the derivation is a string, and a gate that builds an app proves only
 * that the name it got was legal, never that it was the name asked for.
 */
public class ScaffolderEdgesTest {

    private Path tmp;

    @Before
    public void setUp() throws IOException {
        tmp = Files.createTempDirectory("rumi-431-");
    }

    @After
    public void tearDown() throws IOException {
        TestAppFactory.deleteRecursive(tmp);
    }

    private ApplicationBuilder.AppParams params(String appDir, String artifactId) {
        return new ApplicationBuilder.AppParams(
            "Paywhere Prospect Portal", appDir, "com.example.portal", "com.example",
            "paywhere", "4.0.660", "4.0.660", "2.0.92",
            null, null, null, true, artifactId);
    }

    // ---- I-01: create the target directory, do not refuse ----------------

    @Test
    public void createsATargetDirectoryThatDoesNotExistYet() {
        // Several levels deep on purpose: a caller who has to `mkdir -p` anyway
        // has gained nothing from a scaffolder that only makes the last level.
        Path fresh = tmp.resolve("not").resolve("there").resolve("yet");
        params(fresh.toString(), null);
        assertTrue("AppParams must create its target directory, not reject it",
                   Files.isDirectory(fresh));
    }

    @Test
    public void stillRefusesATargetThatIsAnExistingFile() throws IOException {
        // The guard that must NOT be lost in making the above work. A regular
        // file at the target is a caller mistake, and silently scaffolding
        // around it would produce an app in a place nobody can explain.
        Path file = tmp.resolve("i-am-a-file");
        Files.write(file, "x".getBytes("UTF-8"));
        try {
            params(file.toString(), null);
            fail("a regular file at the target must be refused, not scaffolded into");
        }
        catch (IllegalArgumentException expected) {
            assertTrue("the message must say what is actually wrong, got: " + expected.getMessage(),
                       expected.getMessage().contains("not a directory"));
        }
    }

    // ---- I-02: an explicit artifact id, and a derivation that stutters ----

    @Test
    public void derivedArtifactIdStuttersWhichIsWhyTheOverrideExists() {
        // Pinning the BAD behaviour deliberately: this is the default, it is what
        // the reporting session hit, and if someone changes the derivation this test is
        // where they find out it was load-bearing for the override's rationale.
        ApplicationBuilder.AppParams p = params(tmp.toString(), null);
        assertEquals("paywhere-paywhereprospectportal",
                     p.getTokenMap().get(TokenUtils.toToken("ParentArtifactId")));
    }

    @Test
    public void explicitArtifactIdIsUsedForEveryDerivedModuleName() {
        ApplicationBuilder.AppParams p = params(tmp.toString(), "paywhere-portal");
        assertEquals("paywhere-portal",
                     p.getTokenMap().get(TokenUtils.toToken("ParentArtifactId")));
        // The ROE and system ids hang off the same base. Asserting all three
        // because setting only the parent is the shape of a half-applied fix:
        // the app would build and its modules would still stutter.
        assertEquals("paywhere-portal-roe",
                     p.getTokenMap().get(TokenUtils.toToken("RoeArtifactId")));
        assertEquals("paywhere-portal-system",
                     p.getTokenMap().get(TokenUtils.toToken("SystemArtifactId")));
        assertEquals("paywhere-portal", p.getArtifactId());
    }

    @Test
    public void blankArtifactIdFallsBackToTheDerivation() {
        // Not the same as null: a caller threading an optional field through
        // sends "" far more often than it sends null, and treating it as an
        // explicit id would scaffold modules named "-roe" and "-system".
        for (String blank : new String[] { "", "   " }) {
            ApplicationBuilder.AppParams p = params(tmp.toString(), blank);
            assertEquals("blank id " + "'" + blank + "'" + " must derive, not be used",
                         "paywhere-paywhereprospectportal",
                         p.getTokenMap().get(TokenUtils.toToken("ParentArtifactId")));
        }
    }

    @Test
    public void artifactIdIsTrimmed() {
        ApplicationBuilder.AppParams p = params(tmp.toString(), "  paywhere-portal  ");
        assertEquals("paywhere-portal",
                     p.getTokenMap().get(TokenUtils.toToken("ParentArtifactId")));
    }

    @Test
    public void refusesAnArtifactIdMavenCannotRead() {
        // Accept-and-break is the trap this repo keeps paying for (RUMI-424).
        // A space or a slash produces a POM Maven refuses, and the caller meets
        // it as an opaque build failure several calls later.
        for (String bad : new String[] { "paywhere portal", "paywhere/portal",
                                         "paywhere:portal", "pay$where" }) {
            try {
                params(tmp.toString(), bad);
                fail("artifact id '" + bad + "' must be refused, not written into the POM");
            }
            catch (IllegalArgumentException expected) {
                assertTrue("the message must name the offending value, got: "
                               + expected.getMessage(),
                           expected.getMessage().contains(bad));
            }
        }
    }
}
