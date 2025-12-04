package ai.pipestream.quarkus.dynamicgrpc;

import ai.pipestream.quarkus.dynamicgrpc.GrpcClientFactory;
import ai.pipestream.quarkus.dynamicgrpc.base.DynamicGrpcClientFactoryTestBase;
import io.quarkus.test.junit.QuarkusTest;
import jakarta.inject.Inject;
import org.junit.jupiter.api.BeforeEach;

/**
 * Core integration tests for DynamicGrpcClientFactory with real Consul discovery.
 * Tests dynamic client creation with full service discovery stack - no mocks.
 * <p>
 * Migrated from platform-libraries/libraries/dynamic-grpc test suite.
 * </p>
 */
@QuarkusTest
class DynamicGrpcClientFactoryTest extends DynamicGrpcClientFactoryTestBase {

    @Inject
    GrpcClientFactory factory;

    @BeforeEach
    void setupTest() {
        // Register our test gRPC service in real Consul
        registerServiceInConsul();
    }

    @Override
    protected GrpcClientFactory getFactory() {
        return factory;
    }
}
