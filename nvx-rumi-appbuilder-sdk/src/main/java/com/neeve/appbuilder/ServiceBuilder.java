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
package com.neeve.appbuilder;

import java.io.IOException;
import java.nio.file.*;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

public class ServiceBuilder {
    public enum ServiceType {
        DRIVER("driver", false),
        CONNECTOR("connector", false),
        PROCESSOR("processor", true),
        WEBSERVICE("webservice", true);

        private final String name;
        private final boolean clusterable;

        ServiceType(String name, boolean clusterable) {
            this.name = name;
            this.clusterable = clusterable;
        }

        public String getName() {
            return name;
        }

        public boolean isClusterable() {
            return clusterable;
        }

        public static ServiceType fromString(String value) {
            for (ServiceType type : ServiceType.values()) {
                if (type.name.equalsIgnoreCase(value)) {
                    return type;
                }
            }
            throw new IllegalArgumentException("Unsupported service type: " + value);
        }
    }

    public enum ServiceHAModel {
        STATE_REPLICATION("sr"),
        EVENT_SOURCING("es");

        private final String name;

        ServiceHAModel(String name) {
            this.name = name;
        }

        public String getName() {
            return name;
        }

        public static ServiceHAModel fromString(String value) {
            for (ServiceHAModel model : ServiceHAModel.values()) {
                if (model.name.equalsIgnoreCase(value)) {
                    return model;
                }
            }
            throw new IllegalArgumentException("Unsupported service HA model: " + value);
        }
    }

    public static class ServiceParams {
        private final ApplicationBuilder.AppParams appParams;
        private final String serviceName;
        private final ServiceType serviceType;
        private final ServiceHAModel serviceHAModel;
        private final boolean clustered;
        private final int numPartitions;
        /** Null means "inherit whatever mode the app was scaffolded in". */
        private final Boolean includeSamples;
        private final Map<String, String> tokenMap;

        public ServiceParams(String appRoot,
                             String serviceName,
                             ServiceType serviceType,
                             ServiceHAModel serviceHAModel,
                             boolean clustered,
                             int numPartitions) throws IOException {
            this(appRoot, serviceName, serviceType, serviceHAModel, clustered, numPartitions, null);
        }

        /**
         * @param includeSamples whether this service's scaffold carries worked
         *                       example code, or null to inherit the mode
         *                       recorded in the app's {@code .rumi} descriptor.
         *                       Inheriting is what makes an app scaffolded
         *                       sample-free stay that way as services are added.
         */
        public ServiceParams(String appRoot,
                             String serviceName,
                             ServiceType serviceType,
                             ServiceHAModel serviceHAModel,
                             boolean clustered,
                             int numPartitions,
                             Boolean includeSamples) throws IOException {
            if (appRoot == null || appRoot.isEmpty()) {
                throw new IllegalArgumentException("app root cannot be null or empty");
            }
            if (serviceName == null || serviceName.isEmpty()) {
                throw new IllegalArgumentException("service name cannot be null or empty");
            }
            if (numPartitions <= 0) {
                throw new IllegalArgumentException("num partitions cannot be negative");
            }
            this.appParams = ApplicationBuilder.AppParams.read(Paths.get(appRoot));
            this.serviceName = serviceName;
            this.serviceType = serviceType != null ? serviceType : ServiceType.PROCESSOR;
            this.serviceHAModel = serviceHAModel;
            if (this.serviceType.isClusterable() && this.serviceHAModel == null) {
                throw new IllegalArgumentException("service HA model must be specified for the '" + this.serviceType.getName() + "' service type.");
            }
            this.clustered = clustered;
            if (!this.serviceType.isClusterable() && this.clustered) {
                throw new IllegalArgumentException("the '" + this.serviceType.getName() + "' service type is not clusterable.");
            }
            this.numPartitions = numPartitions;
            this.includeSamples = includeSamples;
            this.tokenMap = toTokenMap();
        }

        private Map<String, String> toTokenMap() {
            Map<String, String> map = new HashMap<>(appParams.getTokenMap());
            String kebabCase = TokenUtils.toKebabCase(serviceName);
            String slashCase = TokenUtils.toSlashCase(kebabCase);
            String dottedCase = TokenUtils.toPackagePath(kebabCase);
            String parentArtifactId = map.get(TokenUtils.toToken("ParentArtifactId"));
            String serviceArtifactId = parentArtifactId + "-" + kebabCase;
            map.put(TokenUtils.toToken("ServiceDisplayName"), TokenUtils.forDisplay(serviceName));
            map.put(TokenUtils.toToken("ServiceTokenName"), kebabCase);     // e.g., "order-processor"
            map.put(TokenUtils.toToken("ServiceName"), map.get(TokenUtils.toToken("AppTokenName")) + "-" + kebabCase); // e.g., "tradingsystem-order-processor"
            map.put(TokenUtils.toToken("ServicePackageName"), dottedCase);  // e.g., "order.processor"
            map.put(TokenUtils.toToken("ServicePackagePath"), slashCase);      // e.g., "order/processor"
            map.put(TokenUtils.toToken("ServiceType"), serviceType.getName());
            map.put(TokenUtils.toToken("ServiceHAModel"), serviceHAModel == null || serviceHAModel == ServiceHAModel.STATE_REPLICATION ? "StateReplication" : "EventSourcing");
            map.put(TokenUtils.toToken("ServiceArtifactId"), serviceArtifactId);
            // Default HTTP port for webservice services. The generated config
            // exposes this as an overridable env property; multi-instance
            // deployments must override it to avoid port collisions.
            map.put(TokenUtils.toToken("ServiceHttpPort"), "8080");
            return map;
        }

