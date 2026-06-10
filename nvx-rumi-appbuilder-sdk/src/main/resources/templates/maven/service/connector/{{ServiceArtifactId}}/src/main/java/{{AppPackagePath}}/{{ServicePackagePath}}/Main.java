package {{AppPackageName}}.{{ServicePackageName}};

import com.neeve.aep.AepEngine;
import com.neeve.aep.AepMessageSender;
import com.neeve.aep.annotations.EventHandler;
import com.neeve.aep.event.AepMessagingPrestartEvent;
import com.neeve.server.app.annotations.AppInjectionPoint;

import {{AppPackageName}}.roe.*;
import {{AppPackageName}}.{{ServicePackageName}}.messages.*;

public class Main {
    private AepEngine _engine;
    private AepMessageSender _messageSender;

    final private void scheduleNextAlarm() {
        _engine.scheduleMessage(AlarmMessage.create(), 100);
    }

    @AppInjectionPoint
    public void setEngine(AepEngine engine) {
        _engine = engine;
    }

    @AppInjectionPoint
    public void setMessageSender(AepMessageSender messageSender) {
        _messageSender = messageSender;
    }

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
}
