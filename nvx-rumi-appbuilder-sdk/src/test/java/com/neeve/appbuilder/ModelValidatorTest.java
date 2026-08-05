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

import com.neeve.appbuilder.model.ValidationResult;
import org.junit.After;
import org.junit.Before;
import org.junit.Test;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.stream.Stream;

import static org.junit.Assert.*;

/**
 * RUMI-376. Two layers under test: schema validation, and the semantic rules
 * the schema provably cannot express.
 *
 * <p>The negative cases here are not hypothetical — each one validates
 * cleanly against x-adml.xsd, which is exactly why the semantic layer exists.
 * {@link #schemaAloneAcceptsWhatTheAdmParserRejects()} pins that fact so the
 * semantic layer is never mistaken for redundant.
 */
public class ModelValidatorTest {

    private Path tempDir;

    @Before
    public void setUp() throws IOException {
        tempDir = Files.createTempDirectory("modelval-");
    }

    @After
    public void tearDown() throws IOException {
        if (tempDir != null && Files.exists(tempDir)) {
            try (Stream<Path> walk = Files.walk(tempDir)) {
                walk.sorted((a, b) -> b.getNameCount() - a.getNameCount())
                    .forEach(p -> { try { Files.deleteIfExists(p); } catch (IOException ignored) {} });
            }
        }
    }

    private Path write(String name, String body) throws IOException {
        Path p = tempDir.resolve(name);
        Files.createDirectories(p.getParent() == null ? tempDir : p.getParent());
        Files.writeString(p, body);
        return p;
    }

    private static String model(String body) {
        return "<?xml version=\"1.0\"?>\n"
             + "<model xmlns=\"http://www.neeveresearch.com/schema/x-adml\" "
             + "namespace=\"com.acme.trade.roe\" defaultFactoryId=\"1\">\n"
             + "    <factories><factory name=\"Factory\" id=\"1\"/></factories>\n"
             + body
             + "</model>\n";
    }

    private static String firstError(ValidationResult r) {
        assertFalse("expected a validation failure, got none", r.isOk());
        return r.getErrors().get(0).getMessage();
    }

    // --- schema layer -------------------------------------------------

    @Test
    public void acceptsAWellFormedModel() throws Exception {
        Path f = write("messages.xml", model(
            "    <messages>\n"
          + "        <message name=\"Tick\" id=\"1\">\n"
          + "            <field name=\"px\" id=\"1\" type=\"Double\"/>\n"
          + "        </message>\n"
          + "    </messages>\n"));
        assertTrue(ModelValidator.validateFile(f).getErrors().toString(),
            ModelValidator.validateFile(f).isOk());
    }

    @Test
    public void rejectsStructuralDamage() throws Exception {
        // <fields> is not a real ADML element — fields hang directly off the
        // message. This is the class of error the schema layer owns.
        Path f = write("messages.xml", model(
            "    <messages>\n"
          + "        <message name=\"Tick\" id=\"1\">\n"
          + "            <fields><field name=\"px\" id=\"1\" type=\"Double\"/></fields>\n"
          + "        </message>\n"
          + "    </messages>\n"));
        assertFalse(ModelValidator.validateFile(f).isOk());
    }

    @Test
    public void rejectsANonIntegerId() throws Exception {
        Path f = write("messages.xml", model(
            "    <messages>\n"
          + "        <message name=\"Tick\" id=\"not-a-number\">\n"
          + "            <field name=\"px\" id=\"1\" type=\"Double\"/>\n"
          + "        </message>\n"
          + "    </messages>\n"));
        assertFalse(ModelValidator.validateFile(f).isOk());
    }

    @Test
    public void rejectsAFileThatIsNotARumiModel() throws Exception {
        Path f = write("notamodel.xml", "<?xml version=\"1.0\"?>\n<hello/>\n");
        assertTrue(firstError(ModelValidator.validateFile(f)).contains("not a Rumi model file"));
    }

    @Test
    public void validatesApiXmlAgainstAsml() throws Exception {
        Path f = write("api.xml",
            "<?xml version=\"1.0\"?>\n"
          + "<model xmlns=\"http://www.neeveresearch.com/schema/x-asml\" "
          + "name=\"order-processor\" namespace=\"com.acme.trade.orders\">\n"
          + "    <messages modelFile=\"com/acme/trade/roe/messages.xml\"/>\n"
          + "    <operations></operations>\n"
          + "</model>\n");
        assertTrue(ModelValidator.validateFile(f).getErrors().toString(),
            ModelValidator.validateFile(f).isOk());
    }

