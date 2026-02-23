package ai.pipestream.apicurio.registry.protobuf.runtime;

import ai.pipestream.apicurio.registry.protobuf.ApicurioRegistryProtobufConfig;
import ai.pipestream.common.descriptor.DescriptorLoader;
import ai.pipestream.common.descriptor.apicurio.ApicurioDescriptorLoader;
import io.apicurio.registry.rest.client.RegistryClient;
import io.apicurio.registry.client.RegistryClientFactory;
import io.apicurio.registry.client.common.RegistryClientOptions;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.enterprise.inject.Produces;
import jakarta.inject.Singleton;
import org.eclipse.microprofile.config.ConfigProvider;

import java.util.Optional;

/**
 * Producer for Apicurio-based descriptor loaders.
 */
@ApplicationScoped
public class ApicurioDescriptorLoaderProducer {

    @Produces
    @Singleton
    public RegistryClient produceRegistryClient() {
        String url = getRegistryUrl();
        if (url == null || url.isBlank()) {
            return null;
        }
        RegistryClientOptions options = RegistryClientOptions.create(url);
        return RegistryClientFactory.create(options);
    }

    @Produces
    @Singleton
    public DescriptorLoader produceApicurioDescriptorLoader(RegistryClient client, ApicurioRegistryProtobufConfig config) {
        if (client == null) {
            return null;
        }
        
        // Use configured group ID if present, otherwise default to "default"
        String groupId = config.explicitGroupId().orElse("default");
        
        return new ApicurioDescriptorLoader(client, groupId);
    }

    private String getRegistryUrl() {
        return ConfigProvider.getConfig()
                .getOptionalValue("mp.messaging.connector.smallrye-kafka.apicurio.registry.url", String.class)
                .or(() -> ConfigProvider.getConfig().getOptionalValue("apicurio.registry.url", String.class))
                .orElse(null);
    }
}
