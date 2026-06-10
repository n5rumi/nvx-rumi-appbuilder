---
name: test-the-builder
description: Runtime end-to-end test of the Rumi App Builder. Scaffolds a full system exercising every service type (processor, driver, connector, webservice) plus a custom snapped-in connector, then BUILDS and RUNS it in-process through the JUnit/EmbeddedXVM harness baked into generated apps. Use when you want to prove the builder produces apps that actually run (not just compile) — e.g. after changing templates, the SDK, service types, or connector wiring. Catches runtime-only bugs (DDL/config schema, JAXB/dependency, engine startup, HTTP round-trip, state persistence) that `mvn package` cannot.
---

# Test the Builder (runtime end-to-end)

This skill validates the Rumi App Builder by using it to scaffold a complete multi-service
system, then **building and running** that system in-process via the JUnit + `EmbeddedXVM`
harness that every generated app now ships (see `AbstractTest.java` + the `test` config
profile, added to the `app/.../{{SystemArtifactId}}` template).

Why this exists: `mvn package` only proves the generated code *compiles*. Whole classes of
bugs only appear when the engine actually starts and serves a request — DDL/X-DDL schema
validity, JAXB/dependency resolution, config-profile correctness, HTTP binding, the
`injectRequestAndWaitForReply` round-trip, and Rumi state mutation. This skill exercises all
of that. (It is the runtime analogue of the scaffold-then-`mvn package` smoke check.)

Repo root: `/Users/girish/Development/nvx/github/nvx-rumi-appbuilder` (`$REPO` below).

## What it builds

A `demo` app (package `com.example.demo`) with one of every service type plus a custom
connector snapped into the processor:

| Service | Type | xvm name | app name |
|---|---|---|---|
| `order-processor` | processor | `demo-order-processor-1-1` | `demo-order-processor-1` |
| `feeder` | driver | `demo-feeder-1` | `demo-feeder-1` |
| `sink` | connector | `demo-sink-1` | `demo-sink-1` |
| `gateway` | webservice | `demo-gateway-1-1` | `demo-gateway-1` |
| `audit` | custom connector on `order-processor` | (runs inside the processor xvm) | — |

## Prerequisites

