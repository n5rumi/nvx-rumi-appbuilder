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
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Set;

/**
 * Outcome of a model batch (RUMI-412): what each item did, and the files the
 * batch touched overall.
 *
 * <p>Richer than a {@link ChangeSet} on purpose. A batch that reports only
 * "applied" tells a caller nothing about which of its twenty items were no-ops
 * because they already existed — which is exactly what a caller re-applying a
 * model needs to know.
 */
public final class BatchResult {

    /** One item's outcome. */
    public static final class Item {
        private final String edit;
        private final boolean applied;
        private final boolean noop;
        private final String reason;

        public Item(String edit, boolean applied, boolean noop, String reason) {
            this.edit = edit;
            this.applied = applied;
            this.noop = noop;
            this.reason = reason;
        }

        public String getEdit() { return edit; }
        public boolean isApplied() { return applied; }
        public boolean isNoop() { return noop; }
        public String getReason() { return reason; }
    }

    private final List<Item> items;
    private final List<Path> filesModified;
    private final boolean applied;

    public BatchResult(List<Item> items, Set<Path> filesModified, boolean applied) {
        this.items = Collections.unmodifiableList(new ArrayList<>(items));
        this.filesModified = Collections.unmodifiableList(new ArrayList<>(new LinkedHashSet<>(filesModified)));
        this.applied = applied;
    }

    public List<Item> getItems() { return items; }
    public List<Path> getFilesModified() { return filesModified; }

    /** True when the batch was written to disk (false for a dry run). */
    public boolean isApplied() { return applied; }

    /** Items that did something, as opposed to those that already existed. */
    public int getChangedCount() {
        return (int) items.stream().filter(i -> !i.isNoop()).count();
    }
}
