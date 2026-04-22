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
package com.neeve.appbuilder.rest.resources;

/**
 * Holder for the five substitutions that get inlined into the Swagger
 * UI HTML template: the raw HTML, the raw CSS, the two JS bundles, and
 * the URL where the OpenAPI YAML can be fetched.
 *
 * <p>Plain getters; no Lombok dependency for a single holder class.
 */
public final class SwaggerUIModel {
    private final String html;
    private final String css;
    private final String jsBundle;
    private final String jsPreset;
    private final String yaml;

    private SwaggerUIModel(Builder b) {
        this.html = b.html;
        this.css = b.css;
        this.jsBundle = b.jsBundle;
        this.jsPreset = b.jsPreset;
        this.yaml = b.yaml;
    }

    public String getHtml() { return html; }
    public String getCss() { return css; }
    public String getJsBundle() { return jsBundle; }
    public String getJsPreset() { return jsPreset; }
    public String getYaml() { return yaml; }

    public static Builder builder() { return new Builder(); }

    public static final class Builder {
        private String html, css, jsBundle, jsPreset, yaml;

        public Builder html(String v) { this.html = v; return this; }
        public Builder css(String v) { this.css = v; return this; }
        public Builder jsBundle(String v) { this.jsBundle = v; return this; }
        public Builder jsPreset(String v) { this.jsPreset = v; return this; }
        public Builder yaml(String v) { this.yaml = v; return this; }
        public SwaggerUIModel build() { return new SwaggerUIModel(this); }
    }
}
