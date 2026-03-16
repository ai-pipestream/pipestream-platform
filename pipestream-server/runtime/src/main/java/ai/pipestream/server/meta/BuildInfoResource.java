package ai.pipestream.server.meta;

import jakarta.inject.Inject;
import jakarta.ws.rs.GET;
import jakarta.ws.rs.Path;
import jakarta.ws.rs.Produces;
import jakarta.ws.rs.core.MediaType;
import jakarta.ws.rs.core.Response;

@Path("/api/v1/meta")
public class BuildInfoResource {

    @Inject
    BuildInfoProvider buildInfoProvider;

    @GET
    @Path("/build")
    @Produces(MediaType.APPLICATION_JSON)
    public Response getBuildInfo() {
        return Response.ok(buildInfoProvider.endpointPayload()).build();
    }
}
