package {{AppPackageName}};

import java.io.File;
import java.io.IOException;
import java.net.URL;
import java.util.ArrayList;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Properties;
import java.util.TreeMap;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.atomic.AtomicBoolean;

import javax.xml.parsers.DocumentBuilderFactory;

import org.w3c.dom.Document;
import org.w3c.dom.Element;
import org.w3c.dom.Node;
import org.w3c.dom.NodeList;

import com.neeve.server.embedded.EmbeddedXVM;
import com.neeve.util.UtlFile;

/**
 * Run this whole system IN ONE PROCESS, for showing someone the app.
 *
 * <p>This is ONE OF THREE ways to run a Rumi app, and the names matter because
 * they are routinely confused:
 *
 * <ol>
 *   <li><b>in-process</b> (this class) &mdash; every service in a single JVM on a
 *       loopback bus, under the {@code test} runtime profile. Starts in seconds,
 *       needs no broker, no Docker and no {@code mvn install}. This is what you
 *       use to put a running app in front of someone and iterate.</li>
 *   <li><b>local deployment</b> ({@code rumi cloud local}) &mdash; Docker
 *       containers on this machine, running the {@code cloud} profile. Slower,
 *       and its purpose is different: it PROVES the cloud profile before you
 *       promote it. It is a verification step, not a preview.</li>
 *   <li><b>cloud deployment</b> &mdash; the real thing, off this machine.</li>
 * </ol>
 *
 * <p>Nothing here is called "local". That word already means (2), and conflating
 * the two is how a preview gets debugged against the wrong runtime.
 *
 * <h2>Running it</h2>
 *
 * <pre>./run-in-process.sh</pre>
 *
 * <p>which is {@code mvn -am -pl {{SystemArtifactId}} -Pin-process process-classes}
 * from the app root. Two parts of that are load-bearing. The {@code -am} builds the
 * sibling service modules in the same reactor, which is what removes the need for
 * {@code mvn install} or a hand-built classpath. And the run is a PHASE-bound
 * execution inside a profile rather than {@code exec:exec} on the command line,
 * because a command-line goal runs on every project in the reactor and {@code -am}
 * puts the parent POM there, which has no exec configuration.
 *
 * <p>Anything you pass to the script reaches the runner's JVM:
 * {@code ./run-in-process.sh -D{{AppTokenName}}.local.<service>.http.port=8188}, for
 * a port something else on the machine already holds.
 *
 * <h2>What it starts, and why not everything</h2>
 *
 * <p>It reads {@code conf/config.xml} rather than naming services in code, so it
 * stays correct as services are added and removed &mdash; this file is scaffolded
 * before any service exists.
 *
 * <p>It starts <b>one XVM per app</b>, not every XVM. A config declaring
 * {@code svc-1-1} and {@code svc-1-2} describes two instances of one app; booting
 * both in a single JVM puts two copies of a web tier on one HTTP port, and the
 * second fails to bind with an error that reads like a defect in the app. When
 * several XVMs host the same app the lowest-named one wins, so the choice is
 * deterministic rather than filesystem-dependent.
 */
public final class InProcessRun {

    /** This module's DDL config, relative to the module base directory. */
    private static final String CONFIG_FILE = "conf/config.xml";

    /**
     * Runtime root for an in-process RUN &mdash; deliberately NOT the
     * {@code target/testbed} that {@code AbstractTest} uses.
     *
     * <p>The {@code test} profile derives the service store from {@code ROOT_DIR},
     * so sharing one root means a preview boots on top of whatever the last test
     * run left behind. That is not theoretical: a preview once reported a
     * connector as ready because the store still held the deliberately-invalid
     * endpoint a test had written, and it would have been shown to a user as
     * live application state.
     */
    private static final String RUN_ROOT = "target/inprocess";

    /**
     * Set by whichever path stops first, so the other becomes a no-op.
     *
     * <p>Without this the two deadlock, and it is not theoretical -- it happened
     * on the first SIGTERM: the hook was inside {@code Shutdown.runHooks} (which
     * holds a lock) while the watcher below noticed the XVMs leaving Started and
     * called {@code System.exit}, which blocks waiting for those same hooks. The
     * process sat there with Jetty stopped and the JVM alive.
     */
    private static final AtomicBoolean STOPPING = new AtomicBoolean(false);

