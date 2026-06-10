package com.example.demo;

import static org.junit.Assert.*;

import java.io.BufferedReader;
import java.io.InputStreamReader;
import java.net.HttpURLConnection;
import java.net.URL;
import java.util.Properties;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

import org.junit.Test;

import com.example.demo.gateway.Main;

/**
 * Boots the gateway webservice in-process and exercises the full runtime
 * round-trip: HTTP request -> injectRequestAndWaitForReply -> engine handler
 * -> Rumi state mutation -> reply -> HTTP response. Asserts the echo and that
 * the state-backed count increments across calls.
 *
 * Uses java.net.HttpURLConnection (Java 8 API) since generated apps compile at
 * Java 8 level; the test JVM itself runs on Java 17.
 */
public class WebserviceTest extends AbstractTest {

    @Test
    public void echoRoundTripIncrementsState() throws Throwable {
        // 8080 is frequently occupied; override to a free port before the engine starts.
        System.setProperty("demo.gateway.http.port", "18080");
        Properties env = new Properties();
        env.put("nv.ddl.profiles", "test");
        startApp(Main.class, "demo-gateway-1", "demo-gateway-1-1", env);

        Thread.sleep(3000); // let the embedded HTTP server bind

        String b1 = get("http://localhost:18080/gateway/v1/echo?message=hi");
        String b2 = get("http://localhost:18080/gateway/v1/echo?message=there");

        assertTrue("echo of first call: " + b1, b1.contains("\"echo\":\"hi\""));
        assertTrue("echo of second call: " + b2, b2.contains("\"echo\":\"there\""));
        assertEquals("state-backed count must increment across calls",
            countOf(b1) + 1, countOf(b2));
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

    private static long countOf(String json) {
        Matcher m = Pattern.compile("\"count\"\\s*:\\s*(\\d+)").matcher(json);
        assertTrue("count present in " + json, m.find());
        return Long.parseLong(m.group(1));
    }
}
