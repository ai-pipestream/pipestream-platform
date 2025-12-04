package ai.pipestream.quarkus.dynamicgrpc.it;

import ai.pipestream.quarkus.dynamicgrpc.it.proto.Greeter;
import ai.pipestream.quarkus.dynamicgrpc.it.proto.HelloReply;
import ai.pipestream.quarkus.dynamicgrpc.it.proto.HelloRequest;
import io.quarkus.grpc.GrpcService;
import io.smallrye.mutiny.Uni;

/**
 * Implementation of the Greeter gRPC service used by integration tests.
 */

/**
 * Implementation of the Greeter gRPC service.
 * Provides simple greeting functionality for testing purposes.
 */
@GrpcService
public class GreeterServiceImpl implements Greeter {

    /**
     * Handles the sayHello gRPC call by creating a greeting message.
     *
     * @param request The HelloRequest containing the name to greet
     * @return A Uni containing the HelloReply with the greeting message
     */
    @Override
    public Uni<HelloReply> sayHello(HelloRequest request) {
        // Build a friendly greeting; simple echo service for tests
        String message = "Hello " + request.getName();
        return Uni.createFrom().item(
                HelloReply.newBuilder()
                        .setMessage(message)
                        .build()
        );
    }
}
