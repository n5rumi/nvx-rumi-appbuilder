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
import com.github.javaparser.ast.Modifier;
import com.github.javaparser.ast.NodeList;
import com.github.javaparser.ast.body.ClassOrInterfaceDeclaration;
import com.github.javaparser.ast.body.MethodDeclaration;
import com.github.javaparser.ast.body.Parameter;
import com.github.javaparser.ast.expr.MarkerAnnotationExpr;
import com.github.javaparser.ast.expr.Name;
import com.github.javaparser.ast.stmt.BlockStmt;
import com.github.javaparser.ast.type.ClassOrInterfaceType;
import com.github.javaparser.ast.type.VoidType;
import com.github.javaparser.printer.lexicalpreservation.LexicalPreservingPrinter;
import com.neeve.appbuilder.model.ChangeSet;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Optional;

/**
 * AST-level editor for a service's {@code Main.java}. Adds and removes
 * {@code @EventHandler} methods, preserving comments and formatting on
 * unchanged regions of the file via JavaParser's
 * {@link LexicalPreservingPrinter}.
 *
 * <p>Both operations are identity-matched on {@code methodName}:
 *
 * <ul>
 *   <li>{@link #addHandler} is idempotent — if a method with the same
 *       name already exists, the call is a no-op.
 *   <li>{@link #removeHandler} is no-op when the named method is absent.
 * </ul>
 *
 * <p>All mutations accept a {@code dryRun} flag. When {@code dryRun} is
 * true, the computed result is returned as a {@link ChangeSet} without
 * writing to disk.
 */
public final class JavaSourceEditor {
    private JavaSourceEditor() {}

    /**
     * Insert a new {@code @EventHandler} method into the service's
     * {@code Main.java}. The method is appended as the last member of
     * the class.
     *
     * @param body method body Java code (without surrounding braces), or
     *        {@code null} for an empty body with a TODO comment.
     */
    public static ChangeSet addHandler(Path appRoot,
                                       String serviceName,
                                       String methodName,
                                       String messageType,
                                       String body,
                                       boolean dryRun) throws IOException {
        Path mainJava = AppIntrospector.resolveMainJavaFile(appRoot, serviceName);
        if (!Files.exists(mainJava)) {
            throw new IOException("Main.java not found at " + mainJava);
        }

        CompilationUnit cu = parse(mainJava);
        ClassOrInterfaceDeclaration clazz = primaryClass(cu);
        if (clazz == null) {
            throw new IllegalStateException("no class declaration found in " + mainJava);
        }

        // Idempotency: a method with the same name already exists.
        if (clazz.getMethodsByName(methodName).stream().anyMatch(HandlerIntrospector::hasEventHandlerAnnotation)) {
            return ChangeSet.noop("handler '" + methodName + "' already exists on " + clazz.getNameAsString());
        }

        // Import the message type explicitly. The scaffolded Main.java used to
        // import the message and ROE packages by wildcard, which resolved any
        // handler parameter for free — and made a name defined in both models
        // ambiguous, failing on generated code the author never wrote. The
        // templates now import single types, so the editor has to carry its own
        // import. Unresolvable means the type is not in either model, in which
        // case adding a guessed import would be worse than adding none.
        String fqn = resolveMessageFqn(appRoot, serviceName, messageType);
        if (fqn != null) {
            cu.addImport(fqn);
        }

        MethodDeclaration method = buildHandlerMethod(methodName, messageType, body);
        clazz.addMember(method);
        // LexicalPreservingPrinter setup happens in parse() before any
        // modification. Calling setup() again after adding a programmatically-
        // constructed node would fail with "Range not present" because the
        // new node has no source range.

        String rendered = LexicalPreservingPrinter.print(cu);
        ChangeSet.Builder cs = ChangeSet.builder().addModified(mainJava);
        if (dryRun) {
            return cs.applied(false).build();
        }
        Files.write(mainJava, rendered.getBytes(StandardCharsets.UTF_8));
        return cs.applied(true).build();
    }

