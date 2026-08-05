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

import java.util.ArrayList;
import java.util.List;

/**
 * Resolves the sample/bare regions a scaffold template declares inline.
 *
 * <p>A scaffold serves two audiences that want opposite things. A human reading
 * a generated app for the first time wants worked example code to imitate; an
 * AI agent building a real application wants none of it, because every sample
 * message and handler is something it has to read and then delete before it can
 * start. Rather than keep two template trees -- which would drift, the way any
 * hand-maintained copy does -- a single template marks its demo regions in
 * place and this class removes whichever side the caller did not ask for.
 *
 * <p>Two symmetric region kinds, each on its own line, in a comment syntax the
 * host file already understands:
 *
 * <pre>
 *   // @sample-begin ... // @sample-end          Java: dropped when includeSamples is false
 *   &lt;!-- @sample-begin --&gt; ... &lt;!-- @sample-end --&gt;   XML: likewise
 *   // @bare-begin ... // @bare-end              dropped when includeSamples is true
 * </pre>
 *
 * A {@code @bare} region is how a template supplies a <em>replacement</em>
 * rather than a deletion -- a stubbed comment where the sample had a working
 * method, say. The marker lines themselves are always removed, in both modes,
 * so no generated file ever ships one.
 *
 * <p>Regions do not nest and must balance. An unbalanced or mismatched marker
 * throws rather than silently emitting half a file: a template is compiled by
 * nobody until a user builds the app it generated, so the error has to surface
 * here or it surfaces in their build. {@code SampleMarkerBalanceTest} walks
 * every packaged template so a bad marker fails our build, not theirs.
 */
final class SampleMarkers {

    static final String SAMPLE_BEGIN = "@sample-begin";
    static final String SAMPLE_END = "@sample-end";
    static final String BARE_BEGIN = "@bare-begin";
    static final String BARE_END = "@bare-end";

    private enum Marker { SAMPLE_OPEN, SAMPLE_CLOSE, BARE_OPEN, BARE_CLOSE }

    private SampleMarkers() {}

    /**
     * Return true if the content mentions any marker token at all. Lets callers
     * skip untouched files cheaply.
     *
     * <p>Deliberately tests the closing tokens as well as the opening ones. A
     * file holding only a stray {@code @sample-end} declares no region, but it is
     * still malformed, and if this returned false for it {@link #resolve} would
     * short-circuit and copy that marker line straight into a generated app.
     */
    static boolean hasMarkers(String content) {
        return content.indexOf(SAMPLE_BEGIN) >= 0 || content.indexOf(BARE_BEGIN) >= 0
            || content.indexOf(SAMPLE_END) >= 0 || content.indexOf(BARE_END) >= 0;
    }

    /**
     * Strip the marker lines and whichever regions the mode discards.
     *
     * @param content       the raw template content
     * @param includeSamples true to keep {@code @sample} regions and drop
     *                       {@code @bare} ones, false for the reverse
     * @param origin         a path or name used in error messages so an
     *                       unbalanced marker names the offending template
     */
    static String resolve(String content, boolean includeSamples, String origin) {
        if (!hasMarkers(content)) {
            return content;
        }

        // Preserve the original line terminator and whether the file ended with one.
        final String newline = content.indexOf("\r\n") >= 0 ? "\r\n" : "\n";
        final boolean trailingNewline = content.endsWith("\n");

        final String[] lines = content.split("\r\n|\n", -1);
        final List<String> kept = new ArrayList<>(lines.length);

        Marker open = null;
        int openLine = -1;
        boolean dropping = false;

        for (int i = 0; i < lines.length; i++) {
            final Marker marker = markerOf(lines[i]);
            if (marker == null) {
                if (!dropping) {
                    kept.add(lines[i]);
                }
                continue;
            }

            if (marker == Marker.SAMPLE_OPEN || marker == Marker.BARE_OPEN) {
                if (open != null) {
                    throw new IllegalStateException(origin + ":" + (i + 1)
                        + ": nested sample marker; the region opened at line " + openLine + " is still open");
                }
                open = marker;
                openLine = i + 1;
                dropping = marker == Marker.SAMPLE_OPEN ? !includeSamples : includeSamples;
            }
            else {
                final Marker expected = marker == Marker.SAMPLE_CLOSE ? Marker.SAMPLE_OPEN : Marker.BARE_OPEN;
                if (open == null) {
                    throw new IllegalStateException(origin + ":" + (i + 1)
                        + ": closing marker with no matching opening marker");
                }
                if (open != expected) {
                    throw new IllegalStateException(origin + ":" + (i + 1)
                        + ": marker mismatch; the region opened at line " + openLine + " closes with a different kind");
                }
                open = null;
                dropping = false;
            }
            // A marker line is never emitted, in either mode.
        }

        if (open != null) {
            throw new IllegalStateException(origin + ": unterminated sample region opened at line " + openLine);
        }

        String result = String.join(newline, kept);
        // Removing a region can leave the blank lines that flanked it adjacent.
        // Collapse a run of blank lines back to one so the output reads as if it
        // had been written by hand. Note the inner group matches only lines that
        // are entirely blank: a naive "\n[ \t]*" repetition would swallow the
        // indentation of the first line that follows the run.
        result = result.replaceAll(newline + "(?:[ \t]*" + newline + "){2,}", newline + newline);
        if (trailingNewline && !result.endsWith(newline)) {
            result = result + newline;
        }
        return result;
    }

    private static Marker markerOf(String line) {
        final String trimmed = line.trim();
        // Cheap reject first: the vast majority of lines contain no '@'.
        if (trimmed.indexOf('@') < 0) {
            return null;
        }
        if (isMarker(trimmed, SAMPLE_BEGIN)) return Marker.SAMPLE_OPEN;
        if (isMarker(trimmed, SAMPLE_END)) return Marker.SAMPLE_CLOSE;
        if (isMarker(trimmed, BARE_BEGIN)) return Marker.BARE_OPEN;
        if (isMarker(trimmed, BARE_END)) return Marker.BARE_CLOSE;
        return null;
    }

    /**
     * A marker is the whole line, in one of the comment syntaxes below. Matching
     * the entire line rather than searching for the token means prose that
     * merely mentions {@code @sample-begin} -- this javadoc, for one -- is not
     * mistaken for a marker.
     */
    private static boolean isMarker(String trimmed, String token) {
        return trimmed.equals("// " + token)
            || trimmed.equals("//" + token)
            || trimmed.equals("<!-- " + token + " -->")
            || trimmed.equals("<!--" + token + "-->")
            || trimmed.equals("# " + token)
            || trimmed.equals("#" + token);
    }
}
