package ai.pipestream.quarkus.djl.serving.runtime;

import ai.pipestream.djl.serving.v1.DjlModelService;
import ai.pipestream.djl.serving.v1.GetModelHealthRequest;
import ai.pipestream.djl.serving.v1.GetModelHealthResponse;
import ai.pipestream.djl.serving.v1.ListAvailableModelsRequest;
import ai.pipestream.djl.serving.v1.ListAvailableModelsResponse;
import com.google.protobuf.Timestamp;
import io.quarkus.grpc.GrpcService;
import io.smallrye.mutiny.Uni;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.inject.Inject;

@GrpcService
@ApplicationScoped
public class DjlModelGrpcService implements DjlModelService {

    @Inject
    DjlModelHealthAggregator aggregator;

    @Override
    public Uni<ListAvailableModelsResponse> listAvailableModels(ListAvailableModelsRequest request) {
        return aggregator.aggregateAllModels()
                .map(result -> ListAvailableModelsResponse.newBuilder()
                        .addAllExpectedModels(result.expectedModels())
                        .addAllObservedModels(result.observedModels())
                        .addAllModelHealth(result.modelHealth())
                        .setInstancesChecked(result.instancesChecked())
                        .setInstancesReachable(result.instancesReachable())
                        .setDiscoverySuccessful(result.discoverySuccessful())
                        .setObservedAt(toTimestamp(result.observedAt()))
                        .addAllUnresolvedErrors(result.unresolvedErrors())
                        .build());
    }

    @Override
    public Uni<GetModelHealthResponse> getModelHealth(GetModelHealthRequest request) {
        return aggregator.aggregateModelHealth(request.getModelNamesList(), request.getIncludeUnexpectedModels())
                .map(result -> GetModelHealthResponse.newBuilder()
                        .addAllModelHealth(result.modelHealth())
                        .setInstancesChecked(result.instancesChecked())
                        .setInstancesReachable(result.instancesReachable())
                        .setDiscoverySuccessful(result.discoverySuccessful())
                        .setObservedAt(toTimestamp(result.observedAt()))
                        .addAllUnresolvedErrors(result.unresolvedErrors())
                        .build());
    }

    private static Timestamp toTimestamp(java.time.Instant instant) {
        return Timestamp.newBuilder()
                .setSeconds(instant.getEpochSecond())
                .setNanos(instant.getNano())
                .build();
    }
}
