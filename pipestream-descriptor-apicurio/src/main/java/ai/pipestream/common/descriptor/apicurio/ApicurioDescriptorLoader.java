package ai.pipestream.common.descriptor.apicurio;

import ai.pipestream.common.descriptor.DescriptorLoader;
import com.google.protobuf.Descriptors.FileDescriptor;
import io.apicurio.registry.resolver.ParsedSchema;
import io.apicurio.registry.resolver.SchemaParser;
import io.apicurio.registry.rest.client.RegistryClient;
import io.apicurio.registry.serde.protobuf.ProtobufSchemaParser;
import io.apicurio.registry.utils.protobuf.schema.ProtobufSchema;
import org.jboss.logging.Logger;

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

    @Override
    public List<FileDescriptor> loadDescriptors() throws DescriptorLoadException {
        // In v3, we might want to list all artifacts in a group.
        // For now, we'll return empty and rely on loadDescriptor(fileName) which is called on-demand.
        return Collections.emptyList();
    }

    @Override
    public FileDescriptor loadDescriptor(String name) throws DescriptorLoadException {
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
        return "Apicurio Registry (Group: " + groupId + ")";
    }
}