    /**
     * Replace the body of an existing {@code @EventHandler} method in the
     * service's {@code Main.java}, leaving its signature, annotations and the
     * rest of the file untouched (RUMI-411).
     *
     * <p>Splices the new text between the existing braces in the raw source
     * rather than replacing the AST node. Re-rendering the method would
     * reformat hand-written code around it, and this call exists precisely so
     * that changing behaviour does not force the caller out to a file edit.
     * Read a body with {@code HandlerIntrospector.getHandler(...).getBody()}
     * and hand it straight back here and the file is byte-identical.
     *
     * <p>Idempotent: an unchanged body is a noop rather than a rewrite, so a
     * caller that re-applies its whole model does not dirty every file.
     *
     * @param body method body Java code, without surrounding braces.
     * @throws IllegalArgumentException if {@code body} does not parse. Unlike
     *         {@link #addHandler} — which degrades a bad body to a comment
     *         because there was nothing there to lose — this call is replacing
     *         code that presumably worked, so it fails rather than damaging it.
     */
    public static ChangeSet updateHandler(Path appRoot,
                                          String serviceName,
                                          String methodName,
                                          String body,
                                          boolean dryRun) throws IOException {
        Path mainJava = AppIntrospector.resolveMainJavaFile(appRoot, serviceName);
        if (!Files.exists(mainJava)) {
            throw new IOException("Main.java not found at " + mainJava);
        }
        String newBody = body == null ? "" : body;

        // Validate before touching anything on disk.
        try {
            StaticJavaParser.parseBlock("{" + newBody + "}");
        } catch (Exception e) {
            throw new IllegalArgumentException(
                "handler body does not parse, so '" + methodName + "' was left unchanged: " + e.getMessage(), e);
        }

        String source = new String(Files.readAllBytes(mainJava), StandardCharsets.UTF_8);
        CompilationUnit cu;
        try {
            cu = StaticJavaParser.parse(source);
        } catch (Exception e) {
            throw new IOException("failed to parse " + mainJava, e);
        }
        // Search the whole compilation unit, matching the read path: looking only
        // at the primary class meant a handler on a nested class could be read
        // and then silently fail to update, reported as a no-op.
        Optional<MethodDeclaration> target = HandlerIntrospector.findHandler(cu, methodName);
        if (target.isEmpty()) {
            return ChangeSet.noop("no handler named '" + methodName + "' found in " + mainJava.getFileName());
        }

        BlockStmt block = target.get().getBody().orElse(null);
        if (block == null) {
            throw new IllegalStateException("handler '" + methodName + "' has no body to replace");
        }
        int[] braces = HandlerIntrospector.bodyBraceOffsets(block, source);
        if (braces == null) {
            throw new IllegalStateException(
                "could not locate the body of '" + methodName + "' in " + mainJava
                + " — refusing to edit rather than risk writing at the wrong offset");
        }
        int open = braces[0];
        int close = braces[1];

        if (newBody.equals(source.substring(open + 1, close))) {
            return ChangeSet.noop("handler '" + methodName + "' already has this body");
        }

        String rendered = source.substring(0, open + 1) + newBody + source.substring(close);
        ChangeSet.Builder cs = ChangeSet.builder().addModified(mainJava);
        if (dryRun) {
            return cs.applied(false).build();
        }
        Files.write(mainJava, rendered.getBytes(StandardCharsets.UTF_8));
        return cs.applied(true).build();
    }

    /**
     * Remove the named method from the service's {@code Main.java}. If the
     * method doesn't exist, returns a noop ChangeSet.
     */
    public static ChangeSet removeHandler(Path appRoot,
                                          String serviceName,
                                          String methodName,
                                          boolean dryRun) throws IOException {
        Path mainJava = AppIntrospector.resolveMainJavaFile(appRoot, serviceName);
        if (!Files.exists(mainJava)) {
            throw new IOException("Main.java not found at " + mainJava);
        }

        CompilationUnit cu = parse(mainJava);
        ClassOrInterfaceDeclaration clazz = primaryClass(cu);
        if (clazz == null) {
            throw new IllegalStateException("no class declaration found in " + mainJava);
        }

        // Same whole-unit search as the update path, for the same reason.
        Optional<MethodDeclaration> target = HandlerIntrospector.findHandler(cu, methodName);

        if (target.isEmpty()) {
            return ChangeSet.noop("no handler named '" + methodName + "' found on " + clazz.getNameAsString());
        }

        target.get().remove();

        String rendered = LexicalPreservingPrinter.print(cu);
        ChangeSet.Builder cs = ChangeSet.builder().addModified(mainJava);
        if (dryRun) {
            return cs.applied(false).build();
        }
        Files.write(mainJava, rendered.getBytes(StandardCharsets.UTF_8));
        return cs.applied(true).build();
    }

