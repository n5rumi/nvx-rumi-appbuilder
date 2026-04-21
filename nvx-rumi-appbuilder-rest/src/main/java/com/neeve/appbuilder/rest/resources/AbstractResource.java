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

import java.nio.file.Path;

/**
 * Shared base class for App Builder REST resources.
 *
 * <p>Today the class is minimal — shared helpers land here as the
 * endpoint implementations (RUMI-301) discover common needs. Intentional
 * future home for: app-root path resolution, dry-run flag handling,
 * common error-response helpers, and any HK2-injected SDK singletons.
 *
 * <p>Modelled on Datafye API REST's {@code AbstractResource}.
 */
public abstract class AbstractResource {

    /**
     * Parse an {@code appRoot} path-param into a {@link Path} and assert
     * it is absolute. Relative paths can't reliably resolve from the
     * service's working directory, so we reject them early with a clear
     * error instead of letting the SDK fail deeper with a misleading
     * stack trace.
     */
    protected static Path requireAbsoluteAppRoot(String appRoot) {
        if (appRoot == null || appRoot.isBlank()) {
            throw new IllegalArgumentException("appRoot is required");
        }
        Path p = Path.of(appRoot);
        if (!p.isAbsolute()) {
            throw new IllegalArgumentException("appRoot must be an absolute path: " + appRoot);
        }
        return p;
    }
}
