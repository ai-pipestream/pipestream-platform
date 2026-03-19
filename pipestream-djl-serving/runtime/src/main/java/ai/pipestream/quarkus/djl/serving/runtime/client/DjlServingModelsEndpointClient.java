package ai.pipestream.quarkus.djl.serving.runtime.client;

import io.smallrye.mutiny.Uni;
import io.vertx.core.json.JsonObject;
import jakarta.ws.rs.GET;
import jakarta.ws.rs.Path;
import jakarta.ws.rs.Produces;
import jakarta.ws.rs.core.MediaType;

@Path("/")
public interface DjlServingModelsEndpointClient {

    @GET
    @Path("/models")
    @Produces(MediaType.APPLICATION_JSON)
    Uni<JsonObject> listModels();
}
