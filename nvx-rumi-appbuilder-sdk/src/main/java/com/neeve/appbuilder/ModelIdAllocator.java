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

import org.w3c.dom.Comment;
import org.w3c.dom.Document;
import org.w3c.dom.Element;
import org.w3c.dom.Node;
import org.w3c.dom.NodeList;

import java.util.regex.Matcher;
import java.util.regex.Pattern;

import static com.neeve.appbuilder.MessageIntrospector.ADML_NAMESPACE;

/**
 * Allocates monotonically increasing, never-reused ids for X-ADML model
 * elements. Ids in a Rumi wire model identify a type (message/entity/collection)
 * or a field on the wire; recycling a removed id would let an old peer
 * misinterpret a new type/field, so allocation is <em>high-water-mark + 1</em>,
 * never gap-filling.
 *
 * <p>Because a deleted element is physically removed from the file, its id is
 * preserved by a {@code <!-- id=N reserved ... -->} tombstone comment left in
 * the element's container (the same convention the hand-written Rumi models
 * use). The allocator counts those reserved ids as "used", so a removed id is
 * never handed back out.
 *
 * <p>Two scopes:
 * <ul>
 *   <li><b>Type ids</b> ({@link #nextTypeId}) are unique within the model's
 *       factory — so the scan spans every {@code <message>}, {@code <entity>}
 *       and {@code <collection>}, plus reserved tombstones under their
 *       containers. (The App Builder scaffolds one factory per model file.)
 *   <li><b>Field ids</b> ({@link #nextFieldId}) are unique within their owning
 *       {@code <message>}/{@code <entity>} — so the scan is local to that
 *       element's fields and its reserved tombstones.
 * </ul>
 */
final class ModelIdAllocator {
    private ModelIdAllocator() {}

    /** Matches reserved-id tombstone comments, e.g. {@code id=2 reserved (removed foo)}. */
    private static final Pattern RESERVED_ID = Pattern.compile("\\bid\\s*=\\s*(\\d+)\\s+reserved\\b");

    /** Local names of the type containers whose children share a factory id-space. */
    private static final String[] TYPE_CONTAINERS = {"messages", "entities", "collections"};
    private static final String[] TYPE_ELEMENTS = {"message", "entity", "collection"};

    /**
     * Next available type id (message/entity/collection) for the model: one
     * past the highest id ever used in the factory scope, including reserved
     * tombstones. Never reuses.
     */
    static int nextTypeId(Document doc) {
        Element root = doc.getDocumentElement();
        int max = 0;
        // Present type ids, across all three element kinds (shared id-space).
        for (String tag : TYPE_ELEMENTS) {
            NodeList nodes = doc.getElementsByTagNameNS(ADML_NAMESPACE, tag);
            for (int i = 0; i < nodes.getLength(); i++) {
                max = Math.max(max, idOf((Element) nodes.item(i)));
            }
        }
        // Reserved tombstones left under each type container.
        for (String container : TYPE_CONTAINERS) {
            Element c = MessageIntrospector.firstDirectChildByLocalName(root, container);
            if (c != null) max = Math.max(max, maxReservedIdUnder(c));
        }
        return max + 1;
    }

    /**
     * Next available field id within {@code typeElement} (a {@code <message>}
     * or {@code <entity>}): one past the highest field id ever used in that
     * element, including reserved tombstones. Never reuses.
     */
    static int nextFieldId(Element typeElement) {
        int max = 0;
        NodeList kids = typeElement.getChildNodes();
        for (int i = 0; i < kids.getLength(); i++) {
            Node n = kids.item(i);
            if (n.getNodeType() == Node.ELEMENT_NODE && "field".equals(n.getLocalName())) {
                max = Math.max(max, idOf((Element) n));
            }
        }
        max = Math.max(max, maxReservedIdUnder(typeElement));
        return max + 1;
    }

    /**
     * Build the {@code <!-- id=N reserved (removed NAME) -->} tombstone comment
     * that retires an id when its element is physically removed. The same format
     * the hand-written Rumi models use and that {@link #RESERVED_ID} parses back
     * out, so a removed id is never re-handed-out by {@link #nextTypeId} or
     * {@link #nextFieldId}. Returns {@code null} when {@code id} is absent/blank
     * (nothing to reserve).
     */
    static Comment reservedTombstone(Document doc, String id, String removedName) {
        if (id == null || id.isBlank()) return null;
        return doc.createComment(" id=" + id + " reserved (removed " + removedName + ") ");
    }

    // --- internal -----------------------------------------------------

    private static int idOf(Element e) {
        Integer id = MessageIntrospector.parseIntOrNull(e.getAttribute("id"));
        return id != null ? id : 0;
    }

    /** Highest reserved id mentioned in direct-child comment tombstones of {@code parent}. */
    private static int maxReservedIdUnder(Element parent) {
        int max = 0;
        NodeList kids = parent.getChildNodes();
        for (int i = 0; i < kids.getLength(); i++) {
            Node n = kids.item(i);
            if (n.getNodeType() == Node.COMMENT_NODE) {
                Matcher m = RESERVED_ID.matcher(n.getNodeValue());
                while (m.find()) {
                    max = Math.max(max, Integer.parseInt(m.group(1)));
                }
            }
        }
        return max;
    }
}
