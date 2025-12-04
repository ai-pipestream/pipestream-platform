package ai.pipestream.quarkus.dynamicgrpc.auth;

import ai.pipestream.quarkus.dynamicgrpc.config.DynamicGrpcConfig;
import io.grpc.*;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.enterprise.inject.Instance;
import jakarta.inject.Inject;
import org.jboss.logging.Logger;

/**
 * gRPC client interceptor that automatically adds authentication tokens to request metadata.
 * <p>
 * This interceptor is automatically applied to all dynamic gRPC clients when:
 * </p>
 * <ul>
 *   <li>Auth is enabled in configuration ({@code quarkus.dynamic-grpc.auth.enabled=true})</li>
 *   <li>An {@link AuthTokenProvider} bean is available</li>
 * </ul>
 * <p>
 * The token is added to the configured header (default: "Authorization") with the
 * configured scheme prefix (default: "Bearer ").
 * </p>
 */
@ApplicationScoped
public class AuthMetadataInterceptor implements ClientInterceptor {

    private static final Logger LOG = Logger.getLogger(AuthMetadataInterceptor.class);

    @Inject
    DynamicGrpcConfig config;

    @Inject
    Instance<AuthTokenProvider> tokenProviderInstance;

    /**
     * Intercepts gRPC calls to add authentication metadata.
     *
     * @param method the method being called
     * @param callOptions the call options
     * @param next the next handler in the chain
     * @return the intercepted call
     */
    @Override
    public <ReqT, RespT> ClientCall<ReqT, RespT> interceptCall(
            MethodDescriptor<ReqT, RespT> method,
            CallOptions callOptions,
            Channel next) {

        // Only add auth if enabled and provider is available
        if (!config.auth().enabled() || !tokenProviderInstance.isResolvable()) {
            return next.newCall(method, callOptions);
        }

        AuthTokenProvider tokenProvider = tokenProviderInstance.get();
        String token = tokenProvider.getToken();

        // Skip if no token available
        if (token == null || token.isBlank()) {
            LOG.tracef("No auth token available for method: %s", method.getFullMethodName());
            return next.newCall(method, callOptions);
        }

        // Add token to metadata
        ClientCall<ReqT, RespT> call = next.newCall(method, callOptions);
        return new ForwardingClientCall.SimpleForwardingClientCall<>(call) {
            @Override
            public void start(Listener<RespT> responseListener, Metadata headers) {
                String headerName = config.auth().headerName();
                String tokenValue = config.auth().schemePrefix() + token;

                headers.put(Metadata.Key.of(headerName, Metadata.ASCII_STRING_MARSHALLER), tokenValue);

                LOG.tracef("Added auth token to header '%s' for method: %s", headerName, method.getFullMethodName());

                super.start(responseListener, headers);
            }
        };
    }
}
