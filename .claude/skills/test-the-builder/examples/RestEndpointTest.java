package com.example.demo;

import static org.junit.Assert.*;

import java.io.BufferedReader;
import java.io.InputStreamReader;
import java.io.OutputStream;
import java.net.HttpURLConnection;
import java.net.URL;
import java.nio.charset.StandardCharsets;
import java.util.Properties;

import org.junit.Test;

/**
 * Boots the order processor and the gateway in-process and calls the gateway's
 * generated REST endpoints, which reach the processor through its generated
 * client over the client bus (RUMI-457).
 */
public class RestEndpointTest extends AbstractTest {

    @Test
    public void endpointsReachTheProcessorThroughItsClient() throws Throwable {
        System.setProperty("demo.gateway.http.port", "18082");
        Properties env = new Properties();
        env.put("nv.ddl.profiles", "test");
        startApp(com.example.demo.order.processor.Main.class, "demo-order-processor-1", "demo-order-processor-1-1", env);
        startApp(com.example.demo.gateway.Main.class, "demo-gateway-1", "demo-gateway-1-1", env);
        Thread.sleep(3000);

        String get = call("GET", "http://localhost:18082/orders/42", null, 200);
        assertTrue("path parameter reached the processor: " + get, get.contains("\"orderId\":\"42\""));
        assertTrue("processor replied: " + get, get.contains("\"status\":\"FOUND\""));

        String query = call("GET", "http://localhost:18082/orders/7?verbose=true", null, 200);
        assertTrue("query parameter reached the processor: " + query, query.contains("FOUND (verbose)"));

        String post = call("POST", "http://localhost:18082/orders/lookup", "{\"orderId\":\"99\",\"verbose\":true}", 200);
        assertTrue("body reached the processor: " + post, post.contains("\"orderId\":\"99\"") && post.contains("verbose"));

        call("POST", "http://localhost:18082/orders/lookup", "[1,2]", 400);
    }

    private static String call(String method, String url, String body, int expected) throws Exception {
        HttpURLConnection c = (HttpURLConnection) new URL(url).openConnection();
        c.setRequestMethod(method);
        c.setConnectTimeout(5000);
        c.setReadTimeout(35000);
        if (body != null) {
            c.setDoOutput(true);
            c.setRequestProperty("Content-Type", "application/json");
            try (OutputStream o = c.getOutputStream()) { o.write(body.getBytes(StandardCharsets.UTF_8)); }
        }
        int code = c.getResponseCode();
        StringBuilder sb = new StringBuilder();
        try (BufferedReader r = new BufferedReader(new InputStreamReader(code < 400 ? c.getInputStream() : c.getErrorStream()))) {
            String l;
            while ((l = r.readLine()) != null) sb.append(l);
        }
        assertEquals(method + " " + url + " (body=" + sb + ")", expected, code);
        return sb.toString();
    }
}
