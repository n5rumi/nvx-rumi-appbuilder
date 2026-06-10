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

import com.neeve.appbuilder.model.ChangeSet;
import org.w3c.dom.Document;
import org.w3c.dom.Element;
import org.w3c.dom.Node;
import org.w3c.dom.NodeList;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Arrays;
import java.util.HashMap;
import java.util.Map;
import java.util.stream.Stream;

/**
 * Snaps custom connectors into (and out of) an existing service. A connector
 * is a user-authored Rumi message-bus binding made of three cohesive
 * artifacts, all created on add and reverted on remove:
 *
 * <ol>
 *   <li>a Java class under the service's {@code connector} subpackage,
 *       implementing {@code com.neeve.sma.spi.connector.Connector};
 *   <li>a {@code <bus descriptor="connector://...">} binding in the system
 *       {@code config.xml}'s {@code <buses>} block;
 *   <li>a {@code <bus name="..."/>} reference in the owning app template's
 *       {@code <messaging>} block.
 * </ol>
 *
 * <p>Works on any service type (processor, webservice, connector). Adds are
 * idempotent (identity-matched on connector name); removes are no-ops when the
 * connector is absent. Both support {@code dryRun}.
 *
 * <p>Scope (v1): a connector add does not mint new message types or factory
 * IDs. Inbound message types are added separately via {@link MessageEditor},
 * keeping the two concerns composable.
 */
public final class ConnectorEditor {
    private ConnectorEditor() {}

    /**
     * Add a connector named {@code connectorName} to {@code serviceName}.
     *
     * @return a ChangeSet; {@link ChangeSet#isNoop()} if a connector with the
     *         same name is already fully wired.
     */
    public static ChangeSet addConnector(Path appRoot,
                                         String serviceName,
                                         String connectorName,
                                         boolean dryRun) throws IOException {
        Ctx c = Ctx.resolve(appRoot, serviceName, connectorName);

        ChangeSet.Builder cs = ChangeSet.builder();
        boolean changed = false;

        // (1) Connector Java class.
        boolean classExists = Files.exists(c.javaFile);
        if (!classExists) {
            if (!dryRun) writeConnectorClass(c);
            cs.addCreated(c.javaFile);
            changed = true;
        }

        // (2) + (3) config.xml: bus binding and app messaging reference.
        Document doc = loadConfig(c.configPath);
        boolean configChanged = false;

        if (findBusBinding(doc, c.busName) == null) {
            addBusBinding(doc, c);
            configChanged = true;
        }
        if (addAppBusReference(doc, c)) {
            configChanged = true;
        }

        if (configChanged) {
            cs.addModified(c.configPath);
            changed = true;
            if (!dryRun) saveConfig(doc, c.configPath);
        }

        if (!changed) {
            return ChangeSet.noop("connector '" + connectorName + "' already present on service " + serviceName);
        }
        return cs.applied(!dryRun).build();
    }

    /**
     * Remove the named connector from the service, reverting all three
     * artifacts. No-op when the connector is absent.
     */
    public static ChangeSet removeConnector(Path appRoot,
                                            String serviceName,
                                            String connectorName,
                                            boolean dryRun) throws IOException {
        Ctx c = Ctx.resolve(appRoot, serviceName, connectorName);

        ChangeSet.Builder cs = ChangeSet.builder();
        boolean changed = false;

        // (1) Java class.
        if (Files.exists(c.javaFile)) {
            if (!dryRun) Files.delete(c.javaFile);
            cs.addDeleted(c.javaFile);
            changed = true;
        }

        // (2) + (3) config.xml.
        Document doc = loadConfig(c.configPath);
        boolean configChanged = false;

        Element binding = findBusBinding(doc, c.busName);
        if (binding != null) {
            XmlDomUtils.removeElement(binding);
            configChanged = true;
        }
        if (removeAppBusReference(doc, c)) {
            configChanged = true;
        }

        if (configChanged) {
            cs.addModified(c.configPath);
            changed = true;
            if (!dryRun) saveConfig(doc, c.configPath);
        }

        if (!changed) {
            return ChangeSet.noop("no connector named '" + connectorName + "' on service " + serviceName);
        }
        return cs.applied(!dryRun).build();
    }

