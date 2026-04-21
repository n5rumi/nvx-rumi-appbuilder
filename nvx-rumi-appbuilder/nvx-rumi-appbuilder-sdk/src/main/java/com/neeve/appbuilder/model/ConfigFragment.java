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
import java.util.List;
import java.util.Objects;

/**
 * A single config-fragment entry scoped under an X-DDL scope path such as
 * {@code apps/templates}, {@code xvms/templates},
 * {@code profiles/cloud/apps/templates}, {@code env}, or {@code buses}.
 *
 * <p>Fragments are whatever direct-element children live under the scope
 * path. For the templates scopes the fragment tag is {@code app} or
 * {@code xvm}; for {@code env}/{@code buses} it can be any element name
 * the X-DDL schema allows.
 */
public final class ConfigFragment {
    private final List<String> scopePath;
    private final String tagName;
    private final String name;  // value of the "name" attribute if present, else null
    private final Element element;

    public ConfigFragment(List<String> scopePath, String tagName, String name, Element element) {
        this.scopePath = Collections.unmodifiableList(Objects.requireNonNull(scopePath, "scopePath"));
        this.tagName = Objects.requireNonNull(tagName, "tagName");
        this.name = name;
        this.element = Objects.requireNonNull(element, "element");
    }

    /** The scope path segments from the root of {@code config.xml}. */
    public List<String> getScopePath() {
        return scopePath;
    }

    /** Local element name of the fragment (e.g. {@code app}, {@code xvm}, {@code bus}). */
    public String getTagName() {
        return tagName;
    }

    /** Value of the fragment's {@code name} attribute, or null if not set. */
    public String getName() {
        return name;
    }

    /** The underlying DOM element — live, owned by the config document the caller loaded. */
    public Element getElement() {
        return element;
    }

    @Override
    public String toString() {
        return "ConfigFragment{scope=" + String.join("/", scopePath)
             + ", tag=" + tagName
             + ", name=" + name + "}";
    }
}
