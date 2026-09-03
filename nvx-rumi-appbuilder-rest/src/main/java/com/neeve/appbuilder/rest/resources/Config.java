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
import com.neeve.appbuilder.model.ElementSelector;
import com.neeve.appbuilder.model.ValidationResult;
import com.neeve.appbuilder.rest.dto.AddConfigFragmentRequest;
import com.neeve.appbuilder.rest.dto.ConfigFragmentView;
import com.neeve.appbuilder.rest.dto.RemoveConfigFragmentRequest;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.Parameter;
import io.swagger.v3.oas.annotations.enums.ParameterIn;
import io.swagger.v3.oas.annotations.tags.Tag;
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
import java.util.Map;
import java.util.stream.Collectors;

/**
 * Config-level endpoints: full config retrieval, fragment CRUD, X-DDL validation.
 */
@Path("/v1/config")
@Tag(name = "Config", description = "X-DDL config file inspection, fragment CRUD, schema validation")
public class Config extends AbstractResource {

    @GET
    @Produces(MediaType.APPLICATION_XML)
    @Operation(summary = "Get the rendered config.xml",
               description = "Returns the app's assembled config.xml as text/xml. Includes every fragment ConfigInjector has placed at all scopes.")
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

    @GET
    @Path("/fragments")
    @Produces(MediaType.APPLICATION_JSON)
    @Operation(summary = "List config fragments",
               description = "Returns fragments discovered across config-fragment files. Narrow with profile, an exact scope_path "
                           + "(repeat the parameter per segment, e.g. scope_path=xvms&scope_path=templates), and a tag/name "
                           + "selector - the same selector shape DELETE takes. Prefer a narrowed read over GET /v1/config, which "
                           + "returns the whole file to answer any question.")
    public List<ConfigFragmentView> listFragments(@QueryParam("app_root") String appRoot,
                                                  @QueryParam("profile") String profile,
                                                  @QueryParam("scope_path") List<String> scopePath,
                                                  @QueryParam("tag") String tag,
                                                  @QueryParam("name") String name) throws IOException {
        // An absent repeated query param arrives as an empty list, not null, and
        // an empty list is a legitimate scope path (the document root) as far as
        // equals() is concerned - so it has to mean "unset" explicitly.
        List<String> scope = (scopePath == null || scopePath.isEmpty()) ? null : scopePath;
        // Blank is unset here too. Only the Python MCP omits an unset param; a
        // hand-built curl or a generated client sends ?tag=&name=, and an empty
        // tag matches no element at all -- returning [] indistinguishable from
        // "this app has no fragments". The guard above already learned this for
        // scope_path; tag and name need the same treatment.
        String tagOrNull = (tag == null || tag.isEmpty()) ? null : tag;
        String nameOrNull = (name == null || name.isEmpty()) ? null : name;
        ElementSelector selector = (tagOrNull == null && nameOrNull == null)
            ? null
            : new ElementSelector(tagOrNull, nameOrNull == null ? Map.of() : Map.of("name", nameOrNull));
        return ConfigIntrospector.listFragments(requireAbsoluteAppRoot(appRoot), profile, scope, selector)
            .stream().map(ConfigFragmentView::from).collect(Collectors.toList());
    }

    @POST
    @Path("/fragments")
    @Consumes(MediaType.APPLICATION_JSON)
    @Produces(MediaType.APPLICATION_JSON)
    @Operation(summary = "Add a config fragment",
               description = "Adds the given XML fragment under the specified scope path (e.g. [\"apps\",\"templates\"], [\"buses\"]). Idempotent — a structurally identical fragment is a no-op.")
    @Parameter(in = ParameterIn.QUERY, name = "detail",
               description = "Return the full result: every key present and paths absolute. "
                           + "By default paths are relative to the app_root you supplied and "
                           + "empty collections are omitted (RUMI-414).")
    public ChangeSet addFragment(@QueryParam("app_root") String appRoot,
                                 @QueryParam("dry_run") @DefaultValue("false") boolean dryRun,
                                 AddConfigFragmentRequest req) throws IOException {
        if (req == null) throw new IllegalArgumentException("request body is required");
        return ConfigFragmentEditor.addFragment(requireAbsoluteAppRoot(appRoot),
            req.getScopePath(), req.getXml(), dryRun);
    }

    @DELETE
    @Path("/fragments")
    @Consumes(MediaType.APPLICATION_JSON)
    @Produces(MediaType.APPLICATION_JSON)
    @Operation(summary = "Remove config fragment(s)",
               description = "Removes every fragment matching the selector (tag, name, or both) under the specified scope path.")
    @Parameter(in = ParameterIn.QUERY, name = "detail",
               description = "Return the full result: every key present and paths absolute. "
                           + "By default paths are relative to the app_root you supplied and "
                           + "empty collections are omitted (RUMI-414).")
    public ChangeSet removeFragment(@QueryParam("app_root") String appRoot,
                                    @QueryParam("dry_run") @DefaultValue("false") boolean dryRun,
                                    RemoveConfigFragmentRequest req) throws IOException {
        if (req == null) throw new IllegalArgumentException("request body is required");
        return ConfigFragmentEditor.removeFragment(requireAbsoluteAppRoot(appRoot),
            req.getScopePath(), req.toSelector(), dryRun);
    }

    @POST
    @Path("/validate")
    @Produces(MediaType.APPLICATION_JSON)
    @Operation(summary = "Validate config.xml against the X-DDL schema",
               description = "Runs X-DDL schema validation and returns a result envelope. Always 200 — callers branch on the ok flag or the errors list.")
    public ValidationResult validate(@QueryParam("app_root") String appRoot) throws IOException {
        return ConfigValidator.validate(requireAbsoluteAppRoot(appRoot));
    }
}
