package ai.pipestream.common.descriptor.apicurio;

import ai.pipestream.common.descriptor.DescriptorLoader;
import io.apicurio.registry.client.RegistryClientFactory;
import io.apicurio.registry.client.common.RegistryClientOptions;
import io.apicurio.registry.rest.client.RegistryClient;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.enterprise.inject.Produces;
import jakarta.inject.Singleton;
import org.eclipse.microprofile.config.ConfigProvider;
import org.jboss.logging.Logger;

/**
 * Producer for Apicurio-based descriptor loaders.
 */
@ApplicationScoped
public class ApicurioDescriptorLoaderProducer {

    private static final Logger LOG = Logger.getLogger(ApicurioDescriptorLoaderProducer.class);

    @Produces
    @Singleton
    public RegistryClient produceRegistryClient(PipestreamDescriptorApicurioConfig config) {
        if (!config.enabled()) {
            return null;
        }

        String url = config.registryUrl().orElseGet(this::getRegistryUrl);
        if (url == null || url.isBlank()) {
            LOG.warn("No Apicurio Registry URL found. Descriptor loading from Apicurio is disabled.");
            return null;
        }

        LOG.infof("Creating Apicurio Registry client for URL: %s", url);
        RegistryClientOptions options = RegistryClientOptions.create(url);
        return RegistryClientFactory.create(options);
    }

    @Produces
    @Singleton
    public DescriptorLoader produceApicurioDescriptorLoader(RegistryClient client, PipestreamDescriptorApicurioConfig config) {
        if (client == null || !config.enabled()) {
            return null;
        }
        
        String groupId = config.groupId();
        LOG.infof("Producing ApicurioDescriptorLoader for group: %s", groupId);
        return new ApicurioDescriptorLoader(client, groupId);
    }

    private String getRegistryUrl() {
        return ConfigProvider.getConfig()
                .getOptionalValue("mp.messaging.connector.smallrye-kafka.apicurio.registry.url", String.class)
                .or(() -> ConfigProvider.getConfig().getOptionalValue("apicurio.registry.url", String.class))
                .orElse(null);
    }
}