    private InProcessRun() {}

    public static void main(String[] args) throws Exception {
        File baseDir = baseDirectory();
        File configFile = new File(baseDir, CONFIG_FILE);
        if (!configFile.isFile()) {
            throw new IOException("config file not found: " + configFile.getAbsolutePath()
                + " (run this from the app root via ./run-in-process.sh)");
        }

        File runRoot = new File(baseDir, RUN_ROOT);
        System.setProperty("NVROOT", runRoot.getCanonicalPath());
        // Start from a clean store every time. A preview that silently inherits
        // the previous run's state is the same defect as inheriting the test
        // suite's, and harder to spot because the stale data is plausible.
        File rdat = new File(runRoot, "rdat");
        if (rdat.exists()) {
            UtlFile.deleteDirectory(rdat);
        }
        if (!runRoot.exists() && !runRoot.mkdirs()) {
            throw new IOException("could not create run root: " + runRoot);
        }

        Map<String, String> xvmsByApp = selectOneXvmPerApp(configFile);
        if (xvmsByApp.isEmpty()) {
            System.out.println("[in-process] no services are configured yet -- "
                + "scaffold one, then run this again.");
            return;
        }

        URL ddlConfig = configFile.toURI().toURL();
        // Copy-on-write: the watcher iterates this list while the shutdown hook
        // may be walking it on another thread.
        List<EmbeddedXVM> started = new CopyOnWriteArrayList<EmbeddedXVM>();
        // Shut down in REVERSE start order, and register the hook before the
        // first start so a failure partway through still tears down what is up.
        Runtime.getRuntime().addShutdownHook(new Thread(() -> haltingStop(started), "in-process-shutdown"));

        try {
            // DISTINCT xvms, in first-seen order. The map is keyed by APP, and one
            // xvm can host several apps -- iterating its values would then create
            // the same xvm twice, on the same acceptor and the same rdat store.
            Map<String, String> appsByXvm = new LinkedHashMap<String, String>();
            for (Map.Entry<String, String> entry : xvmsByApp.entrySet()) {
                String existing = appsByXvm.get(entry.getValue());
                appsByXvm.put(entry.getValue(),
                    existing == null ? entry.getKey() : existing + ", " + entry.getKey());
            }
            for (Map.Entry<String, String> entry : appsByXvm.entrySet()) {
                String xvmName = entry.getKey();
                String appName = entry.getValue();
                Properties env = new Properties();
                env.setProperty("nv.ddl.profiles", "test");
                env.setProperty("x.env.ROOT_DIR", runRoot.getCanonicalPath());
                env.setProperty("x.env.nv.data.directory",
                    new File(runRoot, "rdat/" + xvmName).getCanonicalPath());
                System.out.println("[in-process] starting " + xvmName + " (app " + appName + ")");
                EmbeddedXVM xvm = EmbeddedXVM.create(ddlConfig, xvmName, env);
                started.add(xvm);
                // verifyAppsLoaded: a config naming an app the classpath does not
                // have starts an EMPTY xvm perfectly happily, and the preview then
                // serves nothing while reporting success.
                xvm.verifyAppsLoaded(true);
                xvm.start();
                Throwable startupError = xvm.getStartupError();
                if (startupError != null) {
                    throw startupError;
                }
            }
        }
        catch (Throwable failure) {
            System.err.println("[in-process] a service failed to start; shutting the rest down");
            failure.printStackTrace();
            stopOnce(started);
            System.exit(1);
        }

        System.out.println("[in-process] started " + started.size() + " service(s): pid="
            + currentPid() + " root=" + runRoot);
        // NOT "up", and not "ready". A service can fault AFTER start and this
        // process cannot currently tell: a webservice that loses its port bind
        // logs `(sev) ... event handler faulted` and keeps its XVM in Started,
        // so anything here claiming health would be claiming what it has not
        // checked. Saying "started" and pointing at the log is the honest
        // version until there is an app-level health signal to read.
        System.out.println("[in-process] watch the log above for any (sev) line -- "
            + "a service can fault after starting. Ctrl-C to stop.");

        if (awaitAnyStop(started)) {
            System.exit(1);
        }
    }

