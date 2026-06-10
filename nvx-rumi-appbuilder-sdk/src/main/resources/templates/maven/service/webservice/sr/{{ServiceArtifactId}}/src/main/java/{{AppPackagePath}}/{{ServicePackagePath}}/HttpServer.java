package {{AppPackageName}}.{{ServicePackageName}};

import org.eclipse.jetty.ee10.servlet.ServletContextHandler;
import org.eclipse.jetty.ee10.servlet.ServletHolder;
import org.eclipse.jetty.server.Server;
import org.glassfish.hk2.utilities.binding.AbstractBinder;
import org.glassfish.jersey.jackson.JacksonFeature;
import org.glassfish.jersey.server.ResourceConfig;
import org.glassfish.jersey.servlet.ServletContainer;

import com.neeve.trace.Tracer;

/**
 * Minimal embedded HTTP server: Jetty 12 hosting a Jersey 3.1 (Jakarta EE 10)
 * JAX-RS application. The supplied {@link AepEngineProvider} is bound into the
 * Jersey HK2 context so resource classes can {@code @Inject} it and reach the
 * Rumi engine.
 *
 * <p>This is deliberately small -- no auth, no CORS. Add a
 * {@code CrossOriginFilter} or an auth filter here when the service needs them
 * (see the nvx-accounts {@code HttpServer} for a fuller example).
 */
public final class HttpServer {
    private final Tracer _tracer = Tracer.get("{{AppTokenName}}.{{ServicePackageName}}.http");
    private final int _port;
    private Server _server;

    public HttpServer(final int port) {
        _port = port;
    }

    /**
     * Start the server, scanning {@code resourcePackage} for JAX-RS resources
     * and binding {@code engineProvider} for injection.
     */
    public void start(final AepEngineProvider engineProvider, final String resourcePackage) throws Exception {
        final ResourceConfig config = new ResourceConfig();
        config.packages(resourcePackage);
        config.register(JacksonFeature.class);
        config.register(new AbstractBinder() {
            @Override
            protected void configure() {
                bind(engineProvider).to(AepEngineProvider.class);
            }
        });

        final ServletContextHandler context = new ServletContextHandler();
        context.setContextPath("/");
        context.addServlet(new ServletHolder(new ServletContainer(config)), "/*");

        _server = new Server(_port);
        _server.setHandler(context);
        _server.start();
        _tracer.log("HTTP server listening on port " + _port, Tracer.Level.INFO);
    }

    public void stop() throws Exception {
        if (_server != null) {
            _server.stop();
            _server = null;
        }
    }

    public int getPort() {
        return _port;
    }
}
