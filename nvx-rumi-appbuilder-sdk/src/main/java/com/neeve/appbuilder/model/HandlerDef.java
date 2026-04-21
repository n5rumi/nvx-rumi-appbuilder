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

import java.util.Objects;

/**
 * Event handler declaration in a service's Main.java. Populated by
 * HandlerIntrospector (RUMI-292 / Phase C2). Placed here now so Phase B's
 * ServiceInfo can reference it without a forward dependency on Phase C.
 */
public final class HandlerDef {
    private final String methodName;
    private final String messageType;
    private final String returnType;
    private final int startLine;

    public HandlerDef(String methodName, String messageType, String returnType, int startLine) {
        this.methodName = Objects.requireNonNull(methodName, "methodName");
        this.messageType = messageType;  // null for zero/multi-param handlers (lifecycle events, etc.)
        this.returnType = returnType;    // may be null or "void"
        this.startLine = startLine;
    }

    public String getMethodName() {
        return methodName;
    }

    /**
     * The single parameter's type — the message type the handler receives.
     * Null for handlers with zero or more-than-one parameters (lifecycle
     * handlers and edge cases).
     */
    public String getMessageType() {
        return messageType;
    }

    public String getReturnType() {
        return returnType;
    }

    public int getStartLine() {
        return startLine;
    }

    @Override
    public String toString() {
        return "HandlerDef{" + methodName + "(" + messageType + ")}";
    }
}
