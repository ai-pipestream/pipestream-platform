package ai.pipestream.quarkus.djl.serving.runtime.client;

import io.smallrye.mutiny.Uni;
import io.vertx.core.json.JsonArray;
import jakarta.ws.rs.GET;
import jakarta.ws.rs.Path;
import jakarta.ws.rs.PathParam;
import jakarta.ws.rs.QueryParam;

@Path("/v1/health/service")
public interface ConsulDiscoveryClient {

    @GET
    @Path("/{serviceName}")
    Uni<JsonArray> listServiceInstances(@PathParam("serviceName") String serviceName,
                                        @QueryParam("passing") boolean passingOnly,
                                        @QueryParam("tag") String tag,
                                        @QueryParam("dc") String datacenter);
}
