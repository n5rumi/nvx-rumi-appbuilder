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

import com.neeve.appbuilder.ServiceBuilder;

import java.nio.file.Path;
import java.util.Collections;
import java.util.List;
import java.util.Objects;

/**
 * Rolled-up view of a service: type, module location, messages, state
 * entities, state collections, and (once Phase C lands) event handlers.
 */
public final class ServiceInfo {
    private final String name;
    private final ServiceBuilder.ServiceType type;
    private final Path moduleDir;
    private final List<MessageDef> messages;
    private final List<EntityDef> stateEntities;
    private final List<CollectionDef> collections;
    private final List<HandlerDef> handlers;

    public ServiceInfo(String name,
                       ServiceBuilder.ServiceType type,
                       Path moduleDir,
                       List<MessageDef> messages,
                       List<EntityDef> stateEntities,
                       List<CollectionDef> collections,
                       List<HandlerDef> handlers) {
        this.name = Objects.requireNonNull(name, "name");
        this.type = Objects.requireNonNull(type, "type");
        this.moduleDir = Objects.requireNonNull(moduleDir, "moduleDir");
        this.messages = Collections.unmodifiableList(Objects.requireNonNull(messages, "messages"));
        this.stateEntities = Collections.unmodifiableList(Objects.requireNonNull(stateEntities, "stateEntities"));
        this.collections = Collections.unmodifiableList(Objects.requireNonNull(collections, "collections"));
        this.handlers = Collections.unmodifiableList(Objects.requireNonNull(handlers, "handlers"));
    }

    public String getName() {
        return name;
    }

    public ServiceBuilder.ServiceType getType() {
        return type;
    }

    public Path getModuleDir() {
        return moduleDir;
    }

    public List<MessageDef> getMessages() {
        return messages;
    }

    public List<EntityDef> getStateEntities() {
        return stateEntities;
    }

    public List<CollectionDef> getCollections() {
        return collections;
    }

    /**
     * Event handler definitions (populated by HandlerIntrospector in Phase C).
     * Until C2 lands this list is always empty.
     */
    public List<HandlerDef> getHandlers() {
        return handlers;
    }

    @Override
    public String toString() {
        return "ServiceInfo{name=" + name + ", type=" + type
             + ", messages=" + messages.size()
             + ", entities=" + stateEntities.size()
             + ", handlers=" + handlers.size() + "}";
    }
}
