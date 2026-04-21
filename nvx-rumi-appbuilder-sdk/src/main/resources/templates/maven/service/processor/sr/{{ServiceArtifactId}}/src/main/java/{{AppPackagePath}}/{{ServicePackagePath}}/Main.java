package {{AppPackageName}}.{{ServicePackageName}};

import com.neeve.aep.AepEngine;
import com.neeve.aep.AepMessageSender;
import com.neeve.aep.IAepApplicationStateFactory;
import com.neeve.aep.annotations.EventHandler;
import com.neeve.server.app.annotations.AppHAPolicy;
import com.neeve.server.app.annotations.AppInjectionPoint;
import com.neeve.server.app.annotations.AppStateFactoryAccessor;
import com.neeve.sma.MessageView;

import {{AppPackageName}}.roe.*;
import {{AppPackageName}}.{{ServicePackageName}}.messages.*;
import {{AppPackageName}}.{{ServicePackageName}}.state.*;

@AppHAPolicy(value = AepEngine.HAPolicy.{{ServiceHAModel}})
public class Main {
    private AepMessageSender _messageSender;

    @AppStateFactoryAccessor
    public IAepApplicationStateFactory getStateFactory() {
        return new IAepApplicationStateFactory() {
            @Override
            final public Repository createState(final MessageView view) {
                return Repository.create();
            }
        };
    }

    @AppInjectionPoint
    public void setMessageSender(AepMessageSender messageSender) {
        _messageSender = messageSender;
    }
}
