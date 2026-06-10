package {{AppPackageName}}.{{ServicePackageName}}.connector;

import java.util.Properties;

import com.neeve.config.Config;
import com.neeve.sma.MessageView;
import com.neeve.sma.SmaPermanentException;
import com.neeve.sma.spi.connector.Connector;
import com.neeve.trace.Tracer;

import {{AppPackageName}}.roe.*;
import {{AppPackageName}}.{{ServicePackageName}}.messages.*;

/**
 * A user-authored Rumi connector: a message-bus binding that bridges the
 * Rumi service to an external system. It is wired into the service via a
 * {@code connector://} bus binding in {@code config.xml} (the service's
 * {@code <buses>} block) plus the {@code <bus name="{{ServiceName}}"/>}
 * reference in the service app's {@code <messaging>} block.
 *
 * <p>The four lifecycle methods below are the full Connector contract. This
 * skeleton is intentionally minimal -- fill in the inbound (source) and/or
 * outbound (sink) paths for the external system you are integrating.
 */
public class Main implements Connector {
    private Tracer _tracer;
    private BindingCallback _bindingCallback;

    /**
     * Called once when the bus binding opens. Read any configuration the
     * connector needs (via {@link Config}) and establish connections to the
     * external system. Throw {@link SmaPermanentException} to fail the
     * binding permanently (no retry).
     */
    @Override
    final public void open(Properties config) throws Exception, SmaPermanentException {
        _tracer = Tracer.get("{{AppTokenName}}.{{ServicePackageName}}.connector");

        // Example: read a configured endpoint for the external system.
        // final String endpoint = Config.getValue("{{AppTokenName}}.{{ServicePackageName}}.connector.endpoint", null);
        // if (endpoint == null) {
        //     throw new SmaPermanentException("connector endpoint has not been configured");
        // }
    }

    /**
     * Called on a dedicated inbound driver thread. Store the
     * {@link BindingCallback} and, for an inbound (source) connector, block
     * here reading from the external system and emit each message into Rumi
     * via {@code callback.processInbound(...)}. For a purely outbound (sink)
     * connector, just store the callback and return.
     */
    @Override
    final public void run(final BindingCallback callback) throws Exception {
        _bindingCallback = callback;

        // Inbound example (uncomment and adapt for a source connector):
        // while (running) {
        //     final SomeInboundMessage message = readFromExternalSystem();
        //     _bindingCallback.processInbound(message, 0L, 0L, 0L, null);
        // }
    }

    /**
     * Called synchronously for every message the service sends through this
     * connector's bus (via {@code sendMessageThroughBus("{{ServiceName}}", ...)}).
     * Forward the message to the external system, then always acknowledge.
     */
    @Override
    final public void processOutbound(final MessageView view, final OutboundAcknowledger acknowledger, int flags) throws Exception {
        _tracer.log("<--" + view.toString(), Tracer.Level.VERBOSE);
        try {
            if (view instanceof SampleConnectorMessage) {
                // Forward the message to the external system here.
            }
        }
        finally {
            acknowledger.acknowledge();
        }
    }

    /**
     * Called once when the bus binding closes. Release any resources and
     * disconnect from the external system.
     */
    @Override
    final public void close() throws Exception {
    }
}
