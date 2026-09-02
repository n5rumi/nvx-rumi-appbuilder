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

/**
 * Body of a handler-body update (RUMI-411).
 *
 * <p>Only the body: the method name comes from the path and the signature is
 * deliberately not changeable here. Changing a handler's message type changes
 * which messages it receives, which is a different operation from editing what
 * it does — remove and re-add for that.
 */
public class UpdateHandlerRequest {
    private String body;

    /**
     * The Java method body, without the enclosing braces. An empty string is a
     * legitimate value — a handler emptied on purpose — and is not the same as
     * omitting the field.
     */
    public String getBody() {
        return body;
    }

    public void setBody(String body) {
        this.body = body;
    }
}
