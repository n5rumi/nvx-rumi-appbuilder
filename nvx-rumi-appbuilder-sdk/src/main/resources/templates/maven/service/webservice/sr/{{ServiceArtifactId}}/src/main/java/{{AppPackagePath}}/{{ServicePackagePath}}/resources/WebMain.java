package {{AppPackageName}}.{{ServicePackageName}}.resources;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;

import jakarta.inject.Inject;
import jakarta.inject.Singleton;
import jakarta.ws.rs.GET;
import jakarta.ws.rs.Path;
import jakarta.ws.rs.Produces;
// @sample-begin
import jakarta.ws.rs.QueryParam;
// @sample-end
import jakarta.ws.rs.core.MediaType;
import jakarta.ws.rs.core.Response;

import {{AppPackageName}}.{{ServicePackageName}}.AepEngineProvider;
// @sample-begin
import {{AppPackageName}}.{{ServicePackageName}}.messages.EchoRequest;
import {{AppPackageName}}.{{ServicePackageName}}.messages.EchoResponse;
// @sample-end

/**
 * JAX-RS resource exposing the service over HTTP. Each endpoint builds a Rumi
 * request message, injects it into the engine with
 * {@code injectRequestAndWaitForReply}, and renders the correlated reply as
 * JSON.
 *
 * <p>That call blocks the HTTP thread until the engine's matching
 * {@code @EventHandler} answers with {@code sendReply}, or until the timeout
 * expires and it returns null -- so every endpoint must handle the null. The
 * engine is reached through the injected {@link AepEngineProvider} rather than
 * held directly, because Jersey constructs this resource and Rumi owns the
 * engine.
 */
@Singleton
@Path("/{{ServiceTokenName}}/v1")
public class WebMain {
    private static final ObjectMapper MAPPER = new ObjectMapper();
    // @sample-begin
    private static final int REQUEST_TIMEOUT_MS = 30000;
    // @sample-end

    @Inject
    AepEngineProvider _engineProvider;

    /** Liveness probe. Answers without touching the engine. */
    @GET
    @Path("/health")
    @Produces(MediaType.APPLICATION_JSON)
    public Response health() {
        final ObjectNode result = MAPPER.createObjectNode();
        result.put("status", "ok");
        return Response.ok(result.toString()).type(MediaType.APPLICATION_JSON).build();
    }
    // @sample-begin

    @GET
    @Path("/echo")
    @Produces(MediaType.APPLICATION_JSON)
    public Response echo(@QueryParam("message") String message) {
        final EchoRequest request = EchoRequest.create();
        request.setText(message == null ? "" : message);

        final EchoResponse response = _engineProvider.getEngine()
                .<EchoResponse>injectRequestAndWaitForReply(request, REQUEST_TIMEOUT_MS);

        final ObjectNode result = MAPPER.createObjectNode();
        if (response == null) {
            result.put("error", "timed out waiting for engine reply");
            return Response.status(Response.Status.SERVICE_UNAVAILABLE)
                    .entity(result.toString()).type(MediaType.APPLICATION_JSON).build();
        }
        result.put("echo", response.getEcho());
        result.put("count", response.getCount());
        return Response.ok(result.toString()).type(MediaType.APPLICATION_JSON).build();
    }
    // @sample-end
}
