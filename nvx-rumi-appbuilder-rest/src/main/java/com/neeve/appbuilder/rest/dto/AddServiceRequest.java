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
import com.neeve.appbuilder.ServiceBuilder;

/**
 * Request body for {@code POST /v1/services}.
 *
 * <p>{@code type} is one of {@code processor}, {@code driver}, {@code csvwriter}.
 * The HA/partition fields are only consumed for {@code processor}; the
 * other types ignore them.
 */
public final class AddServiceRequest {
    private final String name;
    private final String type;
    private final String haModel;
    private final boolean clustered;
    private final Integer partitions;

    @JsonCreator
    public AddServiceRequest(@JsonProperty("name") String name,
                             @JsonProperty("type") String type,
                             @JsonProperty("haModel") String haModel,
                             @JsonProperty("clustered") boolean clustered,
                             @JsonProperty("partitions") Integer partitions) {
        this.name = name;
        this.type = type;
        this.haModel = haModel;
        this.clustered = clustered;
        this.partitions = partitions;
    }

    public ServiceBuilder.ServiceParams toSdk(String appRoot) throws java.io.IOException {
        ServiceBuilder.ServiceType resolvedType = ServiceBuilder.ServiceType.valueOf(type.toUpperCase());
        ServiceBuilder.ServiceHAModel resolvedHa = null;
        if (resolvedType == ServiceBuilder.ServiceType.PROCESSOR) {
            resolvedHa = haModel == null
                ? ServiceBuilder.ServiceHAModel.STATE_REPLICATION
                : ServiceBuilder.ServiceHAModel.valueOf(haModel.toUpperCase());
        }
        int p = partitions == null ? 1 : partitions;
        return new ServiceBuilder.ServiceParams(appRoot, name, resolvedType, resolvedHa, clustered, p);
    }

    public String getName() { return name; }
    public String getType() { return type; }
    public String getHaModel() { return haModel; }
    public boolean isClustered() { return clustered; }
    public Integer getPartitions() { return partitions; }
}
