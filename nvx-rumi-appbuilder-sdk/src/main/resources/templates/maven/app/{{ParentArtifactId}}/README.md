# {{AppDisplayName}}

A Rumi app scaffolded by the Rumi App Builder.

## Running it: three mechanisms, and which one you want

These get confused constantly, and debugging a preview against the wrong runtime
costs hours. They are not interchangeable.

| | What it is | When |
|---|---|---|
| **in-process** | Every service in **one JVM** on a loopback bus, under the `test` runtime profile. Starts in seconds. No broker, no Docker, **no `mvn install`**. | Showing someone the running app, and iterating on it. This is the one you want most of the time. |
| **local deployment** | `rumi cloud local` — Docker containers on this machine, running the **`cloud`** profile. | **Proving the cloud profile** before you promote it. It is a verification step, not a preview. |
| **cloud deployment** | The real thing, off this machine. | Production, and anything that has to outlive your laptop. |

⚠️ **"local" means the second one.** The in-process runner is never called "local",
because that name is taken and the confusion is the expensive kind: an app
debugged in-process behaves differently from the same app under the `cloud`
profile, which is the entire reason the local deployment exists.

### in-process

```bash
./run-in-process.sh
```

Starts **one XVM per app** from `{{SystemArtifactId}}/conf/config.xml` and parks
until you Ctrl-C. It reads the config rather than naming services in code, so it
stays correct as you add and remove them.

Two things it deliberately does:

- **One instance per app, not every XVM.** A config declaring `svc-1-1` and
  `svc-1-2` describes two instances of one app; booting both in one JVM puts two
  copies of a web tier on one port, and the second fails to bind with an error
  that reads like a bug in your code.
- **Its own store**, under `target/inprocess` rather than the `target/testbed`
  the test suite uses. Sharing one root means a preview boots on top of whatever
  the last test run left behind — which has shown a connector as "ready" because
  a test had written a deliberately invalid endpoint into the store.

It exits as soon as an XVM stops, rather than parking forever. A runner that
outlives its own engine holds the port open to nothing, and the next run then
fails against a process that looks alive.

⚠️ **"started" is not "healthy", and the runner says so.** A service can fault
*after* it starts — a web tier that cannot bind its port logs a `(sev)` line and
its XVM stays in `Started` — so the runner reports what it launched and points you
at the log rather than claiming a health check it has not made. Read the log for
`(sev)` before handing the URL to anyone.

### local deployment, and cloud

See the Rumi CLI docs. Both run the `cloud` profile; neither is a substitute for
the above when you just want to look at the app.

## REST endpoints: request-reply with this service, and with another

A webservice's REST endpoints are added by command, and each endpoint's body is
your code, the way a message handler's is.

<!-- @sample-begin -->
Scaffolded with samples, a webservice and a processor carry everything needed
for one endpoint of each request-reply kind. Below, `<webservice>` and `<processor>` are the service names you chose,
`<ws-package>` is the webservice's package suffix (`order-gateway` becomes
`order.gateway`), and `<Processor>` is the processor's name in PascalCase
(`order-processor` becomes `OrderProcessor`).

**1. Request-reply with the webservice's own engine.** The webservice already
has an `EchoRequest` message and a handler that answers it, so the endpoint only
injects the request:

```bash
rumi dev rest-endpoint add <webservice> GET /<webservice>/echo -a . --body-file - <<'EOF'
final {{AppPackageName}}.<ws-package>.messages.EchoRequest request =
    RestJson.read(uri, null, {{AppPackageName}}.<ws-package>.messages.EchoRequest.create());
final {{AppPackageName}}.<ws-package>.messages.EchoResponse response =
    _engineProvider.getEngine().injectRequestAndWaitForReply(request, 30000);
return response != null ? RestJson.reply(response)
                        : RestJson.error(Response.Status.GATEWAY_TIMEOUT, "no reply in time");
EOF
```

**2. Request-reply with another service.** The processor has an `echo` operation
in its `api.xml`. Calling it from the webservice needs the processor's client
first, which this adds along with its dependency and configuration:

```bash
rumi dev service-client add <webservice> <processor> -a .
rumi dev rest-endpoint add <webservice> GET /<processor>/echo -a . --body-file - <<'EOF'
final {{AppPackageName}}.roe.<Processor>EchoRequest request =
    RestJson.read(uri, null, {{AppPackageName}}.roe.<Processor>EchoRequest.create());
try {
    return RestJson.reply(<Processor>Client.get().echo(request));
} catch (com.neeve.client.EClientException e) {
    return RestJson.clientFailure(e);
}
EOF
```

Then `GET /<webservice>/echo?text=hi` and `GET /<processor>/echo?text=hi` each
echo the text with a count kept in that service's replicated state. A body can
also do neither, or both, or send a one-way message to a downstream service.
`rumi dev rest-endpoint get` prints a body back, and `update` replaces it.
<!-- @sample-end -->
<!-- @bare-begin -->
A body does request-reply with the webservice's own engine by injecting a
request its handler answers (`_engineProvider.getEngine().injectRequestAndWaitForReply(...)`).
To call an operation another service declares in its `api.xml`, first give the
webservice that service's client, then call it from the body:

```bash
rumi dev service-client add <webservice> <service> -a .
rumi dev rest-endpoint add <webservice> GET /<path> -a . --body-file my-body.java
```

`rumi dev service-client get <webservice> <service>` lists the methods the
client offers. `rumi dev rest-endpoint get` prints a body back, and `update`
replaces it.
<!-- @bare-end -->

Request-reply between two services' handlers is not available: a handler
cannot block on another service. It is for REST endpoints, on the webservice's
own threads.

## Which Rumi jar has this class?

Rumi is about forty artifacts, and the package name does not name the artifact:
`RogXStringMap` is in `com.neeve.rog` and ships in **nvx-rumi-ods**. So when you
hit a `cannot find symbol`, ask:

```bash
./find-class.sh RogXStringMap
```

It prints the artifact and the `<dependency>` block to paste. It reads your local
Maven repository and starts no build, so it is instant and works offline -- but a
jar Maven has not fetched cannot be searched, so build once if something you are
sure exists does not turn up.

## Layout

```
{{ParentArtifactId}}/
├── run-in-process.sh
├── find-class.sh
├── {{RoeArtifactId}}/          shared message model (the ROE)
└── {{SystemArtifactId}}/       the system: config, assembly, and the runner
    ├── conf/config.xml         services, XVMs, and the runtime profiles
    └── src/
        ├── main/java/…/InProcessRun.java
        └── test/java/…/AbstractTest.java   base class for in-process tests
```

## Java version

Built and run on **JDK 17**. `maven.compiler.release` is 17 in the parent pom, so
the API you compile against is the API you run on.

Rumi's own jars are compiled for Java 8, which is fine — an app may target a
higher release than the library it uses.

**Do not lower this below 9.** `InProcessRun` reports its own pid with
`ProcessHandle`, which arrived in Java 9, so a lower release fails to compile
with `cannot find symbol: class ProcessHandle`. The `--add-opens` flags in
`rumi.jvm.args` are *not* the reason: those are runtime JVM flags and work
regardless of what release the bytecode was compiled for.

## Tests

```bash
mvn test
```

In-process integration tests boot the services they need via `AbstractTest`,
under the same `test` profile the runner uses.
