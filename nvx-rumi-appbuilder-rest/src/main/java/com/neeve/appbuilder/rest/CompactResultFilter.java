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

import com.neeve.appbuilder.model.BatchResult;
import com.neeve.appbuilder.model.ChangeSet;
import jakarta.ws.rs.container.ContainerRequestContext;
import jakarta.ws.rs.container.ContainerResponseContext;
import jakarta.ws.rs.container.ContainerResponseFilter;
import jakarta.ws.rs.ext.Provider;

import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;

/**
 * Shorten a write receipt to what the caller did not already know (RUMI-414).
 *
 * <p>Every write returned the app root back to the caller who had just supplied
 * it, once per touched file and fully qualified. On a real project that was
 * about two thirds of the receipt:
 *
 * <pre>
 * {"applied":true,"filesCreated":[],
 *  "filesModified":["/home/rumi/.sutra/agent/projects/proj-1db6ca18c244/prospect-portal/
 *                   prospect-portal-roe/src/main/models/com/prospect/portal/roe/messages.xml"],
 *  "filesDeleted":[],"factoryIdsReserved":[],"factoryIdsReleased":[],
 *  "noop":false,"reason":null}
 * </pre>
 *
 * <p>Paths are made relative to the {@code app_root} the request named; the
 * empty collections and the null reason are dropped by the Jackson config. Pass
 * {@code detail=true} for the original absolute form.
 *
 * <p>⚠️ <b>Sizing this honestly: it is worth about 0.4% of a session's token
 * bill.</b> The lever that mattered was the number of calls, not the size of
 * each — see RUMI-412. This is here because it is nearly free and because a
 * receipt that repeats the caller's own input is untidy, not because it moves
 * the number.
 *
 * <p>A filter rather than a change in each resource: there are a dozen
 * endpoints returning a {@link ChangeSet} and one returning a
 * {@link BatchResult}, and a rule applied in twelve places is a rule that will
 * eventually be applied in eleven.
 */
@Provider
public class CompactResultFilter implements ContainerResponseFilter {

    @Override
    public void filter(ContainerRequestContext request, ContainerResponseContext response) {
        Object entity = response.getEntity();
        if (entity == null) return;
        if (!(entity instanceof ChangeSet) && !(entity instanceof BatchResult)) return;

        if (Boolean.parseBoolean(request.getUriInfo().getQueryParameters().getFirst("detail"))) {
            return;
        }
        String appRoot = request.getUriInfo().getQueryParameters().getFirst("app_root");
        if (appRoot == null || appRoot.isBlank()) return;

        Path root;
        try {
            root = Paths.get(appRoot).toAbsolutePath().normalize();
        } catch (Exception e) {
            return;   // an unparseable app_root is the resource's problem, not ours
        }

        if (entity instanceof ChangeSet) {
            response.setEntity(relativize((ChangeSet) entity, root));
        } else {
            response.setEntity(relativize((BatchResult) entity, root));
        }
    }

    private static ChangeSet relativize(ChangeSet cs, Path root) {
        ChangeSet.Builder b = ChangeSet.builder()
            .applied(cs.isApplied())
            .noop(cs.isNoop())
            .reason(cs.getReason())
            .filesCreated(relativize(cs.getFilesCreated(), root))
            .filesModified(relativize(cs.getFilesModified(), root))
            .filesDeleted(relativize(cs.getFilesDeleted(), root));
        for (Integer id : cs.getFactoryIdsReserved()) b.addFactoryIdReserved(id);
        for (Integer id : cs.getFactoryIdsReleased()) b.addFactoryIdReleased(id);
        return b.build();
    }

    private static BatchResult relativize(BatchResult r, Path root) {
        return new BatchResult(r.getItems(),
                               new LinkedHashSet<>(relativize(r.getFilesModified(), root)),
                               r.isApplied());
    }

    /**
     * Relative to {@code root} where the file is under it, unchanged otherwise.
     *
     * <p>Unchanged rather than forced: a path outside the app root cannot be
     * expressed relative to it without {@code ../..} segments, which are longer
     * than the absolute path and harder to read. Nothing should produce one, but
     * silently mangling it if something did would be worse than leaving it.
     */
    private static List<Path> relativize(List<Path> paths, Path root) {
        List<Path> out = new ArrayList<>(paths.size());
        for (Path p : paths) {
            Path abs = p.toAbsolutePath().normalize();
            out.add(abs.startsWith(root) ? root.relativize(abs) : p);
        }
        return out;
    }
}