    /**
     * Block while every started XVM is still running, and return as soon as one
     * is not.
     *
     * <p><b>Not {@code Thread.currentThread().join()}.</b> The obvious shape
     * parks forever and outlives its own engine: a service dies, the JVM stays
     * up holding the port open to nothing, and the next run fails to bind
     * against a process that looks alive. Watching the state means the process
     * exits when it stops being useful, which is what lets a supervisor notice.
     */
    private static boolean awaitAnyStop(List<EmbeddedXVM> xvms) throws InterruptedException {
        while (!STOPPING.get()) {
            for (EmbeddedXVM xvm : xvms) {
                if (xvm.getState() != EmbeddedXVM.State.Started) {
                    System.err.println("[in-process] a service is no longer running ("
                        + xvm.getState() + ") -- stopping the rest.");
                    // haltingStop, NOT stopOnce: an unbounded stop on this thread
                    // would hang exactly as the signal path does, never reach the
                    // exit below, and leave STOPPING set so a later Ctrl-C found
                    // the hook a no-op. That is the unkillable process this file
                    // is supposed to prevent, reached by the other door.
                    haltingStop(xvms);
                    return true;
                }
            }
            Thread.sleep(500L);
        }
        return false;   // something else is already stopping us (a signal)
    }

    /** How long a graceful shutdown gets before this process stops being polite. */
    private static final long SHUTDOWN_GRACE_MS = 15_000L;

    /**
     * Stop on a signal, and GUARANTEE the process dies.
     *
     * <p>{@code EmbeddedXVM.shutdown()} does not reliably return when called from
     * a JVM shutdown hook: it stops the services (Jetty releases its port, the
     * engines log their teardown) and then blocks in {@code Thread.join} on the
     * XVM's own {@code X-Server-*-Main} thread, which is itself waiting for a
     * monitor. Observed with four services on Rumi 4.0.660: the port was free and
     * the JVM stayed alive indefinitely.
     *
     * <p>So the shutdown runs on a DAEMON thread and this hook waits a bounded
     * time for it, then halts. For a preview runner that trade is the right way
     * round: a process you cannot Ctrl-C is a worse bug than one that occasionally
     * skips the last of its teardown, and the previous version of this file left
     * exactly that -- an unkillable JVM holding the port it had already closed.
     *
     * <p>This BOUNDS the problem rather than fixing it; the hang itself belongs to
     * the engine and is filed separately.
     */
    private static void haltingStop(List<EmbeddedXVM> xvms) {
        Thread stopper = new Thread(() -> stopOnce(xvms), "in-process-stop");
        stopper.setDaemon(true);
        stopper.start();
        try {
            stopper.join(SHUTDOWN_GRACE_MS);
        }
        catch (InterruptedException interrupted) {
            Thread.currentThread().interrupt();
        }
        if (stopper.isAlive()) {
            System.err.println("[in-process] shutdown did not finish in "
                + (SHUTDOWN_GRACE_MS / 1000) + "s; halting.");
            Runtime.getRuntime().halt(0);
        }
    }

    /** Shut everything down, once, whichever path gets here first. */
    private static void stopOnce(List<EmbeddedXVM> xvms) {
        if (!STOPPING.compareAndSet(false, true)) {
            return;
        }
        List<EmbeddedXVM> reversed = new ArrayList<EmbeddedXVM>(xvms);
        Collections.reverse(reversed);
        for (EmbeddedXVM xvm : reversed) {
            try {
                xvm.shutdown();
            }
            catch (Throwable thrown) {
                thrown.printStackTrace();
            }
        }
    }

