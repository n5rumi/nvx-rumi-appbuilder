package com.example.demo;

import static org.junit.Assert.*;

import java.io.BufferedReader;
import java.io.InputStreamReader;
import java.net.HttpURLConnection;
import java.net.URL;
import java.util.Properties;

import org.junit.Test;

import com.example.demo.gateway.Main;

/**
 * The sample-free counterpart of {@link WebserviceTest} (RUMI-382).
 *
 * <p>A webservice scaffolded with {@code includeSamples=false} has no
 * EchoRequest, no {@code onEchoRequest} handler and no {@code /echo} endpoint,
 * so the echo round-trip cannot be the thing that proves it works. What still
 * has to be proven is the part most likely to break when the samples are
 * stripped: that Jersey still stands up a resource whose only remaining method
 * is the liveness probe, and that the embedded HTTP server binds and answers.
 *
 * <p>That is not a hypothetical worry. Removing an endpoint can leave a JAX-RS
 * resource class with no resource methods at all, which is the kind of thing
 * that compiles perfectly and then fails at deployment -- exactly the
 * "builds != runs" class of defect this whole harness exists to catch. Keeping
 * a {@code /health} endpoint in the bare template is the deliberate answer, and
 * this test is what holds that answer honest.
 *
 * <p>Uses java.net.HttpURLConnection (Java 8 API) since generated apps compile
 * at Java 8 level; the test JVM itself runs on Java 17.
 */
public class BareWebserviceTest extends AbstractTest {

    @Test
    public void healthEndpointAnswersWithoutAnySampleCode() throws Throwable {
        // 8080 is frequently occupied; override to a free port before the engine starts.
        System.setProperty("demo.gateway.http.port", "18082");
        Properties env = new Properties();
        env.put("nv.ddl.profiles", "test");
        startApp(Main.class, "demo-gateway-1", "demo-gateway-1-1", env);

        Thread.sleep(3000); // let the embedded HTTP server bind

        String body = get("http://localhost:18082/gateway/v1/health");
        assertTrue("health response: " + body, body.contains("\"status\":\"ok\""));
    }

    private static String get(String url) throws Exception {
        HttpURLConnection c = (HttpURLConnection) new URL(url).openConnection();
        c.setRequestMethod("GET");
        c.setConnectTimeout(5000);
        c.setReadTimeout(35000);
        int code = c.getResponseCode();
        StringBuilder sb = new StringBuilder();
        try (BufferedReader r = new BufferedReader(new InputStreamReader(
                code < 400 ? c.getInputStream() : c.getErrorStream()))) {
            String l;
            while ((l = r.readLine()) != null) sb.append(l);
        }
        assertEquals("HTTP 200 from " + url + " (body=" + sb + ")", 200, code);
        return sb.toString();
    }
}
