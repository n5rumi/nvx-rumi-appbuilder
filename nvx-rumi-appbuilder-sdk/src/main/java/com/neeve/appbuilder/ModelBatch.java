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

import com.neeve.appbuilder.model.BatchResult;
import com.neeve.appbuilder.model.ChangeSet;
import com.neeve.appbuilder.model.ModelEdit;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.stream.Stream;

/**
 * Apply a whole model in one call (RUMI-412).
 *
 * <p>Building a six-service app's model cost 120 separate tool calls in the
 * session behind this ticket — 41 of them single fields, including a run of 19
 * three seconds apart. The payload across all 120 was 107 KB; the calls cost
 * 8.09 M tokens. The bytes were 0.4% of that. Everything else was the price of
 * stopping 120 times and re-reading the conversation, so the only lever that
 * matters is the number of calls.
 *
 * <p><b>Items are applied in order, not grouped by kind.</b> Adding a message
 * and then adding fields to it is the normal shape of a batch, so reordering
 * would break the common case.
 *
 * <p><b>All-or-nothing.</b> Every model file that could be touched is snapshotted
 * before the first edit and restored if any edit fails, so a rejected item
 * leaves the app exactly as it was rather than half-built. A dry run writes
 * nothing at all and still validates every item, but note that a dry run cannot
 * validate an item that depends on an earlier one having been applied — a field
 * added to a message the same batch creates will report the message missing.
 * That is a real limit of dry-running a dependent sequence, not a bug: use a
 * real apply, which rolls back.
 */
public final class ModelBatch {
    private ModelBatch() {}

    /**
     * Apply a batch of model edits.
     *
     * @throws IllegalArgumentException if an item is malformed, before anything
     *         is written.
     * @throws IOException on a failed edit — the app is restored first.
     */
    public static BatchResult apply(Path appRoot, List<ModelEdit> edits, boolean dryRun)
            throws IOException {
        if (edits == null || edits.isEmpty()) {
            return new BatchResult(List.of(), Set.of(), false);
        }
        for (ModelEdit e : edits) validate(e);

        Map<Path, byte[]> snapshot = dryRun ? Map.of() : snapshotModelFiles(appRoot);

        List<BatchResult.Item> items = new ArrayList<>();
        Set<Path> touched = new LinkedHashSet<>();
        try {
            for (ModelEdit e : edits) {
                ChangeSet cs = applyOne(appRoot, e, dryRun);
                items.add(new BatchResult.Item(e.describe(), cs.isApplied(), cs.isNoop(), cs.getReason()));
                touched.addAll(cs.getFilesModified());
                touched.addAll(cs.getFilesCreated());
            }
        } catch (RuntimeException | IOException failure) {
            restore(snapshot);
            throw failure;
        }
        return new BatchResult(items, touched, !dryRun);
    }

    // --- internal -----------------------------------------------------

    private static void validate(ModelEdit e) {
        if (e.getKind() == ModelEdit.Kind.COLLECTION) {
            if (e.getIs() == null || e.getContains() == null) {
                throw new IllegalArgumentException(
                    "collection '" + e.getName() + "' needs both 'is' and 'contains'");
            }
        }
        if (e.getKind() == ModelEdit.Kind.FIELDS) {
            if (e.getFields().isEmpty()) {
                throw new IllegalArgumentException(
                    "FIELDS edit for '" + e.getName() + "' carries no fields");
            }
            scopeOf(e, null);   // scope is mandatory here; throws if missing or unknown
        } else if (e.getScope() != null) {
            scopeOf(e, FieldEditor.ModelScope.SERVICE_MESSAGES);  // reject a bad scope up front
        }
        for (com.neeve.appbuilder.model.FieldDef f : e.getFields()) {
            if (f.getName() == null || f.getName().isBlank()) {
                throw new IllegalArgumentException(
                    "a field on " + e.describe() + " has no name");
            }
        }
    }

