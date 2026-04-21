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

import org.w3c.dom.Element;

import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Objects;

/**
 * Simple element-matching selector used by
 * {@link com.neeve.appbuilder.ConfigFragmentEditor#removeFragment} to
 * identify which direct-child element of a scope path to remove.
 *
 * <p>A selector matches an element when:
 *
 * <ul>
 *   <li>The element's local name equals {@link #getTagName()} (or
 *       {@code tagName} is null to match any local name), and
 *   <li>Every {@code (name, value)} pair in
 *       {@link #getAttributeMatches()} is present on the element with the
 *       specified value.
 * </ul>
 *
 * <p>Starts intentionally minimal. XPath, regex matching, nested
 * conditions — we add those only when a concrete use case surfaces.
 */
public final class ElementSelector {
    private final String tagName;
    private final Map<String, String> attributeMatches;

    public ElementSelector(String tagName, Map<String, String> attributeMatches) {
        this.tagName = tagName;
        this.attributeMatches = Collections.unmodifiableMap(new LinkedHashMap<>(
            Objects.requireNonNull(attributeMatches, "attributeMatches")));
    }

    /** Match by tag name only (no attribute constraints). */
    public static ElementSelector byTag(String tagName) {
        return new ElementSelector(tagName, Collections.emptyMap());
    }

    /** Match by {@code name="..."} attribute, any tag. */
    public static ElementSelector byName(String name) {
        Map<String, String> attrs = new LinkedHashMap<>();
        attrs.put("name", name);
        return new ElementSelector(null, attrs);
    }

    /** Match by both tag and {@code name="..."} attribute. */
    public static ElementSelector byTagAndName(String tagName, String name) {
        Map<String, String> attrs = new LinkedHashMap<>();
        attrs.put("name", name);
        return new ElementSelector(tagName, attrs);
    }

    public String getTagName() {
        return tagName;
    }

    public Map<String, String> getAttributeMatches() {
        return attributeMatches;
    }

    /** True if this selector matches the element. */
    public boolean matches(Element element) {
        if (tagName != null && !tagName.equals(element.getLocalName())) {
            return false;
        }
        for (Map.Entry<String, String> e : attributeMatches.entrySet()) {
            String actual = element.getAttribute(e.getKey());
            if (actual == null || !actual.equals(e.getValue())) {
                return false;
            }
        }
        return true;
    }

    @Override
    public String toString() {
        return "ElementSelector{tag=" + tagName + ", attrs=" + attributeMatches + "}";
    }
}
