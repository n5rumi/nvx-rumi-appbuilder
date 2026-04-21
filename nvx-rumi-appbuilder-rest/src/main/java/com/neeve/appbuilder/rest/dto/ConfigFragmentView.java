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

import com.neeve.appbuilder.model.ConfigFragment;
import org.w3c.dom.Element;

import javax.xml.transform.OutputKeys;
import javax.xml.transform.Transformer;
import javax.xml.transform.TransformerFactory;
import javax.xml.transform.dom.DOMSource;
import javax.xml.transform.stream.StreamResult;
import java.io.StringWriter;
import java.util.List;

/**
 * Response view of a {@link ConfigFragment}. The SDK type exposes an
 * {@code org.w3c.dom.Element} which Jackson can't serialize cleanly; we
 * render it to an XML string here so callers get a self-contained JSON
 * value they can diff, log, or re-submit via
 * {@link AddConfigFragmentRequest}.
 */
public final class ConfigFragmentView {
    private final List<String> scopePath;
    private final String tagName;
    private final String name;
    private final String xml;

    public ConfigFragmentView(List<String> scopePath, String tagName, String name, String xml) {
        this.scopePath = scopePath;
        this.tagName = tagName;
        this.name = name;
        this.xml = xml;
    }

    public List<String> getScopePath() { return scopePath; }
    public String getTagName() { return tagName; }
    public String getName() { return name; }
    public String getXml() { return xml; }

    public static ConfigFragmentView from(ConfigFragment f) {
        return new ConfigFragmentView(f.getScopePath(), f.getTagName(), f.getName(), elementToXml(f.getElement()));
    }

    private static String elementToXml(Element element) {
        if (element == null) return null;
        try {
            StringWriter out = new StringWriter();
            Transformer t = TransformerFactory.newInstance().newTransformer();
            t.setOutputProperty(OutputKeys.OMIT_XML_DECLARATION, "yes");
            t.setOutputProperty(OutputKeys.INDENT, "no");
            t.transform(new DOMSource(element), new StreamResult(out));
            return out.toString();
        } catch (Exception e) {
            return "<!-- serialization failed: " + e.getMessage() + " -->";
        }
    }
}