- **JDK 17** and **Maven ≥ 3.9** (the generated app's compiler plugin needs ≥ 3.6.3).
- A **real published Rumi version** for the generated app. `TestAppFactory`'s default
  (`4.0.0` / mgmt `2.0.0`) is an unpublished placeholder; patch it to a real one
  (e.g. `4.0.629` / mgmt `2.0.73`, the nvx-accounts stack) before building.
- The Rumi 4.0 engine needs Java-17 module access at runtime; export the same
  `MAVEN_OPTS` Paywhere uses.

```bash
export JAVA_HOME=$(/usr/libexec/java_home -v 17 2>/dev/null || echo ~/.sdkman/candidates/java/17.0.19-tem)
export PATH="$JAVA_HOME/bin:$PATH"
export MAVEN_OPTS="-Xmx2g --add-exports=java.base/sun.nio.ch=ALL-UNNAMED --add-opens=java.base/java.nio=ALL-UNNAMED --add-opens=java.base/jdk.internal.ref=ALL-UNNAMED"
MVN=mvn   # ensure this is 3.9+; e.g. ~/.sdkman/candidates/maven/3.9.9/bin/mvn
WORK=$(mktemp -d)
```

## Step 1 — build the SDK and capture its classpath

```bash
cd "$REPO"
$MVN -q -pl nvx-rumi-appbuilder-sdk install -DskipTests
$MVN -q -pl nvx-rumi-appbuilder-sdk dependency:build-classpath -Dmdep.outputFile=/tmp/sdk-cp.txt
CP="$REPO/nvx-rumi-appbuilder-sdk/target/classes:$(cat /tmp/sdk-cp.txt)"
```

## Step 2 — scaffold the system via the SDK

`TestAppFactory` (in the SDK's `com.neeve.appbuilder.test` package) plus `ConnectorEditor`
are the scaffolding entry points. Drive them from a tiny Java main:

```bash
cat > "$WORK/Build.java" <<'EOF'
import com.neeve.appbuilder.ConnectorEditor;
import com.neeve.appbuilder.JavaSourceEditor;
import com.neeve.appbuilder.test.TestAppFactory;
import java.nio.file.*;
public class Build {
  public static void main(String[] a) throws Exception {
    Path app = TestAppFactory.newApp("demo").packageName("com.example.demo").scaffoldAt(Paths.get(a[0]));
    TestAppFactory.addProcessor(app, "order-processor");
    TestAppFactory.addDriver(app, "feeder");
    TestAppFactory.addConnector(app, "sink");
    TestAppFactory.addWebservice(app, "gateway");
    ConnectorEditor.addConnector(app, "order-processor", "audit", false);
    // Functional code via a builder op: add an @EventHandler onTick(Tick) to the
    // processor whose body counts ticks. (The Tick message, the _tickCount field,
    // and the getter are added by hand below — the builder has no op for those.)
    JavaSourceEditor.addHandler(app, "order-processor", "onTick", "Tick", "_tickCount++;", false);
    System.out.println("BUILT:" + app);
  }
}
EOF
javac -cp "$CP" -d "$WORK" "$WORK/Build.java"
java  -cp "$CP:$WORK" Build "$WORK/out"
APP="$WORK/out/test-demo"
```

(Equivalent via REST/MCP if the Dev MCP is running: `add_service` for each type,
`add_connector` on the processor, `add_handler` for `onTick`.)

### Step 2b — functional edits the builder can't do yet

The `FlowTest` needs a shared message both the driver and processor see, a counter
the test can read, and a driver that actually sends. The builder has no operation for
these three, so make them by hand (and keep them logged in `gtm/rumi/TODO.md` as
candidate builder operations):

```bash
# (1) shared Tick message in roe (no op to add a shared roe message)
perl -0pi -e 's{<messages>\s*</messages>}{<messages>\n        <message name="Tick" id="1"/>\n    </messages>}' \
  "$APP/test-demo-roe/src/main/models/com/example/demo/roe/messages.xml"
# (2) processor counter field + public accessor (no op to add a field/accessor)
perl -0pi -e 's{    private AepMessageSender _messageSender;}{    private AepMessageSender _messageSender;\n    private int _tickCount;\n    public int getTickCount() \{ return _tickCount; \}}' \
  "$APP/test-demo-order-processor/src/main/java/com/example/demo/order/processor/Main.java"
# (3) fill the driver template's send placeholder
perl -0pi -e 's{// put code here to send a message}{_messageSender.sendMessage(Tick.create());}' \
  "$APP/test-demo-feeder/src/main/java/com/example/demo/feeder/Main.java"
```

## Step 3 — patch the generated app to real Rumi versions

```bash
cd "$APP"
sed -i '' 's#>4.0.0</nvx.rumi.version#>4.0.629</nvx.rumi.version#; \
           s#>4.0.0</nvx.rumi.bindings.version#>4.0.629</nvx.rumi.bindings.version#; \
           s#>2.0.0</nvx.rumi.management.version#>2.0.73</nvx.rumi.management.version#' pom.xml
# (Linux sed: drop the empty '' after -i.)
```

## Step 4 — drop in the integration tests

Three tests go in the **system module** (it has `conf/config.xml` and depends on every service):
`$APP/test-demo-system/src/test/java/com/example/demo/`. Copy them from `examples/` next to
this skill (`$REPO/.claude/skills/test-the-builder/examples/`).

**WebserviceTest** — the deep round-trip: boot the webservice, HTTP GET the echo endpoint
twice, assert the echo and that the Rumi-state-backed `count` increments. The HTTP port is
overridden off the 8080 default (commonly occupied) via a system property.

**SystemBootTest** — broad: boot all four service types + the snapped connector together and
assert each engine started.

**FlowTest** — functional message flow: the driver sends N `Tick` messages, the processor's
`onTick` handler counts them, assert the count matches. Requires the Step 2b edits.

Substitute the app package / service xvm+app names if you scaffolded different ones. Key
points the tests rely on:
- activate the profile: `env.put("nv.ddl.profiles","test")`.
- override the webservice port: `System.setProperty("demo.gateway.http.port","18080")` (pick a
  free port; 8080 is frequently taken).
- the webservice endpoint path is `/<serviceTokenName>/v1/echo` (e.g. `/gateway/v1/echo`).
- tests must compile at **Java 8** (generated apps target Java 8), so use
  `java.net.HttpURLConnection`, not `java.net.http.HttpClient`.

## Step 5 — build and run

```bash
cd "$APP"
$MVN -q test -DfailIfNoTests=false   # runs all three tests in the system module
```

Green (`Tests run: 3, Failures: 0, Errors: 0`, `BUILD SUCCESS`) means: every generated
service type boots in-process, the custom connector wiring is valid, the full
HTTP→engine→state→reply round-trip works, and a driver→processor message flow runs end to
end. Clean up with `rm -rf "$WORK"`.

## Step 6 — treat failures as builder bugs

A failure here is almost always a **template / SDK** defect, not a test defect — the test is
just the lens. Fix it in `$REPO` templates/SDK, rebuild the SDK (Step 1), re-scaffold, re-run.
Real bugs this harness has already caught and fixed:
- `xmlns=""` on `ConfigInjector`-injected fragments → X-DDL schema validation failure under
  `EmbeddedXVM` (fixed: inject/create config elements in the x-ddl namespace).
- Parent POM pinned only javax JAXB (2.3.2); Rumi 4.0's engine also needs the **jakarta**
  JAXB annotations → engine never started (fixed: ship both namespaces side by side).
- `--` inside an XML comment in the system POM template (illegal XML).
- Webservice default port 8080 collisions.

## Builder gaps this surfaced (candidate new operations)

`WebserviceTest` and `SystemBootTest` are gap-free — they run on builder output alone.
`FlowTest` needs the three Step-2b hand edits because the builder currently has no operation
to:
1. add a **shared message to the `roe` module** that multiple services import (`MessageEditor`
   only targets a single service's `messages.xml`);
2. add a **field + public accessor** to a service class (`JavaSourceEditor` only adds
   `@EventHandler` methods);
3. fill a **driver's send body** (the driver template ships a `// put code here` placeholder).

Each is a reasonable future builder operation; they are tracked in `gtm/rumi/TODO.md`. Until
they exist, the skill makes the edits by hand — which is itself a useful signal about where the
builder's "do" surface is thin.
