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

import io.github.classgraph.ClassGraph;
import io.github.classgraph.Resource;
import io.github.classgraph.ScanResult;

import org.junit.Test;

import java.io.IOException;
import java.util.ArrayList;
import java.util.List;

import static org.junit.Assert.assertTrue;
import static org.junit.Assert.fail;

/**
 * Structural guards over the sample markers embedded in the templates.
 *
 * <p>Templates are the one thing in this repository our own build never
 * compiles -- they are compiled by the user, in the app we generated for them.
 * A malformed marker would therefore surface not here but in somebody else's
 * {@code mvn package}, which is exactly the failure mode PROJECT.md records for
 * every other template defect we have shipped. So the markers get checked the
 * way {@code MutatingOperationCoverageTest} checks operation coverage: by
 * walking the real packaged resources rather than a list somebody has to
 * remember to update.
 */
public class SampleMarkerBalanceTest {

    private static final String TEMPLATE_ROOT = "templates";

    /**
     * Every template resolves cleanly in both modes. {@link SampleMarkers#resolve}
     * throws on an unbalanced, nested or mismatched marker, so simply running it
     * over everything is the assertion.
     */
    @Test
    public void everyTemplateHasBalancedMarkers() throws IOException {
        final List<String> failures = new ArrayList<>();
        forEachTemplate((path, content) -> {
            for (boolean includeSamples : new boolean[] { true, false }) {
                try {
                    SampleMarkers.resolve(content, includeSamples, path);
                }
                catch (RuntimeException e) {
                    failures.add(e.getMessage());
                }
            }
        });
        if (!failures.isEmpty()) {
            fail("unbalanced sample markers:\n  " + String.join("\n  ", failures));
        }
    }

    /**
     * Markers are only honoured on the two trees {@code TemplateProcessor.applyTemplate}
     * materializes -- the app scaffold and the service scaffolds.
     *
     * <p>The other trees are rendered by {@code ConfigInjector},
     * {@code ScriptInjector} and {@code ConnectorEditor}, which have no notion of
     * the mode. Worse, {@code ServiceRemover} re-renders script snippets to work
     * out what to delete: a marker there would let a service be scaffolded in one
     * mode and un-scaffolded as if it were the other, leaving debris behind. This
     * test is what stops someone discovering that the hard way.
     */
    @Test
    public void markersAppearOnlyWhereTheyAreHonoured() throws IOException {
        final List<String> offenders = new ArrayList<>();
        forEachTemplate((path, content) -> {
            if (!SampleMarkers.hasMarkers(content)) {
                return;
            }
            // templates/<buildTool>/<tree>/...
            final String[] segments = path.split("/");
            final String tree = segments.length > 2 ? segments[2] : "";
            if (!tree.equals("app") && !tree.equals("service")) {
                offenders.add(path);
            }
        });
        if (!offenders.isEmpty()) {
            fail("sample markers are only honoured under the app/ and service/ template trees, "
                + "but were found in:\n  " + String.join("\n  ", offenders));
        }
    }

    /** Neither mode may leave a marker line behind in a generated file. */
    @Test
    public void resolvedOutputNeverRetainsAMarker() throws IOException {
        final List<String> offenders = new ArrayList<>();
        forEachTemplate((path, content) -> {
            for (boolean includeSamples : new boolean[] { true, false }) {
                final String resolved = SampleMarkers.resolve(content, includeSamples, path);
                if (resolved.contains(SampleMarkers.SAMPLE_BEGIN)
                    || resolved.contains(SampleMarkers.SAMPLE_END)
                    || resolved.contains(SampleMarkers.BARE_BEGIN)
                    || resolved.contains(SampleMarkers.BARE_END)) {
                    offenders.add(path + " (includeSamples=" + includeSamples + ")");
                }
            }
        });
        if (!offenders.isEmpty()) {
            fail("marker lines survived resolution in:\n  " + String.join("\n  ", offenders));
        }
    }

    /**
     * A canary: if this stops finding markers, the templates have been rewritten
     * and the tests above have quietly become vacuous.
     */
    @Test
    public void theTemplatesActuallyDeclareSampleRegions() throws IOException {
        final List<String> marked = new ArrayList<>();
        forEachTemplate((path, content) -> {
            if (SampleMarkers.hasMarkers(content)) {
                marked.add(path);
            }
        });
        assertTrue("expected several templates to declare sample regions, found " + marked,
                   marked.size() >= 5);
    }

    private interface TemplateVisitor {
        void visit(String path, String content);
    }

    private static void forEachTemplate(TemplateVisitor visitor) throws IOException {
        try (ScanResult scan = new ClassGraph().acceptPaths(TEMPLATE_ROOT).scan()) {
            for (Resource resource : scan.getAllResources()) {
                visitor.visit(resource.getPath(), resource.getContentAsString());
            }
        }
    }
}
