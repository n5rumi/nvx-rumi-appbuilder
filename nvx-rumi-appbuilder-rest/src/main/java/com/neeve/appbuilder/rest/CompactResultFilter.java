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
import com.neeve.appbuilder.model.ServiceInfo;
import com.neeve.appbuilder.rest.dto.CompactReceipt;
import com.neeve.appbuilder.rest.dto.ServiceInfoView;
import jakarta.ws.rs.container.ContainerRequestContext;
import jakarta.ws.rs.container.ContainerResponseContext;
import jakarta.ws.rs.container.ContainerResponseFilter;
import jakarta.ws.rs.ext.Provider;

import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.function.UnaryOperator;

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

    /** Query parameter that opts out. Declared on every affected endpoint. */
    public static final String DETAIL_PARAM = "detail";


    @Override
    public void filter(ContainerRequestContext request, ContainerResponseContext response) {
        Object entity = response.getEntity();
        if (entity == null) return;
        boolean isReceipt = entity instanceof ChangeSet || entity instanceof BatchResult;
        if (!isReceipt && !(entity instanceof ServiceInfo)) return;

        if (Boolean.parseBoolean(request.getUriInfo().getQueryParameters().getFirst(DETAIL_PARAM))) {
            return;   // the untouched SDK object: every key, absolute paths
        }
        String appRoot = request.getUriInfo().getQueryParameters().getFirst("app_root");
        if (appRoot == null || appRoot.isBlank()) return;

        Path root;
        try {
            root = Paths.get(appRoot).toAbsolutePath().normalize();
        } catch (Exception e) {
            return;   // an unparseable app_root is the resource's problem, not ours
        }
        UnaryOperator<Path> shorten = p -> shorten(p, root);

        if (entity instanceof ChangeSet) {
            response.setEntity(CompactReceipt.of((ChangeSet) entity, shorten));
        } else if (entity instanceof BatchResult) {
            response.setEntity(CompactReceipt.of((BatchResult) entity, shorten));
        } else {
            // ServiceInfo.moduleDir is the app root again, on the endpoint that
            // starts every build. "Every write" was not true without this.
            response.setEntity(ServiceInfoView.of((ServiceInfo) entity, shorten));
        }
    }

    private static Path shorten(Path p, Path root) {
        Path abs = p.toAbsolutePath().normalize();
        // ABS, not the raw input, when it falls outside the root. A relative
        // input returned unchanged is indistinguishable from one made relative
        // to app_root, so the caller resolves the wrong file; and a path that
        // only normalizes outside the root would keep the ".." segments this is
        // meant to avoid.
        return abs.startsWith(root) ? root.relativize(abs) : abs;
    }
}
