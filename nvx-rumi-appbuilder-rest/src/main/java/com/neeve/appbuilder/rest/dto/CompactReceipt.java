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
package com.neeve.appbuilder.rest.dto;

import com.fasterxml.jackson.annotation.JsonInclude;
import com.neeve.appbuilder.model.BatchResult;
import com.neeve.appbuilder.model.ChangeSet;

import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;

/**
 * The short form of a write receipt (RUMI-414).
 *
 * <p>A purpose-built view rather than Jackson {@code NON_EMPTY} on the SDK
 * types. Bean-level inclusion overrides do not reach a nested type, so
 * suppressing empties on {@link BatchResult} left every
 * {@link BatchResult.Item} carrying {@code "reason":null} — a twenty-item batch
 * shipping ~300 bytes of nulls, more than the ~95 removed from a single write,
 * on the endpoint {@code apply_model} made primary. And a mapper-level rule
 * cannot see the request, so {@code detail=true} could restore the paths and
 * not the keys, which is a flag that half works.
 *
 * <p>Both problems come from putting the rule in the mapper. Here the response
 * filter chooses between this view and the untouched SDK object, so the flag
 * means one thing and nesting is explicit.
 */
@JsonInclude(JsonInclude.Include.NON_EMPTY)
public final class CompactReceipt {

    /** One item of a batch, without the null reason on the common path. */
    @JsonInclude(JsonInclude.Include.NON_EMPTY)
    public static final class Item {
        private final String edit;
        private final boolean applied;
        private final boolean noop;
        private final String reason;

        Item(BatchResult.Item src) {
            this.edit = src.getEdit();
            this.applied = src.isApplied();
            this.noop = src.isNoop();
            this.reason = src.getReason();
        }

        public String getEdit() { return edit; }

        // Booleans stay ALWAYS: NON_EMPTY would drop `false`, and "applied"
        // absent is not the same answer as "applied: false" to anything reading
        // this. Only the optional reason is suppressed.
        @JsonInclude(JsonInclude.Include.ALWAYS)
        public boolean isApplied() { return applied; }

        @JsonInclude(JsonInclude.Include.ALWAYS)
        public boolean isNoop() { return noop; }

        public String getReason() { return reason; }
    }

    private final boolean applied;
    private final boolean noop;
    private final String reason;
    private final List<Path> filesCreated;
    private final List<Path> filesModified;
    private final List<Path> filesDeleted;
    private final List<Integer> factoryIdsReserved;
    private final List<Integer> factoryIdsReleased;
    private final List<Item> items;

    private CompactReceipt(boolean applied, boolean noop, String reason,
                           List<Path> created, List<Path> modified, List<Path> deleted,
                           List<Integer> reserved, List<Integer> released, List<Item> items) {
        this.applied = applied;
        this.noop = noop;
        this.reason = reason;
        this.filesCreated = created;
        this.filesModified = modified;
        this.filesDeleted = deleted;
        this.factoryIdsReserved = reserved;
        this.factoryIdsReleased = released;
        this.items = items;
    }

    public static CompactReceipt of(ChangeSet cs, java.util.function.UnaryOperator<Path> shorten) {
        return new CompactReceipt(cs.isApplied(), cs.isNoop(), cs.getReason(),
            map(cs.getFilesCreated(), shorten), map(cs.getFilesModified(), shorten),
            map(cs.getFilesDeleted(), shorten),
            cs.getFactoryIdsReserved(), cs.getFactoryIdsReleased(), null);
    }

    public static CompactReceipt of(BatchResult r, java.util.function.UnaryOperator<Path> shorten) {
        List<Item> items = new ArrayList<>(r.getItems().size());
        for (BatchResult.Item i : r.getItems()) items.add(new Item(i));
        return new CompactReceipt(r.isApplied(), false, null,
            null, map(r.getFilesModified(), shorten), null, null, null, items);
    }

    private static List<Path> map(List<Path> in, java.util.function.UnaryOperator<Path> shorten) {
        if (in == null || in.isEmpty()) return null;
        List<Path> out = new ArrayList<>(in.size());
        for (Path p : in) out.add(shorten.apply(p));
        return out;
    }

    // `applied` and `noop` are the answer, so they are always present even when
    // false. Everything else is detail that is only interesting when it exists.
    @JsonInclude(JsonInclude.Include.ALWAYS)
    public boolean isApplied() { return applied; }

    @JsonInclude(JsonInclude.Include.ALWAYS)
    public boolean isNoop() { return noop; }

    public String getReason() { return reason; }
    public List<Path> getFilesCreated() { return filesCreated; }
    public List<Path> getFilesModified() { return filesModified; }
    public List<Path> getFilesDeleted() { return filesDeleted; }
    public List<Integer> getFactoryIdsReserved() { return factoryIdsReserved; }
    public List<Integer> getFactoryIdsReleased() { return factoryIdsReleased; }
    public List<Item> getItems() { return items; }
}
