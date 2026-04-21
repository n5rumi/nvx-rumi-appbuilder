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

import org.w3c.dom.Document;
import org.w3c.dom.Element;
import org.w3c.dom.NodeList;

import javax.xml.parsers.DocumentBuilder;
import javax.xml.parsers.DocumentBuilderFactory;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Collections;
import java.util.HashMap;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.stream.Stream;

/**
 * Scans the ADML model XML files inside a Rumi app to find which factory
 * IDs (0..{@link Short#MAX_VALUE}) are in use, and offers allocation +
 * verification APIs on top.
 *
 * <p>Factory IDs live in {@code <factory id="...">} elements under the
 * {@code http://www.neeveresearch.com/schema/x-adml} namespace. They must
 * be unique across the whole app — which is why allocation is a
 * scan-the-world operation rather than a local counter.
 *
 * <p>All methods are read-only — none of them write to the filesystem.
 * {@link #release(Path, int)} is named for its effect on the caller's
 * mental model (the ID is "given back" to the pool after the factory
 * element is removed), not for any side effect here.
 */
public final class FactoryIdCollector {
    private static final String MODELS_DIR = "src/main/models";
    private static final String MODEL_NAMESPACE = "http://www.neeveresearch.com/schema/x-adml";
    private static final int MAX_FACTORY_ID = Short.MAX_VALUE;

    private FactoryIdCollector() {}

    /**
     * Collects available factory IDs not currently used in the app. Fills
     * gaps in the allocated range before incrementing past the current max.
     *
     * @param appRoot app root directory (contains the {@code .rumi} file).
     * @param minAvailable minimum number of IDs to return.
     * @return sorted list of available IDs. Always contains at least
     *         {@code minAvailable} entries, or throws if that would exceed
     *         {@link Short#MAX_VALUE}.
     */
    public static List<Integer> collectAvailableFactoryIds(Path appRoot, int minAvailable) throws IOException {
        Set<Integer> usedIds = collectUsedIds(appRoot);

        int maxUsed = usedIds.stream().max(Integer::compareTo).orElse(0);
        List<Integer> available = new ArrayList<>();
        for (int i = 1; i < maxUsed; i++) {
            if (!usedIds.contains(i)) {
                available.add(i);
            }
        }
        int nextId = maxUsed + 1;
        while (available.size() < minAvailable) {
            if (nextId > MAX_FACTORY_ID) {
                throw new IllegalStateException("Exceeded maximum allowable factory-id (" + MAX_FACTORY_ID + ")");
            }
            available.add(nextId++);
        }
        Collections.sort(available);
        return available;
    }

    /**
     * Return the single next available factory ID. Convenience wrapper
     * over {@link #collectAvailableFactoryIds(Path, int)} with
     * {@code minAvailable = 1}. Does not reserve the ID — the caller is
     * responsible for creating the factory element in a model file before
     * allocating again (otherwise both allocations return the same ID).
     */
    public static int nextAvailableId(Path appRoot) throws IOException {
        return collectAvailableFactoryIds(appRoot, 1).get(0);
    }

    /**
     * Return a map from every used factory ID to a human-readable
     * description of its owner. Descriptions are formatted as
     * {@code "<relative-xml-path>: <factory-identifier>"} where the
     * identifier comes from (in priority order) the {@code className},
     * {@code name}, then a fallback of the model file's tag structure.
     *
     * <p>If the same ID appears in multiple factories (shouldn't happen
     * in a well-formed app, but can after a manual edit), the
     * descriptions are joined with {@code " ; "} — the returned map is a
     * diagnostic tool for that kind of corruption.
     *
     * <p>Returns a {@link LinkedHashMap} in ascending ID order for stable
     * display.
     */
    public static Map<Integer, String> listUsedIds(Path appRoot) throws IOException {
        Map<Integer, List<String>> byId = new HashMap<>();

        try (Stream<Path> walk = Files.walk(appRoot)) {
            walk.filter(path -> path.toString().endsWith(".xml"))
                .filter(path -> path.toString().contains(MODELS_DIR))
                .forEach(path -> collectFactoriesFrom(appRoot, path, byId));
        }

        List<Integer> sortedIds = new ArrayList<>(byId.keySet());
        Collections.sort(sortedIds);
        Map<Integer, String> out = new LinkedHashMap<>();
        for (Integer id : sortedIds) {
            out.put(id, String.join(" ; ", byId.get(id)));
        }
        return out;
    }