    // --- internal: config helpers -------------------------------------

    private static Document loadConfig(Path configPath) throws IOException {
        if (!Files.exists(configPath)) {
            throw new IOException("config.xml not found at " + configPath);
        }
        try {
            return XmlDomUtils.parseXmlDocument(configPath);
        } catch (Exception e) {
            throw new IOException("failed to parse " + configPath, e);
        }
    }

    private static void saveConfig(Document doc, Path configPath) throws IOException {
        try {
            XmlDomUtils.saveXmlDocument(doc, configPath);
        } catch (Exception e) {
            throw new IOException("failed to write " + configPath, e);
        }
    }

    /** Find the {@code <bus name=...>} binding under {@code <buses>}, or null. */
    private static Element findBusBinding(Document doc, String busName) {
        Element buses = XmlDomUtils.getElementByPath(doc.getDocumentElement(),
            java.util.Collections.singletonList("buses"));
        if (buses == null) return null;
        return firstChildBusNamed(buses, busName);
    }

    private static void addBusBinding(Document doc, Ctx c) {
        Element root = doc.getDocumentElement();
        Element buses = XmlDomUtils.getOrCreateChild(root, "buses");

        // Create elements without a namespace to match the config fragments
        // that ConfigInjector injects (the connector-service bus binding etc.),
        // keeping the serialized output consistent. Rumi's X-DDL parser matches
        // by local name and is namespace-insensitive here.
        Element bus = doc.createElement("bus");
        bus.setAttribute("descriptor", ConnectorSupport.descriptor(c.className, ConnectorSupport.INBOUND_CHANNEL));
        bus.setAttribute("name", c.busName);
        Element channels = doc.createElement("channels");
        Element channel = doc.createElement("channel");
        channel.setAttribute("name", ConnectorSupport.INBOUND_CHANNEL);
        channel.setAttribute("id", "1");
        Element qos = doc.createElement("qos");
        qos.setTextContent("BestEffort");
        channel.appendChild(qos);
        channels.appendChild(channel);
        bus.appendChild(channels);
        buses.appendChild(bus);
    }

    /** Add {@code <bus name=busName/>} to the service app template's messaging. Returns true if added. */
    private static boolean addAppBusReference(Document doc, Ctx c) {
        Element app = findAppTemplate(doc, c.appTemplateName);
        if (app == null) {
            throw new IllegalStateException(
                "service app template '" + c.appTemplateName + "' not found in config.xml; cannot wire connector");
        }
        Element messaging = XmlDomUtils.getOrCreateChild(app, "messaging");
        if (firstChildBusNamed(messaging, c.busName) != null) return false;
        Element ref = doc.createElement("bus");
        ref.setAttribute("name", c.busName);
        messaging.appendChild(ref);
        return true;
    }

    /** Remove the {@code <bus name=busName/>} reference from the app messaging. Returns true if removed. */
    private static boolean removeAppBusReference(Document doc, Ctx c) {
        Element app = findAppTemplate(doc, c.appTemplateName);
        if (app == null) return false;
        Element messaging = XmlDomUtils.getElementByPath(app,
            java.util.Collections.singletonList("messaging"));
        if (messaging == null) return false;
        Element ref = firstChildBusNamed(messaging, c.busName);
        if (ref == null) return false;
        XmlDomUtils.removeElement(ref);
        return true;
    }

    /** Locate the {@code <app name=...>} element under {@code <apps><templates>}. */
    private static Element findAppTemplate(Document doc, String appName) {
        Element templates = XmlDomUtils.getElementByPath(doc.getDocumentElement(),
            Arrays.asList("apps", "templates"));
        if (templates == null) return null;
        NodeList kids = templates.getChildNodes();
        for (int i = 0; i < kids.getLength(); i++) {
            Node n = kids.item(i);
            if (n.getNodeType() == Node.ELEMENT_NODE
                    && "app".equals(n.getLocalName())
                    && appName.equals(((Element) n).getAttribute("name"))) {
                return (Element) n;
            }
        }
        return null;
    }

