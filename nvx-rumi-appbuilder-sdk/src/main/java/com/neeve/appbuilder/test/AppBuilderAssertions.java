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
package com.neeve.appbuilder.test;

import com.neeve.appbuilder.AppIntrospector;
import com.neeve.appbuilder.ConfigIntrospector;
import com.neeve.appbuilder.FactoryIdCollector;
import com.neeve.appbuilder.HandlerIntrospector;
import com.neeve.appbuilder.MessageIntrospector;
import com.neeve.appbuilder.ServiceIntrospector;
import com.neeve.appbuilder.StateIntrospector;
import com.neeve.appbuilder.model.ConfigFragment;
import com.neeve.appbuilder.model.ElementSelector;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;

/**
 * Assertion helpers for App Builder integration tests. Each helper wraps
 * the relevant introspector and throws {@link AssertionError} on
 * violation — framework-agnostic, usable from JUnit 4, JUnit 5, TestNG,
 * or a bare {@code main}.
 *
 * <p>Paired with {@link TestAppFactory}. Covers the objects introspectors
 * return (apps, services, handlers, messages, state entities, config
 * fragments, factory IDs) with symmetric {@code assertXExists} /
 * {@code assertXAbsent} methods.
 */
public final class AppBuilderAssertions {
    private AppBuilderAssertions() {}

    // ---- Apps --------------------------------------------------------

    public static void assertIsRumiApp(Path appRoot) throws IOException {
        if (!Files.exists(appRoot.resolve(".rumi"))) {
            throw new AssertionError("expected .rumi at " + appRoot + " but not found");
        }
    }

    public static void assertNotRumiApp(Path appRoot) {
        if (Files.exists(appRoot.resolve(".rumi"))) {
            throw new AssertionError("expected no .rumi at " + appRoot + " but found one");
        }
    }

    // ---- Services ----------------------------------------------------

    public static void assertServiceExists(Path appRoot, String serviceName) throws IOException {
        if (ServiceIntrospector.getService(appRoot, serviceName) == null) {
            throw new AssertionError("expected service '" + serviceName + "' in " + appRoot
                + " but ServiceIntrospector.getService returned null. Known services: "
                + ServiceIntrospector.listServiceNames(appRoot));
        }
    }

    public static void assertServiceAbsent(Path appRoot, String serviceName) throws IOException {
        if (ServiceIntrospector.getService(appRoot, serviceName) != null) {
            throw new AssertionError("expected service '" + serviceName + "' to be absent from "
                + appRoot + " but it was found");
        }
    }

    // ---- Handlers ----------------------------------------------------

    public static void assertHandlerExists(Path appRoot, String serviceName, String methodName) throws IOException {
        if (HandlerIntrospector.getHandler(appRoot, serviceName, methodName) == null) {
            throw new AssertionError("expected handler '" + methodName + "' on service '"
                + serviceName + "' but HandlerIntrospector.getHandler returned null");
        }
    }

    public static void assertHandlerAbsent(Path appRoot, String serviceName, String methodName) throws IOException {
        if (HandlerIntrospector.getHandler(appRoot, serviceName, methodName) != null) {
            throw new AssertionError("expected handler '" + methodName + "' to be absent from service '"
                + serviceName + "' but it was found");
        }
    }

    // ---- Messages ----------------------------------------------------

    public static void assertMessageExists(Path appRoot, String serviceName, String messageName) throws IOException {
        if (MessageIntrospector.getMessage(appRoot, serviceName, messageName) == null) {
            throw new AssertionError("expected message '" + messageName + "' on service '"
                + serviceName + "' but MessageIntrospector.getMessage returned null");
        }
    }

    public static void assertMessageAbsent(Path appRoot, String serviceName, String messageName) throws IOException {
        if (MessageIntrospector.getMessage(appRoot, serviceName, messageName) != null) {
            throw new AssertionError("expected message '" + messageName + "' to be absent from service '"
                + serviceName + "' but it was found");
        }
    }

    // ---- State entities ---------------------------------------------

    public static void assertStateEntityExists(Path appRoot, String serviceName, String entityName) throws IOException {
        if (StateIntrospector.getStateEntity(appRoot, serviceName, entityName) == null) {
            throw new AssertionError("expected state entity '" + entityName + "' on service '"
                + serviceName + "' but StateIntrospector.getStateEntity returned null");
        }
    }

    public static void assertStateEntityAbsent(Path appRoot, String serviceName, String entityName) throws IOException {
        if (StateIntrospector.getStateEntity(appRoot, serviceName, entityName) != null) {
            throw new AssertionError("expected state entity '" + entityName + "' to be absent from service '"
                + serviceName + "' but it was found");
        }
    }

    // ---- Config fragments -------------------------------------------

    /**
     * Assert at least one fragment at the given scope path matches the
     * selector. Path segments follow the same convention as
     * {@link ConfigIntrospector#listFragments}: {@code ["apps",
     * "templates"]}, {@code ["profiles", "cloud", "env"]}, etc.
     */
    public static void assertConfigFragmentPresent(Path appRoot,
                                                   List<String> scopePath,
                                                   ElementSelector selector) throws IOException {
        List<ConfigFragment> frags = ConfigIntrospector.listFragments(appRoot, null);
        for (ConfigFragment f : frags) {
            if (f.getScopePath().equals(scopePath) && selector.matches(f.getElement())) {
                return;
            }
        }
        throw new AssertionError("no config fragment at scope " + scopePath
            + " matched " + selector);
    }

    public static void assertConfigFragmentAbsent(Path appRoot,
                                                  List<String> scopePath,
                                                  ElementSelector selector) throws IOException {
        List<ConfigFragment> frags = ConfigIntrospector.listFragments(appRoot, null);
        for (ConfigFragment f : frags) {
            if (f.getScopePath().equals(scopePath) && selector.matches(f.getElement())) {
                throw new AssertionError("expected no fragment at scope " + scopePath
                    + " matching " + selector + " but one was found: " + f);
            }
        }
    }

    // ---- Factory IDs ------------------------------------------------

    public static void assertFactoryIdUsed(Path appRoot, int id) throws IOException {
        if (!FactoryIdCollector.listUsedIds(appRoot).containsKey(id)) {
            throw new AssertionError("expected factory id " + id + " to be in use but it isn't. "
                + "Used IDs: " + FactoryIdCollector.listUsedIds(appRoot).keySet());
        }
    }

    public static void assertFactoryIdUnused(Path appRoot, int id) throws IOException {
        if (FactoryIdCollector.listUsedIds(appRoot).containsKey(id)) {
            throw new AssertionError("expected factory id " + id + " to be unused but it is owned by: "
                + FactoryIdCollector.listUsedIds(appRoot).get(id));
        }
    }
}
