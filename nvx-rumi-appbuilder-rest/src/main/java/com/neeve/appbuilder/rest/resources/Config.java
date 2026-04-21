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

import com.neeve.appbuilder.ConfigFragmentEditor;
import com.neeve.appbuilder.ConfigIntrospector;
import com.neeve.appbuilder.ConfigValidator;
import com.neeve.appbuilder.model.ChangeSet;
import com.neeve.appbuilder.model.ValidationResult;
import com.neeve.appbuilder.rest.dto.AddConfigFragmentRequest;
import com.neeve.appbuilder.rest.dto.ConfigFragmentView;
import com.neeve.appbuilder.rest.dto.RemoveConfigFragmentRequest;
import jakarta.ws.rs.Consumes;
import jakarta.ws.rs.DELETE;
import jakarta.ws.rs.DefaultValue;
import jakarta.ws.rs.GET;
import jakarta.ws.rs.POST;
import jakarta.ws.rs.Path;
import jakarta.ws.rs.Produces;
import jakarta.ws.rs.QueryParam;
import jakarta.ws.rs.core.MediaType;

import javax.xml.transform.OutputKeys;
import javax.xml.transform.Transformer;
import javax.xml.transform.TransformerFactory;
import javax.xml.transform.dom.DOMSource;
import javax.xml.transform.stream.StreamResult;
import java.io.IOException;
import java.io.StringWriter;
import java.util.List;
import java.util.stream.Collectors;

/**
 * Config-level endpoints: full config retrieval, fragment CRUD, X-DDL validation.
 */
@Path("/v1/config")
public class Config extends AbstractResource {

    /**
     * {@code GET /v1/config?app_root=...} — rendered config.xml as text/xml.
     */
    @GET
    @Produces(MediaType.APPLICATION_XML)
    public String getConfig(@QueryParam("app_root") String appRoot) throws IOException {
        try {
            StringWriter out = new StringWriter();
            Transformer t = TransformerFactory.newInstance().newTransformer();
            t.setOutputProperty(OutputKeys.OMIT_XML_DECLARATION, "no");
            t.setOutputProperty(OutputKeys.INDENT, "yes");
            t.transform(new DOMSource(ConfigIntrospector.getConfig(requireAbsoluteAppRoot(appRoot))), new StreamResult(out));
            return out.toString();
        } catch (Exception e) {
            if (e instanceof IOException io) throw io;
            throw new IOException("failed to render config: " + e.getMessage(), e);
        }
    }

    /**
     * {@code GET /v1/config/fragments?app_root=...[&profile=...]} — list fragments,
     * optionally filtered by profile.
     */
    @GET
    @Path("/fragments")
    @Produces(MediaType.APPLICATION_JSON)
    public List<ConfigFragmentView> listFragments(@QueryParam("app_root") String appRoot,
                                                  @QueryParam("profile") String profile) throws IOException {
        return ConfigIntrospector.listFragments(requireAbsoluteAppRoot(appRoot), profile)
            .stream().map(ConfigFragmentView::from).collect(Collectors.toList());
    }

    /**
     * {@code POST /v1/config/fragments?app_root=...[&dry_run=true]} — add a fragment.
     */
    @POST
    @Path("/fragments")
    @Consumes(MediaType.APPLICATION_JSON)
    @Produces(MediaType.APPLICATION_JSON)
    public ChangeSet addFragment(@QueryParam("app_root") String appRoot,
                                 @QueryParam("dry_run") @DefaultValue("false") boolean dryRun,
                                 AddConfigFragmentRequest req) throws IOException {
        if (req == null) throw new IllegalArgumentException("request body is required");
        return ConfigFragmentEditor.addFragment(requireAbsoluteAppRoot(appRoot),
            req.getScopePath(), req.getXml(), dryRun);
    }

    /**
     * {@code DELETE /v1/config/fragments?app_root=...[&dry_run=true]} — remove
     * fragment(s) matching the selector under the given scope path.
     */
    @DELETE
    @Path("/fragments")
    @Consumes(MediaType.APPLICATION_JSON)
    @Produces(MediaType.APPLICATION_JSON)
    public ChangeSet removeFragment(@QueryParam("app_root") String appRoot,
                                    @QueryParam("dry_run") @DefaultValue("false") boolean dryRun,
                                    RemoveConfigFragmentRequest req) throws IOException {
        if (req == null) throw new IllegalArgumentException("request body is required");
        return ConfigFragmentEditor.removeFragment(requireAbsoluteAppRoot(appRoot),
            req.getScopePath(), req.toSelector(), dryRun);
    }

    /**
     * {@code POST /v1/config/validate?app_root=...} — run X-DDL schema validation.
     * Always returns 200 with the result envelope; callers branch on
     * {@code ok} or the errors list.
     */
    @POST
    @Path("/validate")
    @Produces(MediaType.APPLICATION_JSON)
    public ValidationResult validate(@QueryParam("app_root") String appRoot) throws IOException {
        return ConfigValidator.validate(requireAbsoluteAppRoot(appRoot));
    }
}
