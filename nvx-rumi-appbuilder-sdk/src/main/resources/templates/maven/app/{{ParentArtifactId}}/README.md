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

## Layout

```
{{ParentArtifactId}}/
├── run-in-process.sh
├── {{RoeArtifactId}}/          shared message model (the ROE)
└── {{SystemArtifactId}}/       the system: config, assembly, and the runner
    ├── conf/config.xml         services, XVMs, and the runtime profiles
    └── src/
        ├── main/java/…/InProcessRun.java
        └── test/java/…/AbstractTest.java   base class for in-process tests
```

## Tests

```bash
mvn test
```

In-process integration tests boot the services they need via `AbstractTest`,
under the same `test` profile the runner uses.
