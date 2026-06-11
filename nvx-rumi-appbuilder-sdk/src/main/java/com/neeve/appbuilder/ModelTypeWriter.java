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

import com.neeve.appbuilder.model.FieldDef;
import org.w3c.dom.Document;
import org.w3c.dom.Element;

import java.util.List;
import java.util.Map;

import static com.neeve.appbuilder.MessageIntrospector.ADML_NAMESPACE;

/**
 * Shared field-append logic for the type-level editors ({@link MessageEditor},
 * {@link EntityEditor}). A new {@code <message>}/{@code <entity>} is built by
 * appending its {@code <field>} children with the same rules everywhere: caller
 * attributes pass through, name/type are filled in when the attribute map omits
 * them, and a stable never-reused field id is minted via {@link ModelIdAllocator}
 * when the caller didn't supply one.
 */
final class ModelTypeWriter {
    private ModelTypeWriter() {}

    /**
     * Append each {@code FieldDef} as a {@code <field>} child of {@code type}
     * (a {@code <message>} or {@code <entity>}). Field ids are allocated against
     * {@code type} as fields are appended, so they stay monotonic and unique
     * within the owning element.
     */
    static void appendFields(Document doc, Element type, List<FieldDef> fields) {
        if (fields == null) return;
        for (FieldDef fd : fields) {
            Element field = doc.createElementNS(ADML_NAMESPACE, "field");
            for (Map.Entry<String, String> entry : fd.getAttributes().entrySet()) {
                field.setAttribute(entry.getKey(), entry.getValue());
            }
            // Ensure name/type are set even if the caller's attribute map omitted them.
            if (fd.getName() != null && !fd.getAttributes().containsKey("name")) {
                field.setAttribute("name", fd.getName());
            }
            if (fd.getType() != null && !fd.getAttributes().containsKey("type")) {
                field.setAttribute("type", fd.getType());
            }
            // Normalize the scalar type name to its canonical ADML spelling
            // (e.g. long -> Long) so it survives ADM/ASM codegen; entity/message
            // references and array types pass through untouched.
            if (field.hasAttribute("type")) {
                field.setAttribute("type", AdmTypes.normalizeFieldType(field.getAttribute("type")));
            }
            // Append first, then allocate so the id scan sees prior siblings.
            type.appendChild(field);
            if (!field.hasAttribute("id")) {
                field.setAttribute("id", String.valueOf(ModelIdAllocator.nextFieldId(type)));
            }
        }
    }
}
