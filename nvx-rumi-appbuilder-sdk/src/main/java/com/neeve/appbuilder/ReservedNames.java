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
 *
 * <h2>A THIRD source: the generated Pojo's own lifecycle flags</h2>
 *
 * Inherited final getters are not the only way to collide. Every generated
 * {@code Pojo} inner class carries its own lifecycle bitmask with private
 * {@code setX} / {@code isX} / {@code clearX} accessors per flag, generated
 * INTO the class rather than inherited, so the two-class derivation above
 * cannot see them. Read off a generated message at Rumi 4.0.667 the flags are:
 *
 * <pre>
 * FLG_CREATED  FLG_POOLED  FLG_PRIVATE  FLG_WASRUN  FLG_WASSET  FLG_ONLY_POJOS
 * </pre>
 *
 * {@code created} is the one that actually bites, because it is the obvious
 * name both for a timestamp and for the "did this create a new record" flag on
 * an upsert response — and note {@code createTs} was already covered while
 * {@code created} was not, which is exactly why the gap survived. Re-derive
 * these from a generated source on a milestone bump:
 *
 * <pre>
 * grep -oE "FLG_[A-Z_]+" &lt;module&gt;/target/generated-sources/rumi/adm/**&#47;*.java | sort -u
 * </pre>
 *
 * <h2>What a rejection does and does not prove</h2>
 *
 * A rejection from here is trustworthy. Acceptance is NOT a promise that the
 * model generates compilable code — this class knows about collisions, not
 * about everything the ADM generator can refuse. Compile after a model change,
 * before writing the Java that depends on it.
 */
public final class ReservedNames {

    private ReservedNames() {}

    /**
     * Field names that cannot be used in a message or entity: the final
     * no-argument getters on {@code RogNode} and {@code MessageViewImpl} at
     * Rumi 4.0.640, plus the generated Pojo lifecycle flags at 4.0.667.
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
     * Names colliding with the lifecycle-flag accessors generated INTO each
     * {@code Pojo} rather than inherited, so the RogNode/MessageViewImpl
     * derivation above cannot see them. Kept separate from the inherited set
     * only so a refusal can name the right source: telling an author their
     * field collides with an inherited final method, when it actually collides
     * with a generated flag accessor, sends them to look in the wrong place.
     */
    private static final Set<String> POJO_FLAG_NAMES = Collections.unmodifiableSet(
        new LinkedHashSet<>(Arrays.asList(
            "created", "onlyPojos", "pooled", "private", "wasRun", "wasSet")));

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
        final String key = decapitalize(fieldName.trim());
        return RESERVED_FIELD_NAMES.contains(key) || POJO_FLAG_NAMES.contains(key);
    }

    /**
     * Why {@code fieldName} is reserved, as a clause for a refusal message, or
     * null when it is not reserved at all. The two sources fail the same way —
     * generated source that will not compile — but in different places, so an
     * author chasing the collision needs to be told which.
     */
    public static String collisionSourceOf(String fieldName) {
        String source = null;
        if (fieldName != null && !fieldName.trim().isEmpty()) {
            final String key = decapitalize(fieldName.trim());
            if (POJO_FLAG_NAMES.contains(key)) {
                source = "one of the lifecycle-flag accessors generated into the message's "
                       + "own Pojo class (FLG_" + key.toUpperCase() + ")";
            }
            else if (RESERVED_FIELD_NAMES.contains(key)) {
                source = "a final method inherited from com.neeve.rog.impl.RogNode / "
                       + "com.neeve.sma.MessageViewImpl";
            }
        }
        return source;
    }

    /** The reserved names, for tests and for building an error message. */
    public static Set<String> reservedFieldNames() {
        return RESERVED_FIELD_NAMES;
    }

    private static String decapitalize(String s) {
        return Character.toLowerCase(s.charAt(0)) + s.substring(1);
    }
}
