package com.example.demo;

import static org.junit.Assert.*;

import java.util.Properties;

import org.junit.Test;

/**
 * Boots every service type the builder produces -- processor, driver,
 * connector, and webservice -- plus the custom "audit" connector snapped into
 * the processor, all in one in-process system under the test profile. Proves
 * the generated config + wiring for all service types actually starts the Rumi
 * engine (not just compiles).
 *
 * startApp throws if an XVM fails to start, so reaching the asserts is the
 * proof; the asserts confirm each application object was created.
 */
public class SystemBootTest extends AbstractTest {

    @Test
    public void allServiceTypesBoot() throws Throwable {
        System.setProperty("demo.gateway.http.port", "18081");
        Properties env = new Properties();
        env.put("nv.ddl.profiles", "test");

        Object processor = startApp(com.example.demo.order.processor.Main.class,
            "demo-order-processor-1", "demo-order-processor-1-1", env);
        Object driver = startApp(com.example.demo.feeder.Main.class,
            "demo-feeder-1", "demo-feeder-1", env);
        Object connector = startApp(com.example.demo.sink.Main.class,
            "demo-sink-1", "demo-sink-1", env);
        Object web = startApp(com.example.demo.gateway.Main.class,
            "demo-gateway-1", "demo-gateway-1-1", env);

        Thread.sleep(2000);

        assertNotNull("processor (with snapped 'audit' connector) booted", processor);
        assertNotNull("driver booted", driver);
        assertNotNull("connector service booted", connector);
        assertNotNull("webservice booted", web);
    }
}
