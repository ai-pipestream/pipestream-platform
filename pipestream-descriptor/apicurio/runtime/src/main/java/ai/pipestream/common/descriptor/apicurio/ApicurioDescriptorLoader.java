package ai.pipestream.common.descriptor.apicurio;

import ai.pipestream.common.descriptor.DescriptorLoader;
import com.google.protobuf.Descriptors.FileDescriptor;
import io.apicurio.registry.resolver.SchemaParser;
import io.apicurio.registry.rest.client.RegistryClient;
import io.apicurio.registry.rest.client.models.ArtifactSearchResults;
import io.apicurio.registry.rest.client.models.SearchedArtifact;
import io.apicurio.registry.serde.protobuf.ProtobufSchemaParser;
import io.apicurio.registry.utils.protobuf.schema.ProtobufSchema;
import org.jboss.logging.Logger;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.concurrent.ConcurrentHashMap;

/**
 * Loads Protocol Buffer descriptors from Apicurio Schema Registry v3.
 */
public class ApicurioDescriptorLoader implements DescriptorLoader {

    private static final Logger LOG = Logger.getLogger(ApicurioDescriptorLoader.class);

    private final RegistryClient client;
    private final String groupId;
    private final SchemaParser<ProtobufSchema, ?> schemaParser;
    private final ConcurrentHashMap<String, FileDescriptor> cache = new ConcurrentHashMap<>();

    public ApicurioDescriptorLoader(RegistryClient client, String groupId) {
        this.client = client;
        this.groupId = groupId;
        this.schemaParser = new ProtobufSchemaParser<>();
    }

    /**
     * Alternative constructor that accepts registry URL and group ID.
     * If registryClient is null, the loader will report as unavailable.
     *
     * @param registryUrl the Apicurio registry URL (informational when client is provided externally)
     * @param groupId the artifact group ID
     * @param registryClient the pre-built registry client, or null
     */
    public ApicurioDescriptorLoader(String registryUrl, String groupId, RegistryClient registryClient) {
        this(registryClient, groupId);
    }

    /**
     * Creates a new builder for ApicurioDescriptorLoader.
     */
    public static Builder builder() {
        return new Builder();
    }

    @Override
    public List<FileDescriptor> loadDescriptors() throws DescriptorLoadException {
        if (!isAvailable()) {
            throw new DescriptorLoadException("Apicurio registry client is not available");
        }
        List<FileDescriptor> results = new ArrayList<>();
        int offset = 0;
        int limit = 100;
        int total;

        try {
            do {
                int currentOffset = offset;
                ArtifactSearchResults searchResults = client.search().artifacts().get(config -> {
                    config.queryParameters.groupId = groupId;
                    config.queryParameters.artifactType = "PROTOBUF";
                    config.queryParameters.offset = currentOffset;
                    config.queryParameters.limit = limit;
                });

                if (searchResults == null || searchResults.getArtifacts() == null) {
                    break;
                }

                total = searchResults.getCount();

                for (SearchedArtifact artifact : searchResults.getArtifacts()) {
                    String artifactId = artifact.getArtifactId();
                    String artifactGroup = artifact.getGroupId() != null ? artifact.getGroupId() : groupId;
                    try {
                        FileDescriptor fd = fetchAndParse(artifactGroup, artifactId);
                        if (fd != null) {
                            results.add(fd);
                            cache.put(artifactId, fd);
                        }
                    } catch (Exception e) {
                        LOG.warnf("Failed to load descriptor for artifact %s/%s: %s",
                            artifactGroup, artifactId, e.getMessage());
                    }
                }

                offset += limit;
            } while (offset < total);

        } catch (Exception e) {
            throw new DescriptorLoadException("Failed to search Apicurio for PROTOBUF artifacts", e);
        }

        LOG.infof("Loaded %d protobuf descriptors from Apicurio group '%s'", results.size(), groupId);
        return results;
    }

    @Override
    public FileDescriptor loadDescriptor(String name) throws DescriptorLoadException {
        if (!isAvailable()) {
            throw new DescriptorLoadException("Apicurio registry client is not available");
        }
        return cache.computeIfAbsent(name, n -> {
            try {
                // Heuristic 1: name is the artifactId in the configured group
                LOG.debugf("Heuristic 1: Fetching artifactId=%s from group=%s", n, groupId);
                try {
                    return fetchAndParse(groupId, n);
                } catch (Exception e) {
                    // Ignore
                }

                // Heuristic 2: name contains package info, try splitting into groupId and artifactId
                if (n.contains(".")) {
                    int lastDot = n.lastIndexOf('.');
                    String g = n.substring(0, lastDot);
                    String a = n.substring(lastDot + 1);
                    LOG.debugf("Heuristic 2: Fetching artifactId=%s from group=%s", a, g);
                    try {
                        return fetchAndParse(g, a);
                    } catch (Exception e) {
                        // Ignore
                    }
                }

                // Heuristic 3: try "default" group if not already tried
                if (!"default".equals(groupId)) {
                    LOG.debugf("Heuristic 3: Fetching artifactId=%s from group=default", n);
                    try {
                        return fetchAndParse("default", n);
                    } catch (Exception e) {
                        // Ignore
                    }
                }

                return null;
            } catch (Exception e) {
                LOG.warnf("Failed to resolve descriptor for %s: %s", n, e.getMessage());
                return null;
            }
        });
    }

    private FileDescriptor fetchAndParse(String gid, String aid) throws Exception {
        var inputStream = client.groups().byGroupId(gid).artifacts().byArtifactId(aid).versions().byVersionExpression("branch=latest").content().get();
        if (inputStream == null) {
            throw new Exception("Artifact content not found");
        }
        try (inputStream) {
            byte[] bytes = inputStream.readAllBytes();
            ProtobufSchema parsed = schemaParser.parseSchema(bytes, Collections.emptyMap());
            return parsed.getFileDescriptor();
        }
    }

    @Override
    public boolean isAvailable() {
        return client != null;
    }

    @Override
    public String getLoaderType() {
        return "Apicurio Schema Registry";
    }

    /**
     * Builder for ApicurioDescriptorLoader with validation.
     */
    public static class Builder {
        private String registryUrl;
        private String groupId;
        private RegistryClient registryClient;

        public Builder registryUrl(String registryUrl) {
            this.registryUrl = registryUrl;
            return this;
        }

        public Builder groupId(String groupId) {
            this.groupId = groupId;
            return this;
        }

        public Builder registryClient(RegistryClient registryClient) {
            this.registryClient = registryClient;
            return this;
        }

        public ApicurioDescriptorLoader build() {
            if (registryUrl == null || registryUrl.isBlank()) {
                throw new IllegalArgumentException("Registry URL is required");
            }
            if (groupId == null || groupId.isBlank()) {
                throw new IllegalArgumentException("Group ID is required");
            }
            return new ApicurioDescriptorLoader(registryUrl, groupId, registryClient);
        }
    }
}
