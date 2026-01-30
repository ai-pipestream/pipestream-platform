package ai.pipestream.quarkus.opensearch.init;

import org.opensearch.client.opensearch.OpenSearchClient;

/**
 * Interface for OpenSearch initialization hooks.
 * 
 * <p>Implement this interface to provide custom initialization logic that runs
 * when the application starts. Examples include:
 * <ul>
 *   <li>Creating index templates</li>
 *   <li>Setting up index mappings</li>
 *   <li>Configuring k-NN settings</li>
 *   <li>Creating initial indices</li>
 *   <li>Registering ingest pipelines</li>
 * </ul>
 * 
 * <p>Implementations are discovered via CDI and executed in priority order
 * (lower priority values run first).
 * 
 * <p>Example implementation:
 * <pre>
 * &#64;ApplicationScoped
 * public class MyIndexInitializer implements OpenSearchInitializer {
 *     
 *     &#64;Override
 *     public void initialize(OpenSearchClient client) throws Exception {
 *         // Create index if not exists
 *         if (!client.indices().exists(e -> e.index("my-index")).value()) {
 *             client.indices().create(c -> c.index("my-index"));
 *         }
 *     }
 *     
 *     &#64;Override
 *     public int priority() {
 *         return 100;
 *     }
 * }
 * </pre>
 */
public interface OpenSearchInitializer {

    /**
     * Initialize OpenSearch resources.
     * 
     * <p>This method is called once during application startup after the
     * OpenSearch client is available. Implementations should be idempotent
     * as they may be called multiple times during development with live reload.
     * 
     * @param client the OpenSearch client
     * @throws Exception if initialization fails
     */
    void initialize(OpenSearchClient client) throws Exception;

    /**
     * Get the priority of this initializer.
     * 
     * <p>Lower values run first. Default is 1000.
     * Use values like:
     * <ul>
     *   <li>0-99: Critical infrastructure (templates, settings)</li>
     *   <li>100-499: Index creation</li>
     *   <li>500-999: Data seeding</li>
     *   <li>1000+: Application-specific initialization</li>
     * </ul>
     * 
     * @return the priority value
     */
    default int priority() {
        return 1000;
    }

    /**
     * Get the name of this initializer for logging purposes.
     * 
     * @return the initializer name
     */
    default String name() {
        return getClass().getSimpleName();
    }

    /**
     * Whether this initializer should fail the application startup if it fails.
     * 
     * <p>If true (default), any exception thrown by {@link #initialize} will
     * prevent the application from starting. If false, the error is logged
     * but the application continues.
     * 
     * @return true if failures should be fatal
     */
    default boolean failOnError() {
        return true;
    }
}
