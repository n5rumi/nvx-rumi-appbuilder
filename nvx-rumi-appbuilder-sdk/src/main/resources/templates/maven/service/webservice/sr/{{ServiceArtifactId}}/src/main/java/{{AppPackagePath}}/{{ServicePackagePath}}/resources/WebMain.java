package {{AppPackageName}}.{{ServicePackageName}}.resources;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;

import jakarta.inject.Singleton;
import jakarta.ws.rs.GET;
import jakarta.ws.rs.Path;
import jakarta.ws.rs.Produces;
import jakarta.ws.rs.core.MediaType;
import jakarta.ws.rs.core.Response;

/**
 * The service's liveness probe. Its REST endpoints are added alongside it, each
 * in a resource class of its own, with {@code rumi dev rest-endpoint add} (or
 * App Builder's {@code add_rest_endpoint}): an endpoint's body is your code,
 * which can do request-reply with this service's own engine, request-reply with
 * another service through its client (after {@code rumi dev service-client add}),
 * or send a one-way message. The app's README shows one of each request-reply
 * kind.
 */
@Singleton
@Path("/{{ServiceTokenName}}/v1")
public class WebMain {
    private static final ObjectMapper MAPPER = new ObjectMapper();

    /** Liveness probe. Answers without touching the engine. */
    @GET
    @Path("/health")
    @Produces(MediaType.APPLICATION_JSON)
    public Response health() {
        final ObjectNode result = MAPPER.createObjectNode();
        result.put("status", "ok");
        return Response.ok(result.toString()).type(MediaType.APPLICATION_JSON).build();
    }
}
