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
import com.neeve.appbuilder.model.FieldDef;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;

/**
 * Add and remove {@code <entity>} declarations in a service's state.xml.
 * The state-only facade over {@link EntityEditor}: it fixes the scope to
 * {@link FieldEditor.ModelScope#SERVICE_STATE} and adds a
 * processor-friendly error when the service has no state model. Only processor
 * and webservice services have a state.xml; calling on a driver or connector
 * throws because there's nothing to edit.
 *
 * <p>Entity IDs are local to the service's state factory; allocation and id
 * retirement (never-reuse tombstones) are handled by {@link EntityEditor} /
 * {@link ModelIdAllocator}.
 */
public final class StateEditor {
    private StateEditor() {}

    /**
     * Add an {@code <entity>} declaration to the service's state.xml.
     *
     * @return a ChangeSet; {@link ChangeSet#isNoop()} if an entity with
     *         the same name already exists.
     */
    public static ChangeSet addStateEntity(Path appRoot,
                                           String serviceName,
                                           String entityName,
                                           List<FieldDef> fields,
                                           boolean dryRun) throws IOException {
        requireStateModel(appRoot, serviceName);
        return EntityEditor.addEntity(appRoot, serviceName,
            FieldEditor.ModelScope.SERVICE_STATE, entityName, fields, dryRun);
    }

    /**
     * Remove the named {@code <entity>} from state.xml. The entity's id is
     * retired via a tombstone so it is never reused.
     */
    public static ChangeSet removeStateEntity(Path appRoot,
                                              String serviceName,
                                              String entityName,
                                              boolean dryRun) throws IOException {
        requireStateModel(appRoot, serviceName);
        return EntityEditor.removeEntity(appRoot, serviceName,
            FieldEditor.ModelScope.SERVICE_STATE, entityName, dryRun);
    }

    // --- internal -----------------------------------------------------

    /** Fail with a processor-friendly message when the service has no state model. */
    private static void requireStateModel(Path appRoot, String serviceName) throws IOException {
        Path stateXml = AppIntrospector.resolveStateXmlFile(appRoot, serviceName);
        if (!Files.exists(stateXml)) {
            throw new IOException("state.xml not found at " + stateXml
                + " (is this a processor service?)");
        }
    }
}