    private static Element firstChildBusNamed(Element parent, String busName) {
        NodeList kids = parent.getChildNodes();
        for (int i = 0; i < kids.getLength(); i++) {
            Node n = kids.item(i);
            if (n.getNodeType() == Node.ELEMENT_NODE
                    && "bus".equals(n.getLocalName())
                    && busName.equals(((Element) n).getAttribute("name"))) {
                return (Element) n;
            }
        }
        return null;
    }

    // --- internal: connector class generation -------------------------

    private static void writeConnectorClass(Ctx c) throws IOException {
        Path snippetDir = TemplateProcessor.extractTemplateDirectory(
            "rumi-connector-snippet", "templates/maven/snippets/connector", false);
        Path snippetFile;
        try (Stream<Path> walk = Files.walk(snippetDir)) {
            snippetFile = walk.filter(Files::isRegularFile).findFirst()
                .orElseThrow(() -> new IOException("connector snippet template not found"));
        }
        String content = TemplateProcessor.applyTokens(Files.readString(snippetFile), c.tokens);
        Files.createDirectories(c.javaFile.getParent());
        Files.writeString(c.javaFile, content);
    }

    // --- internal: derived context ------------------------------------

    /** Everything derived once from (appRoot, serviceName, connectorName). */
    private static final class Ctx {
        final Path configPath;
        final Path javaFile;
        final String className;
        final String busName;
        final String appTemplateName;
        final Map<String, String> tokens;

        private Ctx(Path configPath, Path javaFile, String className, String busName,
                    String appTemplateName, Map<String, String> tokens) {
            this.configPath = configPath;
            this.javaFile = javaFile;
            this.className = className;
            this.busName = busName;
            this.appTemplateName = appTemplateName;
            this.tokens = tokens;
        }

        static Ctx resolve(Path appRoot, String serviceName, String connectorName) throws IOException {
            if (connectorName == null || connectorName.isBlank()) {
                throw new IllegalArgumentException("connector name cannot be null or empty");
            }
            ApplicationBuilder.AppParams params = AppIntrospector.loadAppParams(appRoot);
            Path moduleDir = AppIntrospector.resolveServiceModuleDir(appRoot, serviceName);
            if (!Files.isDirectory(moduleDir)) {
                throw new IllegalArgumentException("service '" + serviceName + "' not found under " + appRoot);
            }

            Map<String, String> tokens = new HashMap<>(params.getTokenMap());
            String serviceKebab = TokenUtils.toKebabCase(serviceName);
            String connectorKebab = TokenUtils.toKebabCase(connectorName);
            String connectorClass = TokenUtils.toPascalCase(connectorName);
            String appPkgName = tokens.get(TokenUtils.toToken("AppPackageName"));
            String svcPkgName = TokenUtils.toPackagePath(serviceKebab);
            String serviceFullName = ConnectorSupport.serviceFullName(tokens, serviceKebab);
            String busName = ConnectorSupport.busName(serviceFullName, connectorKebab);
            String className = ConnectorSupport.connectorPackage(appPkgName, serviceKebab) + "." + connectorClass;

            // Tokens consumed by the connector snippet template.
            tokens.put(TokenUtils.toToken("ServicePackageName"), svcPkgName);
            tokens.put(TokenUtils.toToken("ServicePackagePath"), TokenUtils.toSlashCase(serviceKebab));
            tokens.put(TokenUtils.toToken("ServiceName"), serviceFullName);
            tokens.put(TokenUtils.toToken("ConnectorClassName"), connectorClass);
            tokens.put(TokenUtils.toToken("ConnectorTokenName"), connectorKebab);

            Path javaFile = AppIntrospector.resolveMainJavaFile(appRoot, serviceName)
                    .getParent().resolve("connector").resolve(connectorClass + ".java");
            Path configPath = ConnectorSupport.configPath(appRoot, tokens);

            return new Ctx(configPath, javaFile, className, busName, serviceFullName + "-template", tokens);
        }
    }
}
