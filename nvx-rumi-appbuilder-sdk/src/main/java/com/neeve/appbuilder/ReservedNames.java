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

import java.util.Arrays;
import java.util.Collections;
import java.util.LinkedHashSet;
import java.util.Set;

/**
 * Model field names whose generated accessor would collide with a {@code final}
 * method the generated type inherits, and so cannot be compiled.
 *
 * <h2>Why a field name can break the build</h2>
 *
 * An ADM-generated message or entity extends {@code com.neeve.rog.impl.RogNode},
 * which extends {@code com.neeve.sma.MessageViewImpl}. Between them those two
 * declare a large number of {@code final public} no-argument getters —
 * {@code getMessage()}, {@code getMessageKey()}, {@code getCreateTs()},
 * {@code getParent()}, {@code getMetadata()} and so on. A field named
 * {@code message}, {@code messageKey}, {@code createTs}, {@code parent} or
 * {@code metadata} generates a no-argument getter with that exact name, which
 * cannot override a final method.
 *
 * <p>Nothing rejects the name where it is written. The model is well-formed and
 * schema-valid, and the failure arrives later as a javac error inside generated
 * source the author never wrote — the same remote-from-its-cause shape
 * {@link ModelValidator} exists to eliminate.
 *
 * <h2>Only no-argument getters, deliberately</h2>
 *
 * A final method is only a problem when the generated accessor has the same
 * signature. {@code MessageViewImpl.getTag(int)} and {@code setTag(int, Object)}
 * take arguments, so a field named {@code tag} generates {@code getTag()} /
 * {@code setTag(v)}, which are legal <em>overloads</em>. {@code tag} is
 * therefore safe and is NOT listed here, despite being widely believed
 * otherwise. {@code isXxx()} methods are likewise excluded: the generator emits
 * {@code getXxx()} for a field, so an {@code isXxx()} on the base class is not
 * in the way.
 *
 * <p>That precision is the whole point. A false rejection blocks a legitimate
 * model, which costs more than the miss it prevents — the same posture the rest
 * of {@link ModelValidator} takes.
 *
 * <h2>Pinned to the milestone, and how to re-derive it</h2>
 *
 * This list is derived from the Rumi milestone the App Builder is built against
 * ({@code nvx.rumi.version}), and it moves when a base class gains a final
 * getter. Re-derive it on a milestone bump, from a Rumi source tree:
 *
 * <pre>
 * for f in nvx-rumi-sma/src/java/com/neeve/sma/MessageViewImpl.java \
 *          nvx-rumi-ods/src/java/com/neeve/rog/impl/RogNode.java; do
 *   grep -oE "final public [A-Za-z0-9_.&lt;&gt;\[\]]+ get[A-Z][A-Za-z0-9_]*\(\)" "$f"
 * done | sed -E 's/.* get([A-Za-z0-9_]*)\(\)/\1/' | sort -u
 * </pre>
 *
 * then decapitalize the first letter of each. Adding a name that has since
 * stopped being final costs a false rejection; missing a newly-final one costs
 * the build break this class exists to prevent, so err towards re-deriving.
 */
public final class ReservedNames {

    private ReservedNames() {}

    /**
     * Field names that cannot be used in a message or entity, derived from the
     * final no-argument getters on {@code RogNode} and {@code MessageViewImpl}
     * at Rumi 4.0.640.
     */
    private static final Set<String> RESERVED_FIELD_NAMES = Collections.unmodifiableSet(
        new LinkedHashSet<>(Arrays.asList(
            "appSendBeginTs", "appSendDoneTs", "attachment", "binding",
            "checkpointVersion", "committedDataSize", "createTs", "enqueueTs",
            "enqueueTsMicros", "graphId", "inMsgsInTransaction", "isInboundMessage",
            "isInternal", "isLastTransaction", "isLiveInboundMessage", "isMessage",
            "isOutboundMessage", "isPriority", "isReadOnly", "isReplayedMessage",
            "message", "messageBus", "messageBusAsRaw", "messageChannel",
            "messageChannelAsRaw", "messageEncodingType", "messageFlow", "messageKey",
            "messageKeyAsRaw", "messageReflector", "messageSender", "messageSequenceNumber",
            "messageTransportHeaders", "messageType", "metadata", "nodeType",
            "objectType", "ofid", "oid", "originTs",
            "outMsgsInTransaction", "outTs", "outTsMicros", "ownershipCount",
            "parent", "parentId", "postDeserializeTs", "postProcessingTs",
            "postProcessingTsMicros", "postSerializeTs", "postWireSendTs", "postWireTs",
            "preDeserializeTs", "preProcessingTs", "preProcessingTsMicros", "preSerializeTs",
            "preWireTs", "receiveTs", "requestId", "requestorId",
            "requestorIdAsRaw", "sendStartTs", "sendTs", "serializedMetadataLength",
            "stableTransactionId", "storeMetadata", "transactionId", "transactionInSequenceNumber",
            "transactionOutSequenceNumber", "vfid")));

    /**
     * Whether {@code fieldName} would generate an accessor colliding with an
     * inherited final method. Compared ignoring the case of the first letter,
     * since {@code message} and {@code Message} generate the same getter.
     *
     * @param fieldName a model field name; null or blank is never reserved.
     */
    public static boolean isReservedFieldName(String fieldName) {
        if (fieldName == null || fieldName.trim().isEmpty()) {
            return false;
        }
        return RESERVED_FIELD_NAMES.contains(decapitalize(fieldName.trim()));
    }

    /** The reserved names, for tests and for building an error message. */
    public static Set<String> reservedFieldNames() {
        return RESERVED_FIELD_NAMES;
    }

    private static String decapitalize(String s) {
        return Character.toLowerCase(s.charAt(0)) + s.substring(1);
    }
}
