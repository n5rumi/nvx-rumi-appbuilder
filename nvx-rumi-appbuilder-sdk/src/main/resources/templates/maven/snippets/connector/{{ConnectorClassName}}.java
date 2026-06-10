package {{AppPackageName}}.{{ServicePackageName}}.connector;

import java.util.Properties;

import com.neeve.config.Config;
import com.neeve.sma.MessageView;
import com.neeve.sma.SmaPermanentException;
import com.neeve.sma.spi.connector.Connector;
import com.neeve.trace.Tracer;

/**
 * Custom connector "{{ConnectorTokenName}}" snapped into the {{ServiceName}}
 * service. A connector is a user-authored Rumi message-bus binding that
 * bridges the service to an external system.
 *
 * <p>It is wired via the {@code connector://} bus
 * {@code "{{ServiceName}}-{{ConnectorTokenName}}"} declared in {@code config.xml}
 * (the {@code <buses>} block) and referenced from the service app's
 * {@code <messaging>} block. The service sends outbound messages to it with
 * {@code sendMessageThroughBus("{{ServiceName}}-{{ConnectorTokenName}}", message)};
 * inbound messages this connector emits arrive at the service's
 * {@code @EventHandler} methods.
 *
 * <p>Fill in the inbound (source) and/or outbound (sink) paths below.
 */
public class {{ConnectorClassName}} implements Connector {
    private Tracer _tracer;
    private BindingCallback _bindingCallback;

    @Override
    final public void open(Properties config) throws Exception, SmaPermanentException {
        _tracer = Tracer.get("{{AppTokenName}}.{{ServicePackageName}}.connector.{{ConnectorTokenName}}");

        // Example: read a configured endpoint and connect to the external system.
        // final String endpoint = Config.getValue("{{AppTokenName}}.{{ServicePackageName}}.connector.{{ConnectorTokenName}}.endpoint", null);
        // if (endpoint == null) {
        //     throw new SmaPermanentException("connector '{{ConnectorTokenName}}' endpoint has not been configured");
        // }
    }

    @Override
    final public void run(final BindingCallback callback) throws Exception {
        _bindingCallback = callback;

        // Inbound example (uncomment and adapt for a source connector):
        // while (running) {
        //     final SomeInboundMessage message = readFromExternalSystem();
        //     _bindingCallback.processInbound(message, 0L, 0L, 0L, null);
        // }
    }

    @Override
    final public void processOutbound(final MessageView view, final OutboundAcknowledger acknowledger, int flags) throws Exception {
        try {
            _tracer.log("<--" + view.toString(), Tracer.Level.VERBOSE);
            // Forward the outbound message to the external system here.
        }
        finally {
            acknowledger.acknowledge();
        }
    }

    @Override
    final public void close() throws Exception {
    }
}