    // --- internal -----------------------------------------------------

    private static CompilationUnit parse(Path mainJava) throws IOException {
        try {
            String source = new String(Files.readAllBytes(mainJava), StandardCharsets.UTF_8);
            CompilationUnit cu = StaticJavaParser.parse(source);
            LexicalPreservingPrinter.setup(cu);
            return cu;
        } catch (Exception e) {
            throw new IOException("failed to parse " + mainJava, e);
        }
    }

    private static ClassOrInterfaceDeclaration primaryClass(CompilationUnit cu) {
        for (var type : cu.getTypes()) {
            if (type instanceof ClassOrInterfaceDeclaration) {
                return (ClassOrInterfaceDeclaration) type;
            }
        }
        return null;
    }

    /**
     * Fully-qualify a handler's message type by finding which model declares
     * it — the service's own message model, or the app's shared ROE model.
     *
     * <p>The namespace is read from the model's own {@code namespace}
     * attribute rather than assembled from the app's package tokens, so this
     * stays correct for a hand-arranged app whose packages do not follow the
     * scaffolding convention.
     *
     * @return the fully-qualified name, {@code messageType} unchanged if it is
     *         already qualified, or null if no model declares it.
     */
    private static String resolveMessageFqn(Path appRoot, String serviceName, String messageType) {
        if (messageType == null || messageType.trim().isEmpty()) {
            return null;
        }
        String type = messageType.trim();
        if (type.contains(".")) {
            return type; // already qualified; import it as given
        }
        try {
            String ns = namespaceDeclaring(AppIntrospector.resolveMessagesXmlFile(appRoot, serviceName), type);
            if (ns == null) {
                ns = namespaceDeclaring(AppIntrospector.resolveRoeMessagesXmlFile(appRoot), type);
            }
            return ns == null ? null : ns + "." + type;
        } catch (Exception e) {
            return null; // no model to read; leave the import alone
        }
    }

    /** The model's namespace if it declares {@code messageName}, else null. */
    private static String namespaceDeclaring(Path modelFile, String messageName) {
        if (modelFile == null || !Files.exists(modelFile)) {
            return null;
        }
        try {
            org.w3c.dom.Document doc = XmlDomUtils.parseXmlDocument(modelFile);
            for (com.neeve.appbuilder.model.MessageDef m : MessageIntrospector.parseMessages(doc)) {
                if (messageName.equals(m.getName())) {
                    org.w3c.dom.Element root = doc.getDocumentElement();
                    String ns = root == null ? null : root.getAttribute("namespace");
                    return ns == null || ns.trim().isEmpty() ? null : ns.trim();
                }
            }
        } catch (Exception ignored) {
            // unreadable model: treat as "does not declare it"
        }
        return null;
    }

    private static MethodDeclaration buildHandlerMethod(String methodName, String messageType, String body) {
        MethodDeclaration method = new MethodDeclaration();
        method.setName(methodName);
        method.setType(new VoidType());
        method.addModifier(Modifier.Keyword.FINAL);
        method.addModifier(Modifier.Keyword.PUBLIC);
        method.addAnnotation(new MarkerAnnotationExpr(new Name("EventHandler")));

        Parameter p = new Parameter();
        p.setName("message");
        p.setType(new ClassOrInterfaceType(null, messageType));
        p.addModifier(Modifier.Keyword.FINAL);
        NodeList<Parameter> params = new NodeList<>();
        params.add(p);
        method.setParameters(params);

        BlockStmt blockStmt = new BlockStmt();
        if (body != null && !body.isBlank()) {
            // Parse as a block to catch syntax errors early; if that fails, fall back to raw text.
            try {
                BlockStmt parsed = StaticJavaParser.parseBlock("{" + body + "}");
                blockStmt = parsed;
            } catch (Exception e) {
                blockStmt.addStatement("// invalid body provided; left empty for hand-editing: " + e.getMessage());
            }
        } else {
            blockStmt.addOrphanComment(
                new com.github.javaparser.ast.comments.LineComment(" TODO: implement handler"));
        }
        method.setBody(blockStmt);
        return method;
    }
}
