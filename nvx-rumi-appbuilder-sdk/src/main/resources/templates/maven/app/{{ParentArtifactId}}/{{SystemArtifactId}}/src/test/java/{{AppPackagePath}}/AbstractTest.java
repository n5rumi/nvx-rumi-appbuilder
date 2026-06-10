package {{AppPackageName}};

import java.io.File;
import java.io.IOException;
import java.net.URL;
import java.util.HashSet;
import java.util.Properties;

import org.junit.AfterClass;
import org.junit.BeforeClass;
import org.junit.Rule;
import org.junit.rules.TestName;

import com.neeve.server.embedded.EmbeddedXVM;
import com.neeve.util.UtlFile;

/**
 * Base class for in-process integration tests of this Rumi system. Each test
 * boots the subset of services (XVMs) it needs via {@link #startApp}, drives a
 * message flow, asserts on the result, and lets {@link #cleanup()} shut every
 * booted XVM down.
 *
 * <p>Services are launched in-process with {@link EmbeddedXVM} against this
 * module's {@code conf/config.xml}, activating the {@code test} profile
 * (loopback message bus + loopback discovery) so no external broker or
 * networking is required. This mirrors the harness used by the Rumi sample
 * apps and Paywhere.
 *
 * <p>Example:
 * <pre>{@code
 * public class TestFlow extends AbstractTest {
 *     @Test
 *     public void flow() throws Throwable {
 *         Properties env = new Properties();
 *         env.put("nv.ddl.profiles", "test");
 *         MyProcessor proc = startApp(MyProcessor.class, "myapp-order-processor-1", "myapp-order-processor-1-1", env);
 *         MyDriver driver = startApp(MyDriver.class, "myapp-feeder-1", "myapp-feeder-1", env);
 *         // ...drive and assert...
 *     }
 * }
 * }</pre>
 *
 * The {@code appName} is the {@code <app>} instance name and the {@code xvmName}
 * is the {@code <xvm>} instance name from {@code conf/config.xml}.
 */
public class AbstractTest {

    /** Path to this module's DDL config, relative to the module base directory. */
    private static final String CONFIG_FILE = "conf/config.xml";

    @Rule
    public TestName testcaseName = new TestName();

    protected static final HashSet<EmbeddedXVM> xvms = new HashSet<EmbeddedXVM>();

    @BeforeClass
    public static void unitTestInitialize() throws IOException {
        File testRoot = getTestbedRoot();
        System.setProperty("NVROOT", testRoot.getCanonicalPath());

        File rdat = new File(testRoot, "rdat");
        if (rdat.exists()) {
            UtlFile.deleteDirectory(rdat);
        }
        if (!testRoot.exists()) {
            testRoot.mkdirs();
        }
    }

    @AfterClass
    public static void cleanup() throws Throwable {
        Throwable error = null;
        for (EmbeddedXVM xvm : xvms) {
            try {
                xvm.shutdown();
            }
            catch (Throwable thrown) {
                if (error == null) {
                    error = thrown;
                }
                thrown.printStackTrace();
            }
        }
        xvms.clear();
        System.clearProperty("NVROOT");
        if (error != null) {
            throw error;
        }
    }

    protected static File getProjectBaseDirectory() {
        final String basedir = System.getProperty("basedir");
        return basedir != null ? new File(basedir) : new File(".");
    }

    protected static File getTestbedRoot() {
        return new File(getProjectBaseDirectory(), "target/testbed");
    }

    /**
     * Boot one service (XVM) in-process and return its application instance.
     *
     * @param appClass the service's {@code Main}/application class
     * @param appName  the {@code <app>} instance name in conf/config.xml
     * @param xvmName  the {@code <xvm>} instance name in conf/config.xml
     * @param env      runtime properties; set {@code nv.ddl.profiles=test} to
     *                 activate the test profile
     */
    @SuppressWarnings("unchecked")
    public static <T> T startApp(Class<T> appClass, String appName, String xvmName, Properties env) throws Throwable {
        File configFile = new File(getProjectBaseDirectory(), CONFIG_FILE);
        if (!configFile.isFile()) {
            throw new IOException("config file not found: " + configFile.getAbsolutePath());
        }
        URL ddlConfig = configFile.toURI().toURL();
        env.setProperty("x.env.nv.data.directory",
            new File(getTestbedRoot(), "rdat/" + xvmName).getCanonicalPath());
        EmbeddedXVM xvm = EmbeddedXVM.create(ddlConfig, xvmName, env);
        xvms.add(xvm);
        xvm.start();
        return (T) xvm.getApplication(appName);
    }
}
