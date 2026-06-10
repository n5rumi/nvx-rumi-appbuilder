package {{AppPackageName}}.{{ServicePackageName}};

import com.neeve.aep.AepEngine;
import com.neeve.aep.AepMessageSender;
import com.neeve.aep.IAepApplicationStateFactory;
import com.neeve.aep.annotations.EventHandler;
import com.neeve.aep.event.AepEngineStoppedEvent;
import com.neeve.aep.event.AepMessagingPrestartEvent;
import com.neeve.config.Config;
import com.neeve.server.app.annotations.AppHAPolicy;
import com.neeve.server.app.annotations.AppInjectionPoint;
import com.neeve.server.app.annotations.AppStateFactoryAccessor;
import com.neeve.sma.MessageView;
import com.neeve.trace.Tracer;

import {{AppPackageName}}.roe.*;
import {{AppPackageName}}.{{ServicePackageName}}.messages.*;
import {{AppPackageName}}.{{ServicePackageName}}.state.*;

/**
 * A stateful Rumi microservice fronted by an embedded HTTP server.
 *
 * <p>The web layer ({@link {{AppPackageName}}.{{ServicePackageName}}.resources.WebMain})
 * turns HTTP requests into Rumi messages and injects them into this engine via
 * {@code injectRequestAndWaitForReply}. The {@code @EventHandler} methods below
 * run on the engine thread with exclusive access to the replicated
 * {@link Repository} state, and reply via {@code sendReply}. This is the same
 * shape as the nvx-accounts service.
 */
@AppHAPolicy(value = AepEngine.HAPolicy.{{ServiceHAModel}})
public class Main implements AepEngineProvider {
    private static final int DEFAULT_HTTP_PORT = {{ServiceHttpPort}};
    private static final String RESOURCE_PACKAGE = "{{AppPackageName}}.{{ServicePackageName}}.resources";

    private final Tracer _tracer = Tracer.get("{{AppTokenName}}.{{ServicePackageName}}");
    private AepEngine _engine;
    private AepMessageSender _messageSender;
    private HttpServer _httpServer;

    /** Exposes the engine to the JAX-RS layer (see {@link HttpServer}). */
    @Override
    public AepEngine getEngine() {
        return _engine;
    }

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
    public void setEngine(AepEngine engine) {
        _engine = engine;
    }

    @AppInjectionPoint
    public void setMessageSender(AepMessageSender messageSender) {
        _messageSender = messageSender;
    }

    /** Start the embedded HTTP server once messaging is about to come up. */
    @EventHandler
    final public void onMessagingPrestart(final AepMessagingPrestartEvent event) throws Exception {
        final int port = Config.getValue("{{AppTokenName}}.{{ServicePackageName}}.http.port", DEFAULT_HTTP_PORT);
        _httpServer = new HttpServer(port);
        _httpServer.start(this, RESOURCE_PACKAGE);
        _tracer.log("started HTTP server on port " + port, Tracer.Level.INFO);
    }

    /** Stop the embedded HTTP server when the engine stops. */
    @EventHandler
    final public void onEngineStopped(final AepEngineStoppedEvent event) throws Exception {
        if (_httpServer != null) {
            _httpServer.stop();
            _httpServer = null;
        }
    }

    /**
     * Sample request handler: runs on the engine thread, mutates replicated
     * state, and replies. The reply is correlated back to the blocked
     * {@code injectRequestAndWaitForReply} call in the web layer.
     */
    @EventHandler
    final public void onEchoRequest(final EchoRequest request) {
        final Repository repository = _engine.<Repository>getApplicationState(request);
        final long count = repository.getCount() + 1;
        repository.setCount(count);

        final EchoResponse response = EchoResponse.create();
        response.setEcho(request.getText());
        response.setCount(count);
        _messageSender.sendReply(request, response);
    }
}
