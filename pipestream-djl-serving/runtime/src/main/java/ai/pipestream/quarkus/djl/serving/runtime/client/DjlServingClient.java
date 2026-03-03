package ai.pipestream.quarkus.djl.serving.runtime.client;

import io.smallrye.mutiny.Uni;
import io.vertx.core.json.JsonObject;
import jakarta.ws.rs.Consumes;
import jakarta.ws.rs.GET;
import jakarta.ws.rs.POST;
import jakarta.ws.rs.Path;
import jakarta.ws.rs.PathParam;
import jakarta.ws.rs.Produces;
import jakarta.ws.rs.core.MediaType;
import org.eclipse.microprofile.rest.client.inject.RegisterRestClient;

@Path("/")
@RegisterRestClient(configKey = "djl-serving")
public interface DjlServingClient {

    @GET
    @Path("/ping")
    Uni<String> ping();

    @POST
    @Path("/predictions/{modelName}")
    @Consumes(MediaType.APPLICATION_JSON)
    @Produces(MediaType.APPLICATION_JSON)
    Uni<JsonObject> predict(@PathParam("modelName") String modelName, JsonObject input);
}
