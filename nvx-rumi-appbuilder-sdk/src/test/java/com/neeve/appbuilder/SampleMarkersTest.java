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

import org.junit.Test;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertSame;
import static org.junit.Assert.assertTrue;
import static org.junit.Assert.fail;

/** Unit tests for the sample/bare region resolver. */
public class SampleMarkersTest {

    private static String bare(String content) {
        return SampleMarkers.resolve(content, false, "test");
    }

    private static String withSamples(String content) {
        return SampleMarkers.resolve(content, true, "test");
    }

    @Test
    public void aSampleRegionSurvivesInSamplesModeAndVanishesInBareMode() {
        final String template = String.join("\n",
            "keep me",
            "// @sample-begin",
            "demo code",
            "// @sample-end",
            "keep me too");

        assertEquals("keep me\ndemo code\nkeep me too", withSamples(template));
        assertEquals("keep me\nkeep me too", bare(template));
    }

    @Test
    public void aBareRegionIsTheMirrorImage() {
        final String template = String.join("\n",
            "keep me",
            "// @bare-begin",
            "// fill this in",
            "// @bare-end",
            "keep me too");

        assertEquals("keep me\nkeep me too", withSamples(template));
        assertEquals("keep me\n// fill this in\nkeep me too", bare(template));
    }

    @Test
    public void xmlAndShellCommentSyntaxesAreRecognisedToo() {
        assertEquals("<a/>\n<c/>", bare(String.join("\n",
            "<a/>", "<!-- @sample-begin -->", "<b/>", "<!-- @sample-end -->", "<c/>")));
        assertEquals("a\nc", bare(String.join("\n",
            "a", "# @sample-begin", "b", "# @sample-end", "c")));
    }

    @Test
    public void markerLinesAreNeverEmittedInEitherMode() {
        final String template = String.join("\n",
            "// @sample-begin", "x", "// @sample-end",
            "// @bare-begin", "y", "// @bare-end");
        for (String out : new String[] { withSamples(template), bare(template) }) {
            assertFalse(out.contains("@sample"));
            assertFalse(out.contains("@bare"));
        }
    }

    /**
     * The bug this test exists for: collapsing the blank lines left behind by a
     * removed region must not eat the indentation of the line that follows. A
     * "(\n[ \t]*){3,}" style pattern does exactly that, because its final
     * repetition happily consumes the next line's leading whitespace -- which
     * silently de-indents generated Java.
     */
    @Test
    public void collapsingBlankLinesPreservesTheFollowingIndentation() {
        final String template = String.join("\n",
            "    }",
            "",
            "    // @sample-begin",
            "    demo",
            "    // @sample-end",
            "",
            "    @Override");

        assertEquals("    }\n\n    @Override", bare(template));
    }

    @Test
    public void severalBlankLinesCollapseToOne() {
        assertEquals("a\n\nb", bare(String.join("\n",
            "a", "", "// @sample-begin", "", "", "// @sample-end", "", "b")));
    }

    @Test
    public void aSingleBlankLineIsLeftAlone() {
        assertEquals("a\n\nb", bare("a\n\nb"));
    }

    @Test
    public void contentWithoutMarkersIsReturnedUntouched() {
        final String content = "line one\n\n\n\nline two, with deliberate blank lines\n";
        assertSame("an unmarked file must not even be rewritten", content, bare(content));
    }

    @Test
    public void theLineTerminatorAndTrailingNewlineArePreserved() {
        assertEquals("a\r\nb\r\n", bare("a\r\n// @sample-begin\r\nx\r\n// @sample-end\r\nb\r\n"));
        assertEquals("a\nb", bare("a\n// @sample-begin\nx\n// @sample-end\nb"));
    }

    @Test
    public void proseMentioningAMarkerIsNotMistakenForOne() {
        // The token has to be the entire line; this is what lets javadoc and
        // documentation describe the mechanism without triggering it.
        final String template = "/* use // @sample-begin to open a region */\nkeep";
        assertEquals(template, bare(template));
    }

    @Test
    public void anUnterminatedRegionIsRejected() {
        assertRejected("a\n// @sample-begin\nb", "unterminated");
    }

    @Test
    public void aStrayClosingMarkerIsRejected() {
        assertRejected("a\n// @sample-end\nb", "no matching opening marker");
    }

    @Test
    public void nestedRegionsAreRejected() {
        assertRejected("// @sample-begin\n// @bare-begin\nx\n// @bare-end\n// @sample-end", "nested");
    }

    @Test
    public void crossedMarkerKindsAreRejected() {
        assertRejected("// @sample-begin\nx\n// @bare-end", "mismatch");
    }

    private static void assertRejected(String template, String expectedFragment) {
        for (boolean includeSamples : new boolean[] { true, false }) {
            try {
                SampleMarkers.resolve(template, includeSamples, "offender.java");
                fail("expected rejection (includeSamples=" + includeSamples + ")");
            }
            catch (IllegalStateException e) {
                assertTrue("message should explain the problem, was: " + e.getMessage(),
                           e.getMessage().contains(expectedFragment));
                assertTrue("message should name the file, was: " + e.getMessage(),
                           e.getMessage().contains("offender.java"));
            }
        }
    }
}
