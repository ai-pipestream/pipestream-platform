package ai.pipestream.quarkus.djl.serving.it;

import ai.pipestream.quarkus.djl.serving.runtime.client.DjlServingClient;
import io.smallrye.mutiny.Uni;
import io.vertx.core.json.JsonArray;
import io.vertx.core.json.JsonObject;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.inject.Inject;
import jakarta.ws.rs.GET;
import jakarta.ws.rs.Path;
import jakarta.ws.rs.Produces;
import jakarta.ws.rs.QueryParam;
import jakarta.ws.rs.core.MediaType;
import org.eclipse.microprofile.config.inject.ConfigProperty;
import org.eclipse.microprofile.rest.client.inject.RestClient;

@Path("/djl-serving-it")
@ApplicationScoped
public class DjlServingResource {

    @Inject
    @RestClient
    DjlServingClient client;

    @ConfigProperty(name = "pipestream.djl-serving.url")
    String djlServingUrl;

    @ConfigProperty(name = "pipestream.djl-serving.model-name", defaultValue = "all_MiniLM_L6_v2")
    String modelName;

    @GET
    @Path("/url")
    @Produces(MediaType.TEXT_PLAIN)
    public String getUrl() {
        return djlServingUrl;
    }

    @GET
    @Path("/model-name")
    @Produces(MediaType.TEXT_PLAIN)
    public String getModelName() {
        return modelName;
    }

    @GET
    @Path("/ping")
    @Produces(MediaType.TEXT_PLAIN)
    public Uni<String> ping() {
        return client.ping();
    }

    @GET
    @Path("/predict")
    @Produces(MediaType.APPLICATION_JSON)
    public Uni<JsonArray> predict(@QueryParam("text") String text) {
        String input = (text != null && !text.isBlank()) ? text : "The quick brown fox jumps over the lazy dog.";
        JsonObject body = new JsonObject().put("inputs", input);
        return client.predict(modelName, body);
    }
}