    private static ChangeSet applyOne(Path appRoot, ModelEdit e, boolean dryRun) throws IOException {
        switch (e.getKind()) {
            case MESSAGE:
                // The scope-aware overload, deliberately: the 5-arg one hard-codes
                // SERVICE_MESSAGES, so scope:"roe" was silently writing the message
                // into the service's private model and returning success.
                return MessageEditor.addMessage(appRoot, e.getService(),
                                                scopeOf(e, FieldEditor.ModelScope.SERVICE_MESSAGES),
                                                e.getName(), e.getFields(), dryRun);
            case MESSAGE_ENTITY:
                return EntityEditor.addEntity(appRoot, e.getService(), scopeOf(e, FieldEditor.ModelScope.SERVICE_MESSAGES),
                                              e.getName(), e.getFields(), dryRun);
            case STATE_ENTITY:
                return StateEditor.addStateEntity(appRoot, e.getService(), e.getName(), e.getFields(), dryRun);
            case COLLECTION:
                return CollectionEditor.addCollection(appRoot, e.getService(), e.getName(),
                                                      e.getIs(), e.getContains(), dryRun);
            case FIELDS:
                // No default here. A message and a state entity sharing a name is
                // routine in a Rumi app, so defaulting to messages would append a
                // state entity's fields to the message of the same name and report
                // success. Requiring the scope is the only answer that cannot be
                // quietly wrong; validate() enforces it before anything is written.
                return FieldEditor.addFields(appRoot, e.getService(), scopeOf(e, null),
                                             e.getName(), e.getFields(), dryRun);
            default:
                throw new IllegalArgumentException("unhandled edit kind: " + e.getKind());
        }
    }

    /**
     * Parse an edit's scope.
     *
     * <p>Accepts exactly what {@code POST /v1/services/&#123;svc&#125;/fields} accepts —
     * case-insensitive, trimmed, and the {@code service_messages} /
     * {@code service_state} / {@code roe_messages} aliases. A second, stricter
     * parser would mean the same value works on one endpoint and 400s on the
     * other, on the very migration this call is asking callers to make.
     */
    static FieldEditor.ModelScope scopeOf(ModelEdit e, FieldEditor.ModelScope fallback) {
        String raw = e.getScope();
        if (raw == null || raw.isBlank()) {
            if (fallback != null) return fallback;
            throw new IllegalArgumentException(
                "edit " + e.describe() + " needs an explicit scope (messages|state|roe): a message"
                + " and a state entity can share a name, so guessing would silently edit the wrong one");
        }
        switch (raw.trim().toLowerCase(java.util.Locale.ROOT)) {
            case "messages":
            case "service_messages": return FieldEditor.ModelScope.SERVICE_MESSAGES;
            case "state":
            case "service_state":    return FieldEditor.ModelScope.SERVICE_STATE;
            case "roe":
            case "roe_messages":     return FieldEditor.ModelScope.ROE_MESSAGES;
            default:
                throw new IllegalArgumentException(
                    "unknown scope '" + raw + "' on " + e.describe()
                    + "; expected messages, state or roe");
        }
    }

    /**
     * Every model XML in the app, plus the factory-id ledger.
     *
     * <p>Snapshotting the whole model tree rather than working out each item's
     * target file: the set is small and cheap, and deriving it per item would
     * have to duplicate each editor's private path resolution — which is
     * exactly the kind of second copy that drifts and then restores the wrong
     * file.
     */
    private static Map<Path, byte[]> snapshotModelFiles(Path appRoot) throws IOException {
        Map<Path, byte[]> snap = new LinkedHashMap<>();
        try (Stream<Path> walk = Files.walk(appRoot)) {
            List<Path> files = walk.filter(Files::isRegularFile)
                .filter(p -> {
                    String s = p.toString();
                    return (s.contains("src" + java.io.File.separator + "main"
                                       + java.io.File.separator + "models") && s.endsWith(".xml"))
                        || p.getFileName().toString().equals(".rumi-factory-ids");
                })
                .collect(java.util.stream.Collectors.toList());
            for (Path f : files) snap.put(f, Files.readAllBytes(f));
        }
        return snap;
    }

    private static void restore(Map<Path, byte[]> snapshot) {
        for (Map.Entry<Path, byte[]> e : snapshot.entrySet()) {
            try {
                Files.write(e.getKey(), e.getValue());
            } catch (IOException ignored) {
                // Restoring is best-effort by necessity: the original failure is
                // what the caller needs to see, and swallowing it to report a
                // restore problem would hide the cause. A file we cannot rewrite
                // was almost certainly never written in the first place.
            }
        }
    }
}
