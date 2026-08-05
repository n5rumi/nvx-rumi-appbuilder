package {{AppPackageName}}.{{ServicePackageName}};

import com.neeve.aep.AepEngine;
import com.neeve.aep.AepMessageSender;
import com.neeve.aep.annotations.EventHandler;
// @sample-begin
import com.neeve.aep.event.AepMessagingPrestartEvent;
// @sample-end
import com.neeve.server.app.annotations.AppInjectionPoint;

// @sample-begin
import {{AppPackageName}}.{{ServicePackageName}}.messages.AlarmMessage;
import {{AppPackageName}}.{{ServicePackageName}}.messages.EmptyMessage;
import {{AppPackageName}}.{{ServicePackageName}}.messages.SampleConnectorMessage;
// @sample-end

/**
 * The Rumi service that owns this connector's bus.
 *
 * <p>The connector itself lives in
 * {@link {{AppPackageName}}.{{ServicePackageName}}.connector.Main} and is wired in
 * through a {@code connector://} bus binding in {@code config.xml}. This class is
 * the ordinary Rumi service on the other side of that bus: outbound, it publishes
 * with {@code _messageSender.sendMessageThroughBus("{{ServiceName}}", ...)} and the
 * connector's {@code processOutbound} receives it; inbound, whatever the connector
 * emits via {@code processInbound} arrives here as an {@code @EventHandler} call.
 *
 * <p>{@code _engine.scheduleMessage(message, delayMillis)} is how a connector
 * service gives itself a timer, since it has no external caller of its own.
 */
public class Main {
    private AepEngine _engine;
    private AepMessageSender _messageSender;

    // @sample-begin
    final private void scheduleNextAlarm() {
        _engine.scheduleMessage(AlarmMessage.create(), 100);
    }

    // @sample-end
    @AppInjectionPoint
    public void setEngine(AepEngine engine) {
        _engine = engine;
    }

    @AppInjectionPoint
    public void setMessageSender(AepMessageSender messageSender) {
        _messageSender = messageSender;
    }
    // @sample-begin

    @EventHandler
    final public void onMessagingPrestart(final AepMessagingPrestartEvent event) {
        event.setFirstMessage(EmptyMessage.create());
    }

    @EventHandler
    final public void onFirstMessage(final EmptyMessage message) {
        scheduleNextAlarm();
    }

    @EventHandler
    final public void onAlarmMessage(final AlarmMessage message) {
        _messageSender.sendMessageThroughBus("{{ServiceName}}", SampleConnectorMessage.create());
        scheduleNextAlarm();
    }
    // @sample-end
}
