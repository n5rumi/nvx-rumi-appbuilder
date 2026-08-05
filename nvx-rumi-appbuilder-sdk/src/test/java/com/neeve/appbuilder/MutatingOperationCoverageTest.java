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
import io.github.classgraph.ClassGraph;
import io.github.classgraph.ClassInfo;
import io.github.classgraph.MethodInfo;
import io.github.classgraph.ScanResult;
import org.junit.Test;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.Set;
import java.util.TreeSet;

import static org.junit.Assert.*;

/**
 * RUMI-378's structural guard: a mutating operation added later cannot skip
 * {@link MutatingOperationValidityTest} quietly.
 *
 * <p>The expected set is <em>derived</em>, by scanning the SDK for public
 * static methods that return a {@link ChangeSet} — the shape every mutating
 * operation has. It is deliberately not a hand-maintained list, because the
 * MCP suite's hand-maintained {@code EXPECTED_TOOLS} had already gone stale
 * once: it silently failed to pick up the connector, field and operation
 * tools until somebody happened to notice. A list that must be remembered is
 * a list that will eventually be wrong, and its being wrong looks exactly
 * like its being right.
 */
public class MutatingOperationCoverageTest {

    /** Editors whose ChangeSet-returning methods are mutating operations. */
    private static final String PACKAGE = "com.neeve.appbuilder";

    @Test
    public void everyMutatingOperationIsExercisedByTheValiditySuite() throws IOException {
        Set<String> operations = discoverMutatingOperations();
        assertFalse("scan found no mutating operations at all — the discovery "
            + "is broken, not the coverage", operations.isEmpty());

        String suite = readTestSource("MutatingOperationValidityTest.java");

        Set<String> uncovered = new TreeSet<>();
        for (String op : operations) {
            // Method name alone: the suite calls e.g. FieldEditor.renameField.
            String method = op.substring(op.indexOf('#') + 1);
            if (!suite.contains("." + method + "(")) {
                uncovered.add(op);
            }
        }
        assertTrue("these mutating operations have no round-trip validity test.\n"
            + "Add one to MutatingOperationValidityTest — an operation that can leave a\n"
            + "project invalid without any test noticing is exactly what RUMI-378 exists\n"
            + "to prevent:\n  " + String.join("\n  ", uncovered),
            uncovered.isEmpty());
    }

    /**
     * The service-creating operations do not return a ChangeSet, so the scan
     * above cannot see them. They are the highest-traffic operations in the
     * whole builder, so they are pinned explicitly rather than left to a
     * heuristic that happens not to cover them.
     */
    @Test
    public void serviceCreationIsExercisedForEveryServiceType() throws IOException {
        String suite = readTestSource("MutatingOperationValidityTest.java");
        for (String type : new String[] {"addProcessor", "addDriver", "addConnector", "addWebservice"}) {
            assertTrue("service type not exercised by the validity suite: " + type,
                suite.contains(type + "("));
        }
        assertTrue("app scaffolding itself is not validated",
            suite.contains("scaffoldAt("));
    }

    // --- internal -----------------------------------------------------

    private static Set<String> discoverMutatingOperations() {
        Set<String> out = new TreeSet<>();
        try (ScanResult scan = new ClassGraph()
                .enableClassInfo()
                .enableMethodInfo()
                .acceptPackages(PACKAGE)
                .scan()) {
            for (ClassInfo ci : scan.getAllClasses()) {
                // The model package holds the value types themselves — notably
                // ChangeSet, whose static noop() factory returns a ChangeSet
                // without being an operation. Only the editors mutate.
                if (ci.getName().startsWith(PACKAGE + ".model.")
                        || ci.getName().startsWith(PACKAGE + ".test.")
                        || ci.getName().endsWith("Test")) {
                    continue;
                }
                for (MethodInfo mi : ci.getDeclaredMethodInfo()) {
                    if (!mi.isPublic() || !mi.isStatic()) {
                        continue;
                    }
                    String returnType = mi.getTypeSignatureOrTypeDescriptor()
                        .getResultType().toString();
                    if (returnType.equals(ChangeSet.class.getName())) {
                        out.add(ci.getSimpleName() + "#" + mi.getName());
                    }
                }
            }
        }
        return out;
    }

    private static String readTestSource(String fileName) throws IOException {
        Path p = Paths.get(System.getProperty("user.dir"))
            .resolve("src/test/java/com/neeve/appbuilder").resolve(fileName);
        if (!Files.exists(p)) {
            // Surefire can run from the reactor root as well as the module dir.
            p = Paths.get(System.getProperty("user.dir"))
                .resolve("nvx-rumi-appbuilder-sdk/src/test/java/com/neeve/appbuilder")
                .resolve(fileName);
        }
        assertTrue("could not locate " + fileName + " to check coverage against; "
            + "looked relative to user.dir=" + System.getProperty("user.dir"),
            Files.exists(p));
        return Files.readString(p);
    }
}
