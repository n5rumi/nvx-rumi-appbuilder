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
import com.neeve.appbuilder.ApplicationBuilder;

/**
 * Request body for {@code POST /v1/apps}. Mirrors the 11-arg
 * {@link ApplicationBuilder.AppParams} constructor in a JSON-friendly
 * shape with defaults for the less-commonly-overridden fields.
 */
public final class AppParamsRequest {
    private final String appName;
    private final String appDir;
    private final String packageName;
    private final String groupId;
    private final String artifactPrefix;
    private final String rumiVersion;
    private final String rumiBindingsVersion;
    private final String rumiMgmtVersion;
    private final String encodingType;
    private final String messagingProvider;
    private final String buildTool;

    @JsonCreator
    public AppParamsRequest(@JsonProperty("appName") String appName,
                            @JsonProperty("appDir") String appDir,
                            @JsonProperty("packageName") String packageName,
                            @JsonProperty("groupId") String groupId,
                            @JsonProperty("artifactPrefix") String artifactPrefix,
                            @JsonProperty("rumiVersion") String rumiVersion,
                            @JsonProperty("rumiBindingsVersion") String rumiBindingsVersion,
                            @JsonProperty("rumiMgmtVersion") String rumiMgmtVersion,
                            @JsonProperty("encodingType") String encodingType,
                            @JsonProperty("messagingProvider") String messagingProvider,
                            @JsonProperty("buildTool") String buildTool) {
        this.appName = appName;
        this.appDir = appDir;
        this.packageName = packageName;
        this.groupId = groupId;
        this.artifactPrefix = artifactPrefix;
        this.rumiVersion = rumiVersion;
        this.rumiBindingsVersion = rumiBindingsVersion;
        this.rumiMgmtVersion = rumiMgmtVersion;
        this.encodingType = encodingType;
        this.messagingProvider = messagingProvider;
        this.buildTool = buildTool;
    }

    public ApplicationBuilder.AppParams toSdk() {
        return new ApplicationBuilder.AppParams(
            appName,
            appDir,
            packageName,
            groupId,
            artifactPrefix,
            rumiVersion,
            rumiBindingsVersion != null ? rumiBindingsVersion : rumiVersion,
            rumiMgmtVersion != null ? rumiMgmtVersion : "2.0.0",
            encodingType != null ? ApplicationBuilder.EncodingType.valueOf(encodingType.toUpperCase()) : ApplicationBuilder.EncodingType.QUARK,
            messagingProvider != null ? ApplicationBuilder.MessagingProvider.valueOf(messagingProvider.toUpperCase()) : ApplicationBuilder.MessagingProvider.ACTIVEMQ,
            buildTool != null ? ApplicationBuilder.BuildTool.valueOf(buildTool.toUpperCase()) : ApplicationBuilder.BuildTool.MAVEN
        );
    }

    public String getAppName() { return appName; }
    public String getAppDir() { return appDir; }
    public String getPackageName() { return packageName; }
    public String getGroupId() { return groupId; }
    public String getArtifactPrefix() { return artifactPrefix; }
    public String getRumiVersion() { return rumiVersion; }
    public String getRumiBindingsVersion() { return rumiBindingsVersion; }
    public String getRumiMgmtVersion() { return rumiMgmtVersion; }
    public String getEncodingType() { return encodingType; }
    public String getMessagingProvider() { return messagingProvider; }
    public String getBuildTool() { return buildTool; }
}
