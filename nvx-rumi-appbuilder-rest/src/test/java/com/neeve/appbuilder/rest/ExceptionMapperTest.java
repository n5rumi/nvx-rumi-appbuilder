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
package com.neeve.appbuilder.rest;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.neeve.appbuilder.rest.dto.ErrorResponse;
import com.neeve.appbuilder.rest.dto.ModelBatchRequest;
import com.neeve.appbuilder.rest.mappers.ExceptionMapper;
import jakarta.ws.rs.core.Response;
import org.junit.Test;

import static org.junit.Assert.*;

/**
 * RUMI-425: a body the server cannot bind is the caller's problem, so say
 * which part of it was wrong.
 *
 * <p>Before this, every Jackson failure fell through to
 * {@code 500 "An internal error occurred"} — correct for a server fault and
 * useless here. It cost a reporting agent a four-call bisection to discover
 * that its edit carried a property the DTO did not have.
 */
public class ExceptionMapperTest {

    private final ExceptionMapper mapper = new ExceptionMapper();
    private final ObjectMapper json = new ObjectMapper();

    private Response mapOf(Throwable t) {
        return mapper.toResponse(t);
    }

    private static ErrorResponse.Error body(Response r) {
        return ((ErrorResponse) r.getEntity()).getError();
    }

    @Test
    public void anUnknownPropertyIs400AndNamesTheProperty() throws Exception {
        Throwable bind = null;
        try {
            json.readValue(
                "{\"edits\":[{\"kind\":\"state_entity\",\"name\":\"X\",\"nosuchprop\":1}]}",
                ModelBatchRequest.class);
            fail("expected Jackson to reject the unknown property");
        } catch (Exception e) {
            bind = e;
        }

        Response r = mapOf(bind);
        assertEquals("a body we cannot bind is the caller's problem", 400, r.getStatus());
        assertEquals("BadRequest", body(r).getCode());
        assertTrue("names the offending property: " + body(r).getDescription(),
            body(r).getDescription().contains("nosuchprop"));
    }

    @Test
    public void aBindFailureWrappedByJerseyIsStillClassified() throws Exception {
        Throwable bind;
        try {
            json.readValue("{\"edits\":[{\"kind\":\"state_entity\",\"name\":\"X\",\"nope\":1}]}",
                ModelBatchRequest.class);
            throw new AssertionError("expected a bind failure");
        } catch (AssertionError e) {
            throw e;
        } catch (Exception e) {
            bind = e;
        }
        // Jersey does not hand the mapper the raw Jackson exception.
        Response r = mapOf(new RuntimeException("wrapped by the container", bind));
        assertEquals("the cause chain is walked", 400, r.getStatus());
        assertTrue(body(r).getDescription().contains("nope"));
    }

    @Test
    public void aGenuineServerFaultIsStill500AndSaysNothingRevealing() {
        Response r = mapOf(new RuntimeException("NullPointer in some internal helper"));
        assertEquals(500, r.getStatus());
        assertEquals("InternalError", body(r).getCode());
        assertEquals("An internal error occurred", body(r).getDescription());
    }

    @Test
    public void theExistingClassificationsAreUnchanged() {
        assertEquals(400, mapOf(new IllegalArgumentException("bad arg")).getStatus());
        assertEquals(409, mapOf(new IllegalStateException("already exists")).getStatus());
        assertEquals(422, mapOf(new IllegalStateException("not in that state")).getStatus());
    }
}
