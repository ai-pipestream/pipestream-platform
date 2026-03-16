package ai.pipestream.server.constants;

/**
 * Canonical service names as registered in Consul.
 * These are the authoritative identifiers used for service discovery,
 * pipeline graph module references, and gRPC routing.
 *
 * <p>When referencing a service anywhere in the codebase, use these constants
 * instead of hardcoded strings to ensure consistency.
 */
public enum PipestreamServices {

    // ====== Core Services ======

    /** Account management service. */
    ACCOUNT_MANAGER("account-manager", Category.CORE),

    /** Connector registry and API key management. */
    CONNECTOR_ADMIN("connector-admin", Category.CORE),

    /** Stateless document ingestion gateway. */
    CONNECTOR_INTAKE("connector-intake", Category.CORE),

    /** Pipeline orchestration engine. */
    ENGINE("engine", Category.CORE),

    /** Kafka consumer sidecar for engine document hydration. */
    ENGINE_KAFKA_SIDECAR("engine-kafka-sidecar", Category.CORE),

    /** OpenSearch index and metadata management. */
    OPENSEARCH_MANAGER("opensearch-manager", Category.CORE),

    /** Service and module registration (Consul + Apicurio). */
    PLATFORM_REGISTRATION("platform-registration", Category.CORE),

    /** Document storage, versioning, and S3 management. */
    REPOSITORY("repository", Category.CORE),

    // ====== Modules (Processing Steps) ======

    /** Document parser (Tika/Docling text and metadata extraction). */
    PARSER("parser", Category.MODULE),

    /** Text chunker (token, sentence, character splitting). */
    CHUNKER("chunker", Category.MODULE),

    /** Vector embedder (MiniLM, E5, BGE-M3, etc.). */
    EMBEDDER("embedder", Category.MODULE),

    /** Combined chunking + embedding orchestrator. */
    SEMANTIC_MANAGER("semantic-manager", Category.MODULE),

    /** OpenSearch document indexing sink. */
    OPENSEARCH_SINK("opensearch-sink", Category.MODULE),

    /** Module testing and pipeline probe sidecar. */
    MODULE_TESTING_SIDECAR("module-testing-sidecar", Category.MODULE),

    // ====== Connectors ======

    /** S3-compatible storage crawler. */
    S3_CONNECTOR("s3-connector", Category.CONNECTOR),

    // ====== Infrastructure ======

    /** OpenSearch REST API (registered by Consul config, not a Pipestream service). */
    OPENSEARCH("opensearch", Category.INFRASTRUCTURE),

    /** OpenSearch gRPC API. */
    OPENSEARCH_GRPC("opensearch-grpc", Category.INFRASTRUCTURE);

    private final String serviceName;
    private final Category category;

    PipestreamServices(String serviceName, Category category) {
        this.serviceName = serviceName;
        this.category = category;
    }

    /** The Consul service name used for discovery and registration. */
    public String serviceName() {
        return serviceName;
    }

    /** The category of this service. */
    public Category category() {
        return category;
    }

    @Override
    public String toString() {
        return serviceName;
    }

    /** Find a service by its Consul name. Returns null if not found. */
    public static PipestreamServices fromServiceName(String name) {
        for (PipestreamServices s : values()) {
            if (s.serviceName.equals(name)) {
                return s;
            }
        }
        return null;
    }

    public enum Category {
        CORE,
        MODULE,
        CONNECTOR,
        INFRASTRUCTURE
    }
}
