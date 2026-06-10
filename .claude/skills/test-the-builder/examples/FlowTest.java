package com.example.demo;

import static org.junit.Assert.*;

import java.util.Properties;

import org.junit.Test;

/**
 * Functional driver -> processor message flow over the loopback app bus.
 *
 * The feeder (driver) sends N Tick messages; the order-processor's onTick
 * @EventHandler (added via the builder's JavaSourceEditor) counts them. Proves
 * a real, builder-assembled multi-service message flow actually runs — not just
 * that the services boot.
 *
 * Requires the functional edits described in the skill (a shared Tick message
 * in roe, the processor's _tickCount field + getTickCount() accessor, and the
 * driver's send line). Those edits are manual today because the builder has no
 * operation for them yet (tracked in gtm/rumi/TODO.md).
 */
public class FlowTest extends AbstractTest {

    @Test
    public void driverToProcessorFlow() throws Throwable {
        Properties env = new Properties();
        env.put("nv.ddl.profiles", "test");

        com.example.demo.order.processor.Main proc = startApp(
            com.example.demo.order.processor.Main.class,
            "demo-order-processor-1", "demo-order-processor-1-1", env);
        com.example.demo.feeder.Main driver = startApp(
            com.example.demo.feeder.Main.class,
            "demo-feeder-1", "demo-feeder-1", env);

        Thread.sleep(2000); // let bus connections establish

        final int count = 50;
        driver.start(count, 1000); // the driver template's send method (count, rate)

        long timeout = System.currentTimeMillis() + 30000;
        while (proc.getTickCount() < count && System.currentTimeMillis() < timeout) {
            Thread.sleep(100);
        }
        assertEquals("processor must receive all ticks the driver sent", count, proc.getTickCount());
    }
}
