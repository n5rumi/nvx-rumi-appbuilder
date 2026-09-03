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

import com.neeve.appbuilder.ServiceBuilder;
import com.neeve.appbuilder.model.CollectionDef;
import com.neeve.appbuilder.model.EntityDef;
import com.neeve.appbuilder.model.HandlerDef;
import com.neeve.appbuilder.model.MessageDef;
import com.neeve.appbuilder.model.ServiceInfo;

import java.nio.file.Path;
import java.util.List;
import java.util.function.UnaryOperator;

/**
 * A {@link ServiceInfo} whose {@code moduleDir} is relative to the app root
 * (RUMI-414).
 *
 * <p>{@code add_service} is the endpoint that starts every build, and it handed
 * the caller back the app root it had just supplied. The claim that this change
 * shortens "every write" was not true without it.
 *
 * <p>Every other field is passed through unchanged — this exists to shorten one
 * path, not to become a second definition of a service.
 */
public final class ServiceInfoView {
    private final ServiceInfo delegate;
    private final Path moduleDir;

    private ServiceInfoView(ServiceInfo delegate, Path moduleDir) {
        this.delegate = delegate;
        this.moduleDir = moduleDir;
    }

    public static ServiceInfoView of(ServiceInfo src, UnaryOperator<Path> shorten) {
        return new ServiceInfoView(src, src.getModuleDir() == null
            ? null : shorten.apply(src.getModuleDir()));
    }

    public String getName() { return delegate.getName(); }
    public ServiceBuilder.ServiceType getType() { return delegate.getType(); }
    public Path getModuleDir() { return moduleDir; }
    public List<MessageDef> getMessages() { return delegate.getMessages(); }
    public List<EntityDef> getStateEntities() { return delegate.getStateEntities(); }
    public List<CollectionDef> getCollections() { return delegate.getCollections(); }
    public List<HandlerDef> getHandlers() { return delegate.getHandlers(); }
}
