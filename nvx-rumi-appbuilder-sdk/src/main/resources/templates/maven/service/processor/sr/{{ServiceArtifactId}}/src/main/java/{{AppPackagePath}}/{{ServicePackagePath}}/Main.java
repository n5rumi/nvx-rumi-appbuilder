package {{AppPackageName}}.{{ServicePackageName}};

import com.neeve.aep.AepEngine;
import com.neeve.aep.AepMessageSender;
import com.neeve.aep.IAepApplicationStateFactory;
import com.neeve.aep.annotations.EventHandler;
import com.neeve.server.app.annotations.AppHAPolicy;
import com.neeve.server.app.annotations.AppInjectionPoint;
import com.neeve.server.app.annotations.AppStateFactoryAccessor;
import com.neeve.sma.MessageView;

import {{AppPackageName}}.{{ServicePackageName}}.state.Repository;
// @sample-begin
import {{AppPackageName}}.roe.{{ServiceClassPrefix}}EchoRequest;
import {{AppPackageName}}.roe.{{ServiceClassPrefix}}EchoResponse;
// @sample-end

@AppHAPolicy(value = AepEngine.HAPolicy.{{ServiceHAModel}})
public class Main {
    private AepMessageSender _messageSender;
    // @sample-begin
    private AepEngine _engine;
    // @sample-end

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
    // @sample-begin

    @AppInjectionPoint
    public void setEngine(AepEngine engine) {
        _engine = engine;
    }

    /**
     * Sample request-reply operation, the {@code echo} operation in this
     * service's api.xml: counts the calls in replicated state and replies. A
     * webservice calls it through this service's generated client, after
     * {@code rumi dev service-client add}; see the app's README.
     */
    @EventHandler
    final public void on{{ServiceClassPrefix}}EchoRequest(final {{ServiceClassPrefix}}EchoRequest request) {
        final Repository repository = _engine.<Repository>getApplicationState(request);
        final long count = repository.getCount() + 1;
        repository.setCount(count);

        final {{ServiceClassPrefix}}EchoResponse response = {{ServiceClassPrefix}}EchoResponse.create();
        response.setEcho(request.getText());
        response.setCount(count);
        _messageSender.sendReply(request, response);
    }
    // @sample-end
}
