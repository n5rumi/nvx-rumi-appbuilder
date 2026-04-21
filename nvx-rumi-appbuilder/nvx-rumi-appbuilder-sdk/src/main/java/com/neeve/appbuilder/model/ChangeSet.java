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
package com.neeve.appbuilder.model;

import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Objects;

/**
 * Structured result of a mutation. Every editor/remover in the App Builder
 * SDK returns one of these so callers (REST, MCP, CLI) have a uniform
 * shape to surface.
 *
 * <p>Dry-run semantics: when a mutation is invoked with {@code dry_run = true},
 * the same ChangeSet is returned but {@link #isApplied()} is {@code false}
 * and the filesystem is untouched. Callers can use the
 * {@code filesCreated / filesModified / filesDeleted} lists to preview
 * what would change.
 */
public final class ChangeSet {
    private final boolean applied;
    private final List<Path> filesCreated;
    private final List<Path> filesModified;
    private final List<Path> filesDeleted;
    private final List<Integer> factoryIdsReserved;
    private final List<Integer> factoryIdsReleased;
    private final boolean noop;
    private final String reason;

    private ChangeSet(Builder b) {
        this.applied = b.applied;
        this.filesCreated = Collections.unmodifiableList(new ArrayList<>(b.filesCreated));
        this.filesModified = Collections.unmodifiableList(new ArrayList<>(b.filesModified));
        this.filesDeleted = Collections.unmodifiableList(new ArrayList<>(b.filesDeleted));
        this.factoryIdsReserved = Collections.unmodifiableList(new ArrayList<>(b.factoryIdsReserved));
        this.factoryIdsReleased = Collections.unmodifiableList(new ArrayList<>(b.factoryIdsReleased));
        this.noop = b.noop;
        this.reason = b.reason;
    }

    /** True if the change was written to disk. False for dry-run or noop. */
    public boolean isApplied() {
        return applied;
    }

    public List<Path> getFilesCreated() {
        return filesCreated;
    }

    public List<Path> getFilesModified() {
        return filesModified;
    }

    public List<Path> getFilesDeleted() {
        return filesDeleted;
    }

    public List<Integer> getFactoryIdsReserved() {
        return factoryIdsReserved;
    }

    public List<Integer> getFactoryIdsReleased() {
        return factoryIdsReleased;
    }

    /** True when the operation decided nothing needed to change (e.g. idempotent add, remove-of-absent). */
    public boolean isNoop() {
        return noop;
    }

    /** Short human-readable explanation, particularly useful when {@link #isNoop()} is true. */
    public String getReason() {
        return reason;
    }

    @Override
    public String toString() {
        return "ChangeSet{applied=" + applied
             + ", noop=" + noop
             + ", +files=" + filesCreated.size()
             + ", ~files=" + filesModified.size()
             + ", -files=" + filesDeleted.size()
             + ", +ids=" + factoryIdsReserved.size()
             + ", -ids=" + factoryIdsReleased.size()
             + (reason != null ? ", reason=" + reason : "")
             + "}";
    }

    public static Builder builder() {
        return new Builder();
    }

    /** Return a noop ChangeSet with the given reason. Applied=false, noop=true. */
    public static ChangeSet noop(String reason) {
        return builder().noop(true).reason(Objects.requireNonNull(reason)).build();
    }

    public static final class Builder {
        private boolean applied = false;
        private final List<Path> filesCreated = new ArrayList<>();
        private final List<Path> filesModified = new ArrayList<>();
        private final List<Path> filesDeleted = new ArrayList<>();
        private final List<Integer> factoryIdsReserved = new ArrayList<>();
        private final List<Integer> factoryIdsReleased = new ArrayList<>();
        private boolean noop = false;
        private String reason;

        public Builder applied(boolean v) { this.applied = v; return this; }
        public Builder noop(boolean v) { this.noop = v; return this; }
        public Builder reason(String v) { this.reason = v; return this; }
        public Builder filesCreated(List<Path> v) { this.filesCreated.addAll(v); return this; }
        public Builder addCreated(Path p) { this.filesCreated.add(p); return this; }
        public Builder filesModified(List<Path> v) { this.filesModified.addAll(v); return this; }
        public Builder addModified(Path p) { this.filesModified.add(p); return this; }
        public Builder filesDeleted(List<Path> v) { this.filesDeleted.addAll(v); return this; }
        public Builder addDeleted(Path p) { this.filesDeleted.add(p); return this; }
        public Builder addFactoryIdReserved(int id) { this.factoryIdsReserved.add(id); return this; }
        public Builder addFactoryIdReleased(int id) { this.factoryIdsReleased.add(id); return this; }

        public ChangeSet build() {
            return new ChangeSet(this);
        }
    }
}
