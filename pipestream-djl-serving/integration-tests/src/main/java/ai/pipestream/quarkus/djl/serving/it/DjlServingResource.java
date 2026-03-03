package ai.pipestream.quarkus.djl.serving.it;

import ai.pipestream.quarkus.djl.serving.runtime.client.DjlServingClient;
import io.smallrye.mutiny.Uni;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.inject.Inject;
import jakarta.ws.rs.GET;
import jakarta.ws.rs.Path;
import jakarta.ws.rs.Produces;
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

    @GET
    @Path("/url")
    @Produces(MediaType.TEXT_PLAIN)
    public String getUrl() {
        return djlServingUrl;
    }

    @GET
    @Path("/ping")
    @Produces(MediaType.TEXT_PLAIN)
    public Uni<String> ping() {
        return client.ping();
    }
}
