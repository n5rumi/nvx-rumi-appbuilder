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
package com.neeve.appbuilder.rest;

import org.junit.Test;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.List;
import java.util.Set;
import java.util.TreeSet;
import java.util.stream.Collectors;
import java.util.stream.Stream;

import static org.junit.Assert.*;

/**
 * A resource class that is written but never registered serves 404 with no
 * error anywhere — the service starts clean and the endpoint simply is not
 * there. Registration is explicit here (deliberately, so the API surface is
 * auditable in one place), which is exactly the arrangement where adding a
 * resource and forgetting the register() line is easy.
 *
 * <p>Added with RUMI-412's {@code Model} resource, which came within one line
 * of shipping unregistered.
 */
public class ResourceRegistrationTest {

    @Test
    public void everyResourceClassIsRegisteredInTheResourceConfig() throws IOException {
        Set<String> onDisk = resourceClassNames();
        assertFalse("found no resource classes at all — the discovery is broken, "
            + "not the registration", onDisk.isEmpty());

        Set<String> registered = new Main.ResourceConfig().getClasses().stream()
            .map(Class::getSimpleName)
            .collect(Collectors.toCollection(TreeSet::new));

        Set<String> missing = new TreeSet<>(onDisk);
        missing.removeAll(registered);

        assertTrue("these @Path resource classes exist but are not registered in "
            + "Main.ResourceConfig, so their endpoints would 404 silently:\n  "
            + String.join("\n  ", missing), missing.isEmpty());
    }

    /** Simple names of every {@code @Path}-annotated class under resources/. */
    private static Set<String> resourceClassNames() throws IOException {
        Path dir = sourceDir();
        try (Stream<Path> files = Files.list(dir)) {
            List<Path> java = files.filter(p -> p.toString().endsWith(".java")).collect(Collectors.toList());
            Set<String> out = new TreeSet<>();
            for (Path p : java) {
                String src = Files.readString(p);
                // A resource is a class carrying a type-level @Path. AbstractResource
                // has none, which is what keeps it out of this set.
                if (src.contains("\n@Path(") || src.contains("\n@Path (")) {
                    String n = p.getFileName().toString();
                    out.add(n.substring(0, n.length() - ".java".length()));
                }
            }
            return out;
        }
    }

    private static Path sourceDir() {
        Path rel = Paths.get("src/main/java/com/neeve/appbuilder/rest/resources");
        Path fromModule = Paths.get(System.getProperty("user.dir")).resolve(rel);
        if (Files.isDirectory(fromModule)) return fromModule;
        // Surefire can run from the reactor root as well as the module dir.
        return Paths.get(System.getProperty("user.dir"))
            .resolve("nvx-rumi-appbuilder-rest").resolve(rel);
    }
}
