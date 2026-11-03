package {{WebPackageName}}.resources;

import jakarta.inject.Inject;
import jakarta.inject.Singleton;
import jakarta.ws.rs.DELETE;
import jakarta.ws.rs.GET;
import jakarta.ws.rs.PATCH;
import jakarta.ws.rs.POST;
import jakarta.ws.rs.PUT;
import jakarta.ws.rs.Path;
import jakarta.ws.rs.PathParam;
import jakarta.ws.rs.Produces;
import jakarta.ws.rs.core.Context;
import jakarta.ws.rs.core.MediaType;
import jakarta.ws.rs.core.Response;
import jakarta.ws.rs.core.UriInfo;

import {{WebPackageName}}.AepEngineProvider;
import {{WebPackageName}}.rest.*;

/**
 * REST endpoints at {@code {{EndpointPath}}}, added with App Builder's
 * {@code add_rest_endpoint}. Each method's body is the developer's code.
 *
 * <p>A body talks to Rumi in one of three ways: request-reply with this
 * webservice's own engine ({@code _engineProvider.getEngine()
 * .injectRequestAndWaitForReply(...)}), request-reply with another service
 * through its generated client (which {@code add_service_client} must have
 * added first), or a one-way message to a downstream service.
 * {@link RestJson} reads a request into a message and renders a reply.
 *
 * <p>Change these with {@code update_rest_endpoint} and
 * {@code remove_rest_endpoint} rather than by hand: the marker comment around
 * each method is how App Builder finds it. Each path gets its own class so that
 * no other resource's path prefix can shadow it.
 */
@Singleton
@Path("{{EndpointPath}}")
public class {{ResourceClassName}} {
    @Inject
    AepEngineProvider _engineProvider;
}
