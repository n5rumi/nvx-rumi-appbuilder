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
 * RUMI-459. Boots the order processor and the gateway in-process and calls the
 * GET /order-processor/echo REST endpoint the README's second pair of commands
 * adds: the gateway calls the processor's sample echo operation through the
 * processor's generated client, and the processor counts the calls in its own
 * replicated state. Runs in a JVM of its own (RUMI-460), so the gate stages it
 * separately rather than with the shared suite.
 */
public class SampleEchoTest extends AbstractTest {

    @Test
    public void processorEchoThroughItsClientIncrementsState() throws Throwable {
        // 8080 is frequently occupied; override to a free port before the engine starts.
        System.setProperty("demo.gateway.http.port", "18083");
        Properties env = new Properties();
        env.put("nv.ddl.profiles", "test");
        startApp(com.example.demo.order.processor.Main.class, "demo-order-processor-1", "demo-order-processor-1-1", env);
        startApp(Main.class, "demo-gateway-1", "demo-gateway-1-1", env);

        Thread.sleep(3000); // let the embedded HTTP server bind

        String b1 = get("http://localhost:18083/order-processor/echo?text=hi");
        String b2 = get("http://localhost:18083/order-processor/echo?text=there");

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
