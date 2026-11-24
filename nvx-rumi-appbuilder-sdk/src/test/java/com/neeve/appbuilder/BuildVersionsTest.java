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

import org.junit.Test;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;

import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

/**
 * RUMI-443 — the default management version is one that exists.
 *
 * <p>These assert the SHAPE of the value and that both defaulting paths use it.
 * That the version actually RESOLVES is proved by
 * {@code ci/verify-generated-app.sh}, which now packages a scaffold with the
 * parameter omitted — a unit test cannot assert that without making the build
 * depend on the network.
 */
public class BuildVersionsTest {

    @Test
    public void theDefaultComesFromTheFilteredResource() {
        final String v = BuildVersions.managementVersion();
        assertFalse("unsubstituted Maven placeholder: " + v, v.contains("${"));
        assertTrue("a Management version looks like 2.0.x, got: " + v,
                   v.matches("\\d+\\.\\d+\\.\\d+"));
    }

    @Test
    public void theDefaultIsNotTheUnpublishedLiteralItReplaced() {
        // Pinned deliberately. This is not a typo-guard: 2.0.0 is THE defect.
        // com.neeve:nvx-rumi-xar-maven-plugin:2.0.0 has never been published,
        // so it is the one value that must never come back.
        assertFalse("2.0.0 was never published -- that is RUMI-443",
                    "2.0.0".equals(BuildVersions.managementVersion()));
    }

    @Test
    public void theRumiVersionIsCarriedTooAndIsNotAPlaceholder() {
        final String v = BuildVersions.rumiVersion();
        assertFalse("unsubstituted placeholder: " + v, v.contains("${"));
        assertTrue("a Rumi version looks like 4.0.x, got: " + v, v.matches("\\d+\\.\\d+\\.\\d+"));
    }

    /**
     * The fixture carried its own copy of the bad literal, which is why one fix
     * in the REST layer would not have been enough: the release gate scaffolds
     * through THIS path.
     */
    @Test
    public void theTestFixtureUsesTheSameDefault() throws IOException {
        final Path tmp = Files.createTempDirectory("rumi-443-");
        try {
            final Path app = TestAppFactory.newApp("demo")
                .packageName("com.example.demo")
                .rumiVersion(BuildVersions.rumiVersion())
                .rumiBindingsVersion(BuildVersions.rumiVersion())
                .scaffoldAt(tmp);
            final String pom = new String(Files.readAllBytes(app.resolve("pom.xml")), "UTF-8");
            assertTrue("the scaffolded POM must pin the build-time management version, not a literal",
                       pom.contains("<nvx.rumi.management.version>"
                                    + BuildVersions.managementVersion()
                                    + "</nvx.rumi.management.version>"));
            assertFalse("the scaffolded POM still pins the unpublished 2.0.0",
                        pom.contains("<nvx.rumi.management.version>2.0.0<"));
        }
        finally {
            TestAppFactory.deleteRecursive(tmp);
        }
    }

    @Test
    public void anExplicitVersionStillWins() throws IOException {
        final Path tmp = Files.createTempDirectory("rumi-443-");
        try {
            final Path app = TestAppFactory.newApp("demo")
                .packageName("com.example.demo")
                .rumiVersion(BuildVersions.rumiVersion())
                .rumiBindingsVersion(BuildVersions.rumiVersion())
                .rumiMgmtVersion("2.0.94")
                .scaffoldAt(tmp);
            final String pom = new String(Files.readAllBytes(app.resolve("pom.xml")), "UTF-8");
            assertTrue("an explicitly named version must be honoured",
                       pom.contains("<nvx.rumi.management.version>2.0.94</nvx.rumi.management.version>"));
        }
        finally {
            TestAppFactory.deleteRecursive(tmp);
        }
    }

}
