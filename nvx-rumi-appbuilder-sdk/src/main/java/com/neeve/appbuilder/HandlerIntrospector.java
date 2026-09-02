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

import com.github.javaparser.StaticJavaParser;
import com.github.javaparser.ast.CompilationUnit;
import com.github.javaparser.ast.body.MethodDeclaration;
import com.github.javaparser.ast.expr.AnnotationExpr;
import com.github.javaparser.ast.body.Parameter;
import com.github.javaparser.ast.stmt.BlockStmt;
import com.github.javaparser.Position;
import com.neeve.appbuilder.model.HandlerDef;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;

/**
 * Read-only introspection over a service's {@code Main.java}. Returns the
 * {@code @EventHandler}-annotated methods declared on the service class.
 *
 * <p>An {@code @EventHandler} method is expected to take exactly one
 * parameter — the message type it handles. Methods with a matching
 * annotation but no parameters or multiple parameters are included in
 * the result with {@code messageType = null}; callers can filter if they
 * only want canonical handlers.
 *
 * <p>Annotation matching is by simple name (unqualified). Both
 * {@code @EventHandler} and {@code @EventHandler(...)} are recognized.
 * Fully-qualified annotation references (e.g. {@code @com.foo.EventHandler})
 * are matched too.
 */
public final class HandlerIntrospector {
    private static final String EVENT_HANDLER = "EventHandler";

    private HandlerIntrospector() {}

    /**
     * Return every handler declaration in the service's {@code Main.java},
     * in source order.
     */
    public static List<HandlerDef> listHandlers(Path appRoot, String serviceName) throws IOException {
        return listHandlers(appRoot, serviceName, false);
    }

    /**
     * As {@link #listHandlers(Path, String)}, optionally carrying each
     * handler's body.
     *
     * <p>Bodies are <b>off by default</b> and that is deliberate. A list call
     * answers "what handlers are there?", and returning every body turns a
     * cheap signature listing into a full dump of the service's logic — the
     * exact shape of waste this surface exists to remove. Ask for one handler's
     * body with {@link #getHandler}, or opt in here when you genuinely want
     * them all.
     */
    public static List<HandlerDef> listHandlers(Path appRoot, String serviceName, boolean includeBodies)
            throws IOException {
        Path mainJava = AppIntrospector.resolveMainJavaFile(appRoot, serviceName);
        if (!Files.exists(mainJava)) return Collections.emptyList();
        String source;
        CompilationUnit cu;
        try {
            source = new String(Files.readAllBytes(mainJava), java.nio.charset.StandardCharsets.UTF_8);
            cu = StaticJavaParser.parse(source);
        } catch (Exception e) {
            throw new IOException("failed to parse " + mainJava, e);
        }
        return parseHandlers(cu, includeBodies ? source : null);
    }

    /**
     * Return the named handler, or {@code null} if no {@code @EventHandler}
     * method with that name exists in the service's {@code Main.java}.
     */
    public static HandlerDef getHandler(Path appRoot, String serviceName, String methodName) throws IOException {
        // Bodies on: fetching one named handler is the case where the body is
        // the point (RUMI-411), unlike the bulk listing.
        for (HandlerDef h : listHandlers(appRoot, serviceName, true)) {
            if (methodName.equals(h.getMethodName())) return h;
        }
        return null;
    }

    static List<HandlerDef> parseHandlers(CompilationUnit cu) {
        return parseHandlers(cu, null);
    }

    /**
     * @param source the original file text, or {@code null} to skip body
     *        extraction. Bodies are sliced out of this rather than printed
     *        from the AST so they survive a read/write round trip unchanged.
     */
    static List<HandlerDef> parseHandlers(CompilationUnit cu, String source) {
        List<HandlerDef> out = new ArrayList<>();
        cu.findAll(MethodDeclaration.class).forEach(method -> {
            if (!hasEventHandlerAnnotation(method)) return;
            String name = method.getNameAsString();
            String messageType = extractMessageType(method);
            String returnType = method.getType().asString();
            int startLine = method.getBegin().map(p -> p.line).orElse(-1);
            out.add(new HandlerDef(name, messageType, returnType, startLine,
                                   source == null ? null : extractBody(method, source)));
        });
        return out;
    }

    /**
     * The verbatim text between a handler's braces, or {@code null} for an
     * abstract/interface method that has no body at all.
     */
    static String extractBody(MethodDeclaration method, String source) {
        BlockStmt block = method.getBody().orElse(null);
        if (block == null) return null;
        int open = offsetOf(source, block.getBegin().orElse(null));
        int close = offsetOf(source, block.getEnd().orElse(null));
        if (open < 0 || close < 0 || close <= open) return null;
        // begin sits on '{' and end on '}', so the inner text excludes both.
        return source.substring(open + 1, close);
    }

    /**
     * Character offset of a JavaParser {@link Position} (1-based line and
     * column) in {@code source}, or -1 if it cannot be resolved.
     *
     * <p>Counts line breaks directly rather than splitting, so it is correct
     * for {@code \n} and {@code \r\n} alike — a split-and-rejoin would
     * silently shift every offset on a CRLF file by one per preceding line.
     */
    static int offsetOf(String source, Position pos) {
        if (pos == null) return -1;
        int line = 1;
        int i = 0;
        while (line < pos.line && i < source.length()) {
            if (source.charAt(i) == '\n') line++;
            i++;
        }
        if (line != pos.line) return -1;
        int offset = i + (pos.column - 1);
        return offset <= source.length() ? offset : -1;
    }

    static boolean hasEventHandlerAnnotation(MethodDeclaration method) {
        for (AnnotationExpr a : method.getAnnotations()) {
            String name = a.getNameAsString();
            // Handles "EventHandler" and "com.neeve.aep.annotations.EventHandler" alike.
            if (EVENT_HANDLER.equals(name) || name.endsWith("." + EVENT_HANDLER)) {
                return true;
            }
        }
        return false;
    }

    /**
     * Extract the message type from an {@code @EventHandler} method: the
     * sole parameter's type. Returns {@code null} if the method has zero or
     * multiple parameters (atypical but handled rather than thrown).
     */
    static String extractMessageType(MethodDeclaration method) {
        if (method.getParameters().size() != 1) return null;
        Parameter p = method.getParameters().get(0);
        return p.getType().asString();
    }
}
