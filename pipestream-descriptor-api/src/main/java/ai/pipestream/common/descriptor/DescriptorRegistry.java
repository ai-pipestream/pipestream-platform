package ai.pipestream.common.descriptor;

import com.google.protobuf.Descriptors.Descriptor;
import com.google.protobuf.Descriptors.FileDescriptor;
import com.google.protobuf.Message;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.enterprise.inject.Any;
import jakarta.enterprise.inject.Instance;
import jakarta.inject.Inject;
import org.jboss.logging.Logger;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

/**
 * Registry for managing Protocol Buffer descriptors.
 * Provides lookup capabilities for descriptors by type name and caching.
 * Supports loading descriptors from various sources via DescriptorLoader implementations.
 */
@ApplicationScoped
public class DescriptorRegistry {

    private static final Logger LOG = Logger.getLogger(DescriptorRegistry.class);

    private final Map<String, Descriptor> descriptorsByFullName = new ConcurrentHashMap<>();
    private final Map<String, Descriptor> descriptorsBySimpleName = new ConcurrentHashMap<>();
    private final List<DescriptorLoader> manualLoaders = new ArrayList<>();
    
    @Inject
    @Any
    Instance<DescriptorLoader> injectedLoaders;

    private boolean autoLoadAttempted = false;

    /**
     * Creates a new DescriptorRegistry and registers well-known types.
     */
    public DescriptorRegistry() {
        registerWellKnownTypes();
    }

    /**
     * Registers well-known Google protobuf types.
     */
    private void registerWellKnownTypes() {
        try {
            register(com.google.protobuf.Struct.getDescriptor());
            register(com.google.protobuf.Value.getDescriptor());
            register(com.google.protobuf.ListValue.getDescriptor());
            register(com.google.protobuf.Timestamp.getDescriptor());
            register(com.google.protobuf.Duration.getDescriptor());
            register(com.google.protobuf.Any.getDescriptor());
            register(com.google.protobuf.Empty.getDescriptor());
        } catch (Exception e) {
            LOG.warn("Failed to register some well-known protobuf types", e);
        }
    }

    /**
     * Registers a descriptor in the registry.
     *
     * @param descriptor The descriptor to register
     */
    public void register(Descriptor descriptor) {
        descriptorsByFullName.put(descriptor.getFullName(), descriptor);
        descriptorsBySimpleName.put(descriptor.getName(), descriptor);
    }

    /**
     * Registers all message types from a file descriptor.
     *
     * @param fileDescriptor The file descriptor to register
     */
    public void registerFile(FileDescriptor fileDescriptor) {
        for (Descriptor messageType : fileDescriptor.getMessageTypes()) {
            register(messageType);
            registerNestedTypes(messageType);
        }
    }

    private void registerNestedTypes(Descriptor descriptor) {
        for (Descriptor nested : descriptor.getNestedTypes()) {
            register(nested);
            registerNestedTypes(nested);
        }
    }

    /**
     * Registers a descriptor from a message instance.
     *
     * @param message The message whose descriptor should be registered
     */
    public void registerFromMessage(Message message) {
        register(message.getDescriptorForType());
    }

    /**
     * Finds a descriptor by its full name.
     *
     * @param fullName The full name (e.g., "ai.pipestream.data.v1.SearchMetadata")
     * @return The descriptor, or null if not found
     */
    public Descriptor findDescriptorByFullName(String fullName) {
        Descriptor d = descriptorsByFullName.get(fullName);
        if (d == null) {
            // Try to resolve on-demand
            d = resolveOnDemand(fullName);
        }
        return d;
    }

    private Descriptor resolveOnDemand(String typeName) {
        // We might need a mapping from typeName -> fileName.
        // For now, let's assume artifactId == typeName or use heuristics.
        
        List<DescriptorLoader> allLoaders = getLoaders();
        for (DescriptorLoader loader : allLoaders) {
            if (loader.isAvailable()) {
                try {
                    // Try to load by name (heuristically using typeName)
                    FileDescriptor fd = loader.loadDescriptor(typeName);
                    if (fd != null) {
                        registerFile(fd);
                        return findDescriptorByFullName(typeName);
                    }
                } catch (DescriptorLoader.DescriptorLoadException e) {
                    // Ignore and try next loader
                }
            }
        }
        return null;
    }

    private List<DescriptorLoader> getLoaders() {
        List<DescriptorLoader> allLoaders = new ArrayList<>(manualLoaders);
        if (injectedLoaders != null) {
            for (DescriptorLoader loader : injectedLoaders) {
                allLoaders.add(loader);
            }
        }
        return allLoaders;
    }

    /**
     * Finds a descriptor by its simple name.
     */
    public Descriptor findDescriptorBySimpleName(String simpleName) {
        Descriptor d = descriptorsBySimpleName.get(simpleName);
        if (d == null) {
            autoLoadDescriptors();
            d = descriptorsBySimpleName.get(simpleName);
        }
        return d;
    }

    /**
     * Finds a descriptor by either full or simple name.
     */
    public Descriptor findDescriptor(String name) {
        Descriptor descriptor = findDescriptorByFullName(name);
        if (descriptor == null) {
            descriptor = findDescriptorBySimpleName(name);
        }
        return descriptor;
    }

    /**
     * Checks if a descriptor is registered.
     */
    public boolean isRegistered(String fullName) {
        return descriptorsByFullName.containsKey(fullName);
    }

    /**
     * Clears all registered descriptors except well-known types.
     */
    public void clear() {
        descriptorsByFullName.clear();
        descriptorsBySimpleName.clear();
        registerWellKnownTypes();
    }

    /**
     * Adds a manual descriptor loader.
     */
    public void addLoader(DescriptorLoader loader) {
        if (loader != null) {
            manualLoaders.add(loader);
        }
    }

    /**
     * Loads descriptors from available loaders.
     */
    public synchronized void autoLoadDescriptors() {
        if (autoLoadAttempted) {
            return;
        }
        autoLoadAttempted = true;

        List<DescriptorLoader> allLoaders = new ArrayList<>(manualLoaders);
        if (injectedLoaders != null) {
            for (DescriptorLoader loader : injectedLoaders) {
                allLoaders.add(loader);
            }
        }

        for (DescriptorLoader loader : allLoaders) {
            if (loader.isAvailable()) {
                try {
                    List<FileDescriptor> fileDescriptors = loader.loadDescriptors();
                    int count = 0;
                    for (FileDescriptor fd : fileDescriptors) {
                        registerFile(fd);
                        count += fd.getMessageTypes().size();
                    }
                    LOG.infof("Loaded %d descriptors from %s", count, loader.getLoaderType());
                } catch (DescriptorLoader.DescriptorLoadException e) {
                    LOG.warnf("Failed to load descriptors from %s: %s", loader.getLoaderType(), e.getMessage());
                }
            }
        }
    }
}