    // --- the gap the semantic layer fills -----------------------------

    /**
     * The motivating fact for RUMI-376. Every rule below is schema-valid;
     * {@code field/@type} is declared {@code xs:string} and XSD does no
     * cross-referencing. If this test ever fails, the semantic layer's checks
     * may have become redundant and should be re-examined rather than kept
     * out of habit.
     */
    @Test
    public void schemaAloneAcceptsWhatTheAdmParserRejects() throws Exception {
        Path f = write("messages.xml", model(
            "    <messages>\n"
          + "        <message name=\"Tick\" id=\"1\">\n"
          + "            <field name=\"qty\" id=\"1\" type=\"long\"/>\n"
          + "            <field name=\"notional\" id=\"2\" type=\"Money\"/>\n"
          + "            <field name=\"bogus\" id=\"3\" type=\"NoSuchTypeAnywhere\"/>\n"
          + "        </message>\n"
          + "    </messages>\n"
          + "    <entities>\n"
          + "        <entity name=\"Money\" id=\"2\">\n"
          + "            <field name=\"amount\" id=\"1\" type=\"Long\"/>\n"
          + "        </entity>\n"
          + "    </entities>\n"));

        // Schema layer alone: clean.
        javax.xml.validation.Validator v =
            Schemas.load(Schemas.Kind.X_ADML).newValidator();
        v.validate(new javax.xml.transform.stream.StreamSource(f.toFile()));

        // Full validation: all three caught.
        ValidationResult r = ModelValidator.validateFile(f);
        assertFalse(r.isOk());
        String all = r.getErrors().toString();
        assertTrue("lowercase scalar not caught: " + all, all.contains("capitalized"));
        assertTrue("asEmbedded not caught: " + all, all.contains("asEmbedded"));
        assertTrue("undefined type not caught: " + all, all.contains("NoSuchTypeAnywhere"));
    }

    @Test
    public void rejectsAFieldNamedAfterAnInheritedFinalGetter() throws Exception {
        Path f = write("messages.xml", model(
            "    <messages>\n"
          + "        <message name=\"Tick\" id=\"1\">\n"
          + "            <field name=\"message\" id=\"1\" type=\"String\"/>\n"
          + "        </message>\n"
          + "    </messages>\n"));
        assertTrue(firstError(ModelValidator.validateFile(f)).contains("reserved name"));
    }

    /**
     * The plausible-looking names matter more than the obvious one: nobody is
     * surprised that `message` is taken, but `messageKey`, `createTs` and
     * `parent` all read as ordinary domain fields and all collide.
     */
    @Test
    public void rejectsTheLessObviousReservedNames() throws Exception {
        for (String name : new String[] {"messageKey", "createTs", "parent", "metadata", "requestId"}) {
            Path f = write(name + ".xml", model(
                "    <messages>\n"
              + "        <message name=\"Tick\" id=\"1\">\n"
              + "            <field name=\"" + name + "\" id=\"1\" type=\"String\"/>\n"
              + "        </message>\n"
              + "    </messages>\n"));
            assertFalse("'" + name + "' should be rejected", ModelValidator.validateFile(f).isOk());
        }
    }

    /**
     * The rule is about SIGNATURE collision, not about the word appearing on a
     * base class. {@code getTag(int)} and {@code setTag(int, Object)} take
     * arguments, so a field named `tag` generates legal overloads. Rejecting it
     * would be a false rejection, which costs more than the miss it prevents —
     * and `tag` was on the list this check was written from.
     */
    @Test
    public void acceptsAFieldWhoseBaseClassMethodTakesArguments() throws Exception {
        Path f = write("messages.xml", model(
            "    <messages>\n"
          + "        <message name=\"Tick\" id=\"1\">\n"
          + "            <field name=\"tag\" id=\"1\" type=\"String\"/>\n"
          + "            <field name=\"name\" id=\"2\" type=\"String\"/>\n"
          + "        </message>\n"
          + "    </messages>\n"));
        assertTrue(ModelValidator.validateFile(f).getErrors().toString(),
            ModelValidator.validateFile(f).isOk());
    }

