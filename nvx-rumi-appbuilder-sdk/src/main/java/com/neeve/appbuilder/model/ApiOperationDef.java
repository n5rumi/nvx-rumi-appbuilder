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

import java.util.Collections;
import java.util.Map;
import java.util.Objects;

/**
 * An {@code <operation>} declaration in an X-ASML {@code api.xml}: a
 * request-reply operation pairing a request message ({@code inMessage}) with a
 * response message ({@code outMessage}). The Rumi {@code asm-generate} goal
 * turns each operation into a typed client method.
 */
public final class ApiOperationDef {
    private final String name;
    private final String inMessage;
    private final String outMessage;
    private final Map<String, String> attributes;

    public ApiOperationDef(String name, String inMessage, String outMessage, Map<String, String> attributes) {
        this.name = Objects.requireNonNull(name, "name");
        this.inMessage = inMessage;
        this.outMessage = outMessage;
        this.attributes = Collections.unmodifiableMap(
            Objects.requireNonNull(attributes, "attributes"));
    }

    public String getName() {
        return name;
    }

    /** Request message name. */
    public String getInMessage() {
        return inMessage;
    }

    /** Response/reply message name. */
    public String getOutMessage() {
        return outMessage;
    }

    /** Any other attributes on the element (e.g. {@code RESTPath}, {@code RESTMethod}). */
    public Map<String, String> getAttributes() {
        return attributes;
    }

    @Override
    public String toString() {
        return "ApiOperationDef{name=" + name + ", in=" + inMessage + ", out=" + outMessage + "}";
    }
}
