package {{AppPackageName}}.{{ServicePackageName}}.resources;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;

import jakarta.inject.Inject;
import jakarta.inject.Singleton;
import jakarta.ws.rs.GET;
import jakarta.ws.rs.Path;
import jakarta.ws.rs.Produces;
import jakarta.ws.rs.QueryParam;
import jakarta.ws.rs.core.MediaType;
import jakarta.ws.rs.core.Response;

import {{AppPackageName}}.{{ServicePackageName}}.AepEngineProvider;
import {{AppPackageName}}.{{ServicePackageName}}.messages.EchoRequest;
import {{AppPackageName}}.{{ServicePackageName}}.messages.EchoResponse;

/**
 * JAX-RS resource exposing the service over HTTP. Each endpoint builds a Rumi
 * request message, injects it into the engine with
 * {@code injectRequestAndWaitForReply}, and renders the correlated reply as
 * JSON. Add more {@code @GET}/{@code @POST} methods following the {@code echo}
 * pattern.
 */
@Singleton
@Path("/{{ServiceTokenName}}/v1")
public class WebMain {
    private static final ObjectMapper MAPPER = new ObjectMapper();
    private static final int REQUEST_TIMEOUT_MS = 30000;

    @Inject
    AepEngineProvider _engineProvider;

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
}