    /**
     * One XVM per app, keyed by app name, in config order.
     *
     * <p>Reads only what is declared at the top level of {@code config.xml}: a
     * profile's own {@code <xvms>} overrides are activation-dependent and
     * resolving them here would duplicate the DDL engine badly.
     */
    static Map<String, String> selectOneXvmPerApp(File configFile) throws Exception {
        DocumentBuilderFactory factory = DocumentBuilderFactory.newInstance();
        // No network access for DTDs, and no entity expansion: this file is
        // local and trusted, but a parser that can be steered is never worth it.
        factory.setFeature("http://apache.org/xml/features/disallow-doctype-decl", true);
        factory.setNamespaceAware(false);
        Document doc = factory.newDocumentBuilder().parse(configFile);

        Element model = doc.getDocumentElement();
        Element xvms = firstChild(model, "xvms");
        if (xvms == null) {
            return Collections.emptyMap();
        }

        // app name -> candidate xvm names, sorted, so "svc-1-1" beats "svc-1-2"
        // and the choice does not depend on document order.
        Map<String, TreeMap<String, String>> candidates = new LinkedHashMap<String, TreeMap<String, String>>();
        for (Element xvm : childrenNamed(xvms, "xvm")) {
            String xvmName = xvm.getAttribute("name");
            if (xvmName.isEmpty() || !isEnabled(xvm.getAttribute("enabled"))) {
                continue;   // a disabled xvm is not a candidate
            }
            Element apps = firstChild(xvm, "apps");
            if (apps == null) {
                continue;
            }
            for (Element app : childrenNamed(apps, "app")) {
                String appName = app.getAttribute("name");
                if (appName.isEmpty()) {
                    continue;
                }
                candidates.computeIfAbsent(appName, k -> new TreeMap<String, String>())
                          .put(xvmName, xvmName);
            }
        }

        Map<String, String> chosen = new LinkedHashMap<String, String>();
        for (Map.Entry<String, TreeMap<String, String>> e : candidates.entrySet()) {
            chosen.put(e.getKey(), e.getValue().firstKey());
        }
        return chosen;
    }

    /**
     * Whether an {@code enabled} attribute means yes.
     *
     * <p>The scaffolder writes it as a DDL placeholder rather than a literal:
     * {@code enabled="${app.local.svc.xvm.enabled::true}"}. So a check for the
     * string "false" is dead code, and this file had one -- the flag looked
     * honoured and nothing honoured it.
     *
     * <p>Resolves exactly the {@code ${key::default}} form against system
     * properties, which is what the script's passthrough sets, so
     * {@code ./run-in-process.sh -Dapp.local.svc.xvm.enabled=false} leaves that
     * service out of the preview. Anything more complex is the DDL engine's job
     * and is deliberately not reimplemented here: an expression this does not
     * recognise is treated as enabled, because failing to start a service the
     * config wanted is worse than starting one it did not.
     */
    static boolean isEnabled(String attribute) {
        if (attribute == null || attribute.trim().isEmpty()) {
            return true;    // absent means enabled
        }
        String value = attribute.trim();
        if (value.startsWith("${") && value.endsWith("}")) {
            String inner = value.substring(2, value.length() - 1);
            int sep = inner.indexOf("::");
            String key = sep >= 0 ? inner.substring(0, sep) : inner;
            String fallback = sep >= 0 ? inner.substring(sep + 2) : "true";
            if (key.contains("${") || fallback.contains("${")) {
                return true;   // nested expression: not ours to resolve
            }
            value = System.getProperty(key, fallback);
        }
        return !"false".equalsIgnoreCase(value.trim());
    }

    private static Element firstChild(Element parent, String name) {
        List<Element> found = childrenNamed(parent, name);
        return found.isEmpty() ? null : found.get(0);
    }

    private static List<Element> childrenNamed(Element parent, String name) {
        List<Element> out = new ArrayList<Element>();
        NodeList children = parent.getChildNodes();
        for (int i = 0; i < children.getLength(); i++) {
            Node node = children.item(i);
            if (node.getNodeType() == Node.ELEMENT_NODE && name.equals(node.getNodeName())) {
                out.add((Element) node);
            }
        }
        return out;
    }

    /**
     * This JVM's pid, the Java 8 way.
     *
     * <p>NOT {@code ProcessHandle.current().pid()}: a scaffolded app compiles at
     * {@code <release>8</release>} while it RUNS on a 17 JVM, so anything added
     * to the JDK after 8 compiles nowhere and the error names this file rather
     * than the mismatch. (That mismatch is itself a filed defect; until it is
     * fixed, template code has to stay Java 8.)
     *
     * <p>The pid is printed so whoever started this can stop it by pid rather
     * than pattern-killing every process that looks similar, which matches the
     * shell running the pattern and kills itself.
     */
    private static String currentPid() {
        String vmName = java.lang.management.ManagementFactory.getRuntimeMXBean().getName();
        int at = vmName.indexOf('@');
        return at > 0 ? vmName.substring(0, at) : vmName;
    }

    private static File baseDirectory() {
        String basedir = System.getProperty("basedir");
        return basedir != null ? new File(basedir) : new File(".");
    }
}