    /**
     * Verify a factory ID is no longer referenced anywhere in the app. To
     * be called by editors after removing the factory element that owned
     * the ID, as a safety assertion.
     *
     * <p>If any factory still uses the ID — either because the caller
     * hasn't finished removing it or because a different factory has
     * always held the same ID (edge case of corrupted state) — this method
     * throws {@link IllegalStateException} listing the remaining owners.
     *
     * <p>After a successful call the ID is eligible for reuse by
     * {@link #nextAvailableId(Path)}'s gap-fill behaviour. No explicit
     * pool bookkeeping is required; the "pool" is always implicit in the
     * app's model files.
     */
    public static void release(Path appRoot, int id) throws IOException {
        Map<Integer, String> used = listUsedIds(appRoot);
        String owner = used.get(id);
        if (owner != null) {
            throw new IllegalStateException(
                "factory id " + id + " is still referenced — cannot release. Owner(s): " + owner);
        }
    }

    // ---- internal -----------------------------------------------------

    private static Set<Integer> collectUsedIds(Path appRoot) throws IOException {
        Set<Integer> usedIds = new HashSet<>();
        try (Stream<Path> walk = Files.walk(appRoot)) {
            walk.filter(path -> path.toString().endsWith(".xml"))
                .filter(path -> path.toString().contains(MODELS_DIR))
                .forEach(path -> {
                    try {
                        Document doc = parseNamespaceAware(path);
                        Element root = doc.getDocumentElement();
                        if (!MODEL_NAMESPACE.equals(root.getNamespaceURI())) return;
                        NodeList factories = doc.getElementsByTagNameNS(MODEL_NAMESPACE, "factory");
                        for (int i = 0; i < factories.getLength(); i++) {
                            Element factory = (Element) factories.item(i);
                            String idAttr = factory.getAttribute("id");
                            if (idAttr != null && !idAttr.isBlank()) {
                                usedIds.add(Integer.parseInt(idAttr));
                            }
                        }
                    } catch (Exception e) {
                        System.err.println("Skipping invalid model file: " + path + " — " + e.getMessage());
                    }
                });
        }
        return usedIds;
    }

    private static void collectFactoriesFrom(Path appRoot, Path xmlPath, Map<Integer, List<String>> byId) {
        try {
            Document doc = parseNamespaceAware(xmlPath);
            Element root = doc.getDocumentElement();
            if (!MODEL_NAMESPACE.equals(root.getNamespaceURI())) return;
            NodeList factories = doc.getElementsByTagNameNS(MODEL_NAMESPACE, "factory");
            String relative = appRoot.relativize(xmlPath).toString();
            for (int i = 0; i < factories.getLength(); i++) {
                Element factory = (Element) factories.item(i);
                String idAttr = factory.getAttribute("id");
                if (idAttr == null || idAttr.isBlank()) continue;
                int id;
                try {
                    id = Integer.parseInt(idAttr);
                } catch (NumberFormatException e) {
                    continue;
                }
                byId.computeIfAbsent(id, k -> new ArrayList<>()).add(relative + ": " + describe(factory));
            }
        } catch (Exception e) {
            System.err.println("Skipping invalid model file: " + xmlPath + " — " + e.getMessage());
        }
    }

    private static String describe(Element factory) {
        String className = factory.getAttribute("className");
        if (className != null && !className.isBlank()) return className;
        String name = factory.getAttribute("name");
        if (name != null && !name.isBlank()) return name;
        return "<factory>";
    }

    private static Document parseNamespaceAware(Path path) throws Exception {
        DocumentBuilderFactory dbf = DocumentBuilderFactory.newInstance();
        dbf.setNamespaceAware(true);
        DocumentBuilder builder = dbf.newDocumentBuilder();
        return builder.parse(path.toFile());
    }
}
