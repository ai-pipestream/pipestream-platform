package ai.pipestream.quarkus.dynamicgrpc.auth;

/**
 * Interface for providing authentication tokens to dynamic gRPC clients.
 * <p>
 * Implement this interface as a CDI bean to automatically inject authentication
 * tokens into all dynamic gRPC client requests. The token will be added to the
 * gRPC metadata (headers) for each call.
 * </p>
 * <p>
 * Example implementation:
 * </p>
 * <pre>
 * &#64;ApplicationScoped
 * public class JwtTokenProvider implements AuthTokenProvider {
 *     &#64;Override
 *     public String getToken() {
 *         return fetchJwtFromVault();
 *     }
 * }
 * </pre>
 */
public interface AuthTokenProvider {

    /**
     * Provides an authentication token for gRPC requests.
     * <p>
     * This method is called for each gRPC request, so implementations should:
     * </p>
     * <ul>
     *   <li>Be fast - avoid blocking operations if possible</li>
     *   <li>Cache tokens if appropriate (e.g., JWT with expiration)</li>
     *   <li>Return null or empty string if no token is available</li>
     * </ul>
     *
     * @return the authentication token, or null/empty if not available
     */
    String getToken();

    /**
     * Optional: Provides the service name context for the current request.
     * <p>
     * This allows the token provider to return different tokens based on
     * which service is being called. Default implementation returns null.
     * </p>
     *
     * @param serviceName the name of the service being called
     * @return the authentication token for this specific service
     */
    default String getToken(String serviceName) {
        return getToken();
    }
}
