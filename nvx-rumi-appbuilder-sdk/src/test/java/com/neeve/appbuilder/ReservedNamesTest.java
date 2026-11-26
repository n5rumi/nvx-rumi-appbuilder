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

import org.junit.Test;

import static org.junit.Assert.*;

/**
 * The reserved-field-name list, and the blind spot it used to have.
 */
public class ReservedNamesTest {

    /**
     * Every generated Pojo carries its own lifecycle bitmask with private
     * setX/isX/clearX accessors per flag, generated INTO the class rather than
     * inherited -- so the RogNode/MessageViewImpl derivation never saw them.
     * A user field of the same name generates public accessors that collide,
     * and the failure lands in generated code the author never wrote, minutes
     * into a build. Verified against GreetRequest.java at Rumi 4.0.667:
     * FLG_CREATED FLG_POOLED FLG_PRIVATE FLG_WASRUN FLG_WASSET FLG_ONLY_POJOS.
     */
    @Test
    public void theGeneratedPojoLifecycleFlagsAreReserved() {
        for (String name : new String[]{"created", "pooled", "private", "wasRun", "wasSet", "onlyPojos"}) {
            assertTrue("Pojo flag accessor collision not caught: " + name,
                ReservedNames.isReservedFieldName(name));
        }
    }

    /**
     * 'created' is the one that actually bites -- the obvious name for a
     * timestamp and for the did-this-create-a-record flag on an upsert
     * response. 'createTs' was already covered while 'created' was not, which
     * is exactly why the gap survived: the neighbour was guarded.
     */
    @Test
    public void createdIsReservedAndCreatedAtIsNot() {
        assertTrue("created collides with FLG_CREATED's accessor",
            ReservedNames.isReservedFieldName("created"));
        assertTrue("createTs was already covered", ReservedNames.isReservedFieldName("createTs"));
        assertFalse("createdAt is the name to use instead",
            ReservedNames.isReservedFieldName("createdAt"));
        assertFalse("wasCreated is fine too", ReservedNames.isReservedFieldName("wasCreated"));
    }

    /** Matching ignores the case of the first letter, as the existing contract says. */
    @Test
    public void matchingIgnoresTheLeadingCase() {
        assertTrue(ReservedNames.isReservedFieldName("Created"));
        assertTrue(ReservedNames.isReservedFieldName("WasRun"));
    }

    /**
     * A false rejection blocks a legitimate model, which costs more than the
     * miss it prevents -- so the list must stay precise.
     */
    @Test
    public void ordinaryFieldNamesAreNotReserved() {
        for (String name : new String[]{"orderId", "symbol", "quantity", "price",
                                        "customerName", "status", "greeting", "personName"}) {
            assertFalse("false rejection on a legitimate name: " + name,
                ReservedNames.isReservedFieldName(name));
        }
    }

    @Test
    public void inheritedFinalGetterNamesAreStillReserved() {
        assertTrue(ReservedNames.isReservedFieldName("message"));
        assertTrue(ReservedNames.isReservedFieldName("messageType"));
        assertTrue(ReservedNames.isReservedFieldName("transactionId"));
    }

    /**
     * A refusal that names the wrong source sends the author to look in the
     * wrong place. 'created' collides with a flag accessor generated INTO the
     * Pojo; 'message' collides with an inherited final method. Same failure,
     * different file to go and read.
     */
    @Test
    public void theRefusalNamesWhichCollisionItIs() {
        String pojo = ReservedNames.collisionSourceOf("created");
        assertNotNull(pojo);
        assertTrue("should point at the generated Pojo flags, not inheritance",
            pojo.contains("generated into") && pojo.contains("FLG_CREATED"));
        assertFalse("must not blame inheritance for a flag collision",
            pojo.contains("inherited"));

        String inherited = ReservedNames.collisionSourceOf("message");
        assertNotNull(inherited);
        assertTrue("should point at the inherited final methods",
            inherited.contains("inherited") && inherited.contains("RogNode"));
        assertFalse(inherited.contains("FLG_"));

        assertNull("not reserved means no source", ReservedNames.collisionSourceOf("createdAt"));
        assertNull(ReservedNames.collisionSourceOf(null));
    }

    @Test
    public void nullAndBlankAreNotReserved() {
        assertFalse(ReservedNames.isReservedFieldName(null));
        assertFalse(ReservedNames.isReservedFieldName(""));
        assertFalse(ReservedNames.isReservedFieldName("   "));
    }
}