        public String getServiceName() {
            return serviceName;
        }

        public ApplicationBuilder.AppParams getAppParams() {
            return appParams;
        }

        public ServiceType getServiceType() {
            return serviceType;
        }

        public ServiceHAModel getServiceHAModel() {
            return serviceHAModel;
        }

        public boolean isClustered() {
            return clustered;
        }

        public int getNumPartitions() {
            return numPartitions;
        }

        /**
         * Whether this service's scaffold carries worked example code, falling
         * back to the mode the app itself was scaffolded in.
         */
        public boolean isIncludeSamples() {
            return includeSamples != null ? includeSamples : appParams.isIncludeSamples();
        }

        public Map<String, String> getTokenMap() {
            return tokenMap;
        }
    }

    private void updateParentPom(Path appRoot, ServiceParams params) throws IOException {
        // resolve pom to be updated
        Path pomPath = appRoot.resolve("pom.xml");

        // get the token map
        Map<String, String> tokens = params.getTokenMap();

        // prepare service module line to be added
        String serviceModuleLine = "        <module>" + tokens.get(TokenUtils.toToken("ServiceArtifactId")) + "</module>";

        // prepare system module line that serves as position where the new module line needs to be added
        String systemModuleLine = "<module>" + tokens.get(TokenUtils.toToken("SystemArtifactId")) + "</module>";

        // read the pom
        List<String> lines = Files.readAllLines(pomPath);

        // skip if it's already present
        for (int i = 0; i < lines.size(); i++) {
            if (lines.get(i).trim().equals(serviceModuleLine.trim())) {
                return;
            }
        }

        // find the line to insert before
        int insertIndex = -1;
        for (int i = 0; i < lines.size(); i++) {
            if (lines.get(i).trim().equals(systemModuleLine.trim())) {
                insertIndex = i;
                break;
            }
        }

        // insert
        if (insertIndex != -1) {
            lines.add(insertIndex, serviceModuleLine);
            Files.write(pomPath, lines);
        } 
        else {
            throw new IOException("Could not find system module in pom.xml");
        }
    }

    private void updateSystemModulePom(Path appRoot, ServiceParams params) throws IOException {
        // get the tokens
        Map<String, String> tokens = params.getTokenMap();

        // resolve the system module pom.xml path
        Path pomPath = appRoot.resolve(tokens.get(TokenUtils.toToken("SystemArtifactId"))).resolve("pom.xml");

        // read the POM content
        List<String> lines = Files.readAllLines(pomPath);
        String closingTag = "</dependencies>";
        String dependencyBlock = String.format(
            "        <dependency>\n" +
            "            <groupId>%s</groupId>\n" +
            "            <artifactId>%s</artifactId>\n" +
            "            <version>${project.version}</version>\n" +
            "        </dependency>",
            tokens.get(TokenUtils.toToken("GroupId")),
            tokens.get(TokenUtils.toToken("ServiceArtifactId"))
        );

        // find the last </dependencies> tag and inject before it
        for (int i = 0; i < lines.size(); i++) {
            if (lines.get(i).contains(closingTag)) {
                lines.add(i, dependencyBlock);
                break;
            }
        }

        // write back the modified pom
        Files.write(pomPath, lines);
    }

    public ServiceBuilder createService(ServiceParams params) throws Exception {
        // validate
        if (params == null) {
            throw new IllegalArgumentException("params cannot be null");
        }

        // get params
        ApplicationBuilder.AppParams appParams = params.getAppParams();
        Path appRoot = Paths.get(appParams.getAppRoot()).toAbsolutePath();
        String serviceTypeName = params.getServiceType().getName();
        String buildToolName = appParams.getBuildTool().getName();

        // extract template to a temp directory
        Path templateDir;
        try {
            String templatePath = String.format("templates/%s/service/%s", buildToolName, serviceTypeName);
            if (params.getServiceHAModel() != null) {
                templatePath = templatePath + "/" + params.getServiceHAModel().getName();
            }
            templateDir = TemplateProcessor.extractTemplateDirectory("rumi-service-template", templatePath, false);
        }
        catch (IOException e) {
            throw new IOException("Failed to extract template for build tool '" + buildToolName + "' and service type '" + serviceTypeName + "'", e);
        }

        // prepare factory id tokens
        List<Integer> availableIds = FactoryIdCollector.collectAvailableFactoryIds(appRoot, 2);
        params.getTokenMap().put(TokenUtils.toToken("ServiceStateModelId"), String.valueOf(availableIds.get(0)));
        params.getTokenMap().put(TokenUtils.toToken("ServiceMessageModelId"), String.valueOf(availableIds.get(1)));

        // generate the service skeleton
        TemplateProcessor.applyTemplate(templateDir, appRoot, params.getTokenMap(), params.isIncludeSamples());

        // inject service config
        ConfigInjector.injectServiceConfig(appRoot, params);

        // inject deployment script updates
        ScriptInjector.injectIntoScripts(appRoot, params);

        // update the system module pom
        updateSystemModulePom(appRoot, params);

        // update the parent pom
        updateParentPom(appRoot, params);

        // record the factory ids this service now owns into the app-global ledger
        // so they are never reused even after the service is later removed.
        FactoryIdCollector.recordAllocatedIds(appRoot);

        // done
        return this;
    }
}

