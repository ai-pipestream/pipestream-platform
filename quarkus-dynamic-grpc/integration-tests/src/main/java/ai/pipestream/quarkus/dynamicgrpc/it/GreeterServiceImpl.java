package ai.pipestream.quarkus.dynamicgrpc.it;

import ai.pipestream.quarkus.dynamicgrpc.it.proto.DownloadBytesReply;
import ai.pipestream.quarkus.dynamicgrpc.it.proto.DownloadBytesRequest;
import ai.pipestream.quarkus.dynamicgrpc.it.proto.EchoBytesReply;
import ai.pipestream.quarkus.dynamicgrpc.it.proto.EchoBytesRequest;
import ai.pipestream.quarkus.dynamicgrpc.it.proto.Greeter;
import ai.pipestream.quarkus.dynamicgrpc.it.proto.HelloReply;
import ai.pipestream.quarkus.dynamicgrpc.it.proto.HelloRequest;
import com.google.protobuf.ByteString;
import io.quarkus.grpc.GrpcService;
import io.smallrye.mutiny.Uni;

import java.util.Arrays;

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

    /**
     * Reports back the byte count it received. The Quarkus-managed copy of
     * the Greeter is registered for completeness; LargePayloadConcurrencyTest
     * uses its own standalone {@code ServerBuilder}-built server, so this
     * impl is effectively a fallback rather than the test path.
     */
    @Override
    public Uni<EchoBytesReply> echoBytes(EchoBytesRequest request) {
        return Uni.createFrom().item(
                EchoBytesReply.newBuilder()
                        .setReceivedBytes(request.getPayload().size())
                        .build()
        );
    }

    /**
     * Sums {@code payload.size()} across every message on the stream and
     * returns the total once the client half-closes. Same fallback role as
     * {@link #echoBytes} — the streaming IT brings up its own server.
     */
    @Override
    public Uni<EchoBytesReply> uploadStream(io.smallrye.mutiny.Multi<EchoBytesRequest> request) {
        return request
                .onItem().transform(req -> (long) req.getPayload().size())
                .collect().with(java.util.stream.Collectors.summingLong(Long::longValue))
                .onItem().transform(total -> EchoBytesReply.newBuilder()
                        .setReceivedBytes(total)
                        .build());
    }

    /**
     * Builds and returns a server-allocated payload of the requested size.
     * Like {@link #echoBytes}, this is the Quarkus-managed fallback impl;
     * {@code DownloadBytesTest} uses its own standalone server.
     */
    @Override
    public Uni<DownloadBytesReply> downloadBytes(DownloadBytesRequest request) {
        byte[] data = new byte[request.getSizeBytes()];
        Arrays.fill(data, (byte) 0x37);
        return Uni.createFrom().item(
                DownloadBytesReply.newBuilder()
                        .setPayload(ByteString.copyFrom(data))
                        .build()
        );
    }
}
