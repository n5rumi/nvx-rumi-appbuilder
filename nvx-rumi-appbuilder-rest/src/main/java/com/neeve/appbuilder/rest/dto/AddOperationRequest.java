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

import com.fasterxml.jackson.annotation.JsonCreator;
import com.fasterxml.jackson.annotation.JsonProperty;

/**
 * Request body for {@code POST /v1/services/{svc}/operations}. Pairs a request
 * message with a response message. An operation describes the service's API
 * only; exposing it over HTTP is a REST endpoint on a web service (RUMI-457).
 */
public final class AddOperationRequest {
    private final String name;
    private final String inMessage;
    private final String outMessage;
    private final String restPath;
    private final String restMethod;

    @JsonCreator
    public AddOperationRequest(@JsonProperty("name") String name,
                               @JsonProperty("inMessage") String inMessage,
                               @JsonProperty("outMessage") String outMessage,
                               @JsonProperty("restPath") String restPath,
                               @JsonProperty("restMethod") String restMethod) {
        // RUMI-456 removed these. They are still bound so that an MCP server older
        // than this REST service, which sends both as explicit nulls on every call,
        // is not refused for an unknown property; Operations refuses a real value.
        this.restPath = restPath;
        this.restMethod = restMethod;
        this.name = name;
        this.inMessage = inMessage;
        this.outMessage = outMessage;
    }

    public String getName() { return name; }
    public String getInMessage() { return inMessage; }
    public String getOutMessage() { return outMessage; }

    /** The removed RUMI-456 field that carries a value, or null if neither does. */
    public String removedFieldInUse() {
        if (restPath != null && !restPath.isBlank()) return "restPath";
        if (restMethod != null && !restMethod.isBlank()) return "restMethod";
        return null;
    }
}
