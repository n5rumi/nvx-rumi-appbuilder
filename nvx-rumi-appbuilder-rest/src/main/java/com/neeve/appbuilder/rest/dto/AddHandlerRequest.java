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
 * Request body for {@code POST /v1/services/{svc}/handlers}.
 *
 * <p>{@code body} is the Java code for the method body (without braces).
 * Null means the editor inserts an empty body with a TODO comment.
 */
public final class AddHandlerRequest {
    private final String method;
    private final String messageType;
    private final String body;

    @JsonCreator
    public AddHandlerRequest(@JsonProperty("method") String method,
                             @JsonProperty("messageType") String messageType,
                             @JsonProperty("body") String body) {
        this.method = method;
        this.messageType = messageType;
        this.body = body;
    }

    public String getMethod() { return method; }
    public String getMessageType() { return messageType; }
    public String getBody() { return body; }
}
