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
import java.io.InputStream;
import java.util.Properties;

/**
 * The versions this builder was BUILT against, read from a Maven-filtered
 * resource rather than written as literals in Java (RUMI-443).
 *
 * <p>A scaffolded app has to pin a Rumi Management version, because its
 * generated {@code -system} module runs {@code nvx-rumi-xar-maven-plugin} at
 * that version. The default used to be the literal {@code "2.0.0"} in two
 * independent places — {@code AppParamsRequest.toSdk} in the REST layer and
 * {@link com.neeve.appbuilder.test.TestAppFactory} here — and that version was
 * never published. The failure shape is the expensive one: the scaffold
 * succeeds, the app compiles, and it dies minutes later in PACKAGING with
 * {@code Plugin com.neeve:nvx-rumi-xar-maven-plugin:2.0.0 could not be
 * resolved}, which reads as a broken repository and points nowhere near
 * {@code create_app}.
 *
 * <p><b>It lives in the SDK, not in REST, on purpose.</b> Both the REST layer
 * and the test fixture need this default, and two copies of a value whose whole
 * purpose is "cannot drift" is precisely how it drifts — which is what the two
 * independent {@code "2.0.0"} literals already demonstrated.
 *
 * <p>⚠️ {@link #managementVersion()} is deliberately allowed to throw rather
 * than fall back. A missing or unsubstituted value means the build is
 * misconfigured, and a literal fallback here would reinstate the defect in the
 * one place guaranteed to be reached.
 */
public final class BuildVersions {

    private static final String RESOURCE = "/appbuilder-versions.properties";
    private static final String MGMT_KEY = "nvx.rumi.management.version";
    private static final String RUMI_KEY = "nvx.rumi.version";

    private static final Properties PROPS = load();

    private BuildVersions() {}

    private static Properties load() {
        final Properties p = new Properties();
        try (InputStream in = BuildVersions.class.getResourceAsStream(RESOURCE)) {
            if (in != null) {
                p.load(in);
            }
        }
        catch (IOException e) {
            // Left empty on purpose: managementVersion() reports the real
            // problem with the detail a reader can act on.
        }
        return p;
    }

    private static String require(final String key) {
        final String v = PROPS.getProperty(key);
        if (v == null || v.trim().isEmpty() || v.contains("${")) {
            throw new IllegalStateException(
                "the App Builder was built without a usable " + key + " (got "
                + (v == null ? "nothing" : "'" + v + "'") + " from " + RESOURCE
                + "). Either resource filtering for appbuilder-versions.properties "
                + "is missing from nvx-rumi-appbuilder-sdk/pom.xml, or the root POM "
                + "no longer defines the property. Pass the version explicitly "
                + "until the build is fixed.");
        }
        return v.trim();
    }

    /**
     * The Rumi Management version a scaffolded app is pinned to when the caller
     * names none. Always a version the build pinned, never a literal.
     */
    public static String managementVersion() {
        return require(MGMT_KEY);
    }

    /** The Rumi version this builder was built against. */
    public static String rumiVersion() {
        return require(RUMI_KEY);
    }
}