    /** An entity's fields generate the same accessors, so the rule is the same. */
    @Test
    public void appliesTheReservedNameRuleToEntityFieldsToo() throws Exception {
        Path f = write("state.xml", model(
            "    <entities>\n"
          + "        <entity name=\"Repository\" id=\"1\">\n"
          + "            <field name=\"ownershipCount\" id=\"1\" type=\"Long\"/>\n"
          + "        </entity>\n"
          + "    </entities>\n"));
        assertTrue(firstError(ModelValidator.validateFile(f)).contains("reserved name"));
    }

    @Test
    public void requiresAsEmbeddedOnAnEntityUsedAsAFieldType() throws Exception {
        Path f = write("messages.xml", model(
            "    <messages>\n"
          + "        <message name=\"Tick\" id=\"1\">\n"
          + "            <field name=\"notional\" id=\"1\" type=\"Money\"/>\n"
          + "        </message>\n"
          + "    </messages>\n"
          + "    <entities>\n"
          + "        <entity name=\"Money\" id=\"2\">\n"
          + "            <field name=\"amount\" id=\"1\" type=\"Long\"/>\n"
          + "        </entity>\n"
          + "    </entities>\n"));
        assertTrue(firstError(ModelValidator.validateFile(f)).contains("asEmbedded"));
    }

    @Test
    public void acceptsAnAsEmbeddedEntityUsedAsAFieldType() throws Exception {
        Path f = write("messages.xml", model(
            "    <messages>\n"
          + "        <message name=\"Tick\" id=\"1\">\n"
          + "            <field name=\"notional\" id=\"1\" type=\"Money\"/>\n"
          + "        </message>\n"
          + "    </messages>\n"
          + "    <entities>\n"
          + "        <entity name=\"Money\" id=\"2\" asEmbedded=\"true\">\n"
          + "            <field name=\"amount\" id=\"1\" type=\"Long\"/>\n"
          + "        </entity>\n"
          + "    </entities>\n"));
        assertTrue(ModelValidator.validateFile(f).getErrors().toString(),
            ModelValidator.validateFile(f).isOk());
    }

    /** The exact inverse of the rule above — same entity, opposite requirement. */
    @Test
    public void rejectsAnAsEmbeddedEntityUsedAsACollectionElement() throws Exception {
        Path f = write("state.xml", model(
            "    <entities>\n"
          + "        <entity name=\"Order\" id=\"2\" asEmbedded=\"true\">\n"
          + "            <field name=\"qty\" id=\"1\" type=\"Long\"/>\n"
          + "        </entity>\n"
          + "    </entities>\n"
          + "    <collections>\n"
          + "        <collection name=\"Orders\" id=\"3\" is=\"StringMap\" contains=\"Order\"/>\n"
          + "    </collections>\n"));
        String msg = firstError(ModelValidator.validateFile(f));
        assertTrue(msg, msg.contains("must NOT be"));
    }

    @Test
    public void acceptsAPlainEntityAsACollectionElement() throws Exception {
        Path f = write("state.xml", model(
            "    <entities>\n"
          + "        <entity name=\"Order\" id=\"2\">\n"
          + "            <field name=\"qty\" id=\"1\" type=\"Long\"/>\n"
          + "        </entity>\n"
          + "    </entities>\n"
          + "    <collections>\n"
          + "        <collection name=\"Orders\" id=\"3\" is=\"StringMap\" contains=\"Order\"/>\n"
          + "    </collections>\n"));
        assertTrue(ModelValidator.validateFile(f).getErrors().toString(),
            ModelValidator.validateFile(f).isOk());
    }

    @Test
    public void rejectsAScalarAsACollectionElement() throws Exception {
        Path f = write("state.xml", model(
            "    <collections>\n"
          + "        <collection name=\"Names\" id=\"2\" is=\"StringMap\" contains=\"String\"/>\n"
          + "    </collections>\n"));
        String msg = firstError(ModelValidator.validateFile(f));
        assertTrue(msg, msg.contains("only contain entity or message types"));
    }

    @Test
    public void rejectsALowercaseScalarSpelling() throws Exception {
        Path f = write("messages.xml", model(
            "    <messages>\n"
          + "        <message name=\"Tick\" id=\"1\">\n"
          + "            <field name=\"qty\" id=\"1\" type=\"long\"/>\n"
          + "        </message>\n"
          + "    </messages>\n"));
        String msg = firstError(ModelValidator.validateFile(f));
        assertTrue(msg, msg.contains("capitalized") && msg.contains("Long"));
    }

    // --- conservatism: no false rejections ----------------------------

