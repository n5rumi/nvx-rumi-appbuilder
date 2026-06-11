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
 * message with a response message; {@code restPath}/{@code restMethod} are
 * optional documentation hints.
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
        this.name = name;
        this.inMessage = inMessage;
        this.outMessage = outMessage;
        this.restPath = restPath;
        this.restMethod = restMethod;
    }

    public String getName() { return name; }
    public String getInMessage() { return inMessage; }
    public String getOutMessage() { return outMessage; }
    public String getRestPath() { return restPath; }
    public String getRestMethod() { return restMethod; }
}