    @Test
    public void doesNotFlagAFullyQualifiedTypeFromAnotherNamespace() throws Exception {
        Path f = write("messages.xml", model(
            "    <messages>\n"
          + "        <message name=\"Tick\" id=\"1\">\n"
          + "            <field name=\"ref\" id=\"1\" type=\"com.other.ns.Thing\"/>\n"
          + "        </message>\n"
          + "    </messages>\n"));
        assertTrue(ModelValidator.validateFile(f).getErrors().toString(),
            ModelValidator.validateFile(f).isOk());
    }

    /**
     * An unreadable import means the type inventory is incomplete, so an
     * unresolved name is a guess rather than a finding. Staying silent is the
     * right call: a false rejection blocks a legitimate edit, which costs more
     * than the miss.
     */
    @Test
    public void staysSilentOnUnresolvableTypesWhenAnImportCouldNotBeRead() throws Exception {
        Path f = write("models/com/acme/trade/orders/state/state.xml",
            "<?xml version=\"1.0\"?>\n"
          + "<model xmlns=\"http://www.neeveresearch.com/schema/x-adml\" "
          + "namespace=\"com.acme.trade.orders.state\" defaultFactoryId=\"2\">\n"
          + "    <import model=\"com/acme/trade/roe/messages.xml\"/>\n"
          + "    <factories><factory name=\"StateFactory\" id=\"2\"/></factories>\n"
          + "    <entities>\n"
          + "        <entity name=\"Repository\" id=\"1\">\n"
          + "            <field name=\"last\" id=\"1\" type=\"SomethingFromRoe\"/>\n"
          + "        </entity>\n"
          + "    </entities>\n"
          + "</model>\n");
        assertTrue(ModelValidator.validateFile(f).getErrors().toString(),
            ModelValidator.validateFile(f).isOk());
    }

    @Test
    public void resolvesTypesThroughAReadableImport() throws Exception {
        write("models/com/acme/trade/roe/messages.xml", model(
            "    <entities>\n"
          + "        <entity name=\"Money\" id=\"2\" asEmbedded=\"true\">\n"
          + "            <field name=\"amount\" id=\"1\" type=\"Long\"/>\n"
          + "        </entity>\n"
          + "    </entities>\n"));
        Path f = write("models/com/acme/trade/orders/state/state.xml",
            "<?xml version=\"1.0\"?>\n"
          + "<model xmlns=\"http://www.neeveresearch.com/schema/x-adml\" "
          + "namespace=\"com.acme.trade.orders.state\" defaultFactoryId=\"2\">\n"
          + "    <import model=\"com/acme/trade/roe/messages.xml\"/>\n"
          + "    <factories><factory name=\"StateFactory\" id=\"2\"/></factories>\n"
          + "    <entities>\n"
          + "        <entity name=\"Repository\" id=\"1\">\n"
          + "            <field name=\"known\" id=\"1\" type=\"Money\"/>\n"
          + "            <field name=\"unknown\" id=\"2\" type=\"NotInEitherModel\"/>\n"
          + "        </entity>\n"
          + "    </entities>\n"
          + "</model>\n");
        ValidationResult r = ModelValidator.validateFile(f);
        assertFalse(r.isOk());
        String all = r.getErrors().toString();
        assertTrue(all, all.contains("NotInEitherModel"));
        assertFalse("the imported type should have resolved: " + all, all.contains("'Money'"));
    }

    @Test
    public void acceptsAnArrayOfAScalar() throws Exception {
        Path f = write("messages.xml", model(
            "    <messages>\n"
          + "        <message name=\"Tick\" id=\"1\">\n"
          + "            <field name=\"pxs\" id=\"1\" type=\"Double[]\"/>\n"
          + "        </message>\n"
          + "    </messages>\n"));
        assertTrue(ModelValidator.validateFile(f).getErrors().toString(),
            ModelValidator.validateFile(f).isOk());
    }

    @Test
    public void toleratesReservedIdTombstoneComments() throws Exception {
        Path f = write("messages.xml", model(
            "    <messages>\n"
          + "        <message name=\"Tick\" id=\"1\">\n"
          + "            <!-- id=1 reserved (removed qty) -->\n"
          + "            <field name=\"px\" id=\"2\" type=\"Double\"/>\n"
          + "        </message>\n"
          + "    </messages>\n"));
        assertTrue(ModelValidator.validateFile(f).getErrors().toString(),
            ModelValidator.validateFile(f).isOk());
    }
}
