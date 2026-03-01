package ai.pipestream.common.descriptor;

import com.google.protobuf.DescriptorProtos.FileDescriptorSet;
import com.google.protobuf.Descriptors.DescriptorValidationException;
import com.google.protobuf.Descriptors.FileDescriptor;

import java.io.IOException;
import java.io.InputStream;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

/**
 * Loads Protocol Buffer descriptors from Google descriptor set files (.dsc).
 */
public class GoogleDescriptorLoader implements DescriptorLoader {

    private final String descriptorPath;
    private final ClassLoader classLoader;

    public static final String DEFAULT_DESCRIPTOR_PATH = "META-INF/grpc/services.dsc";

    public GoogleDescriptorLoader() {
        this(DEFAULT_DESCRIPTOR_PATH);
    }

    public GoogleDescriptorLoader(String descriptorPath) {
        this(descriptorPath, Thread.currentThread().getContextClassLoader());
    }

    public GoogleDescriptorLoader(String descriptorPath, ClassLoader classLoader) {
        this.descriptorPath = descriptorPath;
        this.classLoader = classLoader != null ? classLoader : ClassLoader.getSystemClassLoader();
    }

    @Override
    public List<FileDescriptor> loadDescriptors() throws DescriptorLoadException {
        try (InputStream inputStream = classLoader.getResourceAsStream(descriptorPath)) {
            if (inputStream == null) {
                throw new DescriptorLoadException(
                    "Descriptor file not found on classpath: " + descriptorPath);
            }

            FileDescriptorSet descriptorSet = FileDescriptorSet.parseFrom(inputStream);
            return buildFileDescriptors(descriptorSet);
        } catch (IOException e) {
            throw new DescriptorLoadException(
                "Failed to read descriptor file: " + descriptorPath, e);
        } catch (DescriptorValidationException e) {
            throw new DescriptorLoadException(
                "Invalid descriptor in file: " + descriptorPath, e);
        } catch (RuntimeException e) {
            throw new DescriptorLoadException(
                "Failed to build descriptors from file: " + descriptorPath, e);
        }
    }

    @Override
    public FileDescriptor loadDescriptor(String fileName) throws DescriptorLoadException {
        List<FileDescriptor> allDescriptors = loadDescriptors();
        return allDescriptors.stream()
            .filter(fd -> fd.getName().equals(fileName))
            .findFirst()
            .orElse(null);
    }

    @Override
    public boolean isAvailable() {
        InputStream stream = classLoader.getResourceAsStream(descriptorPath);
        if (stream != null) {
            try {
                stream.close();
            } catch (IOException e) {
                // Ignore
            }
            return true;
        }
        return false;
    }

    /**
     * Returns the descriptor file path this loader is configured with.
     */
    public String getDescriptorPath() {
        return descriptorPath;
    }

    /**
     * Creates a GoogleDescriptorLoader from the first available path.
     * Tries each path in order and returns a loader for the first one that exists.
     * If none are available, returns a loader for the first path (which will report unavailable).
     *
     * @param paths the descriptor file paths to try
     * @return a GoogleDescriptorLoader configured with the first available path
     */
    public static GoogleDescriptorLoader searchPaths(String... paths) {
        for (String path : paths) {
            GoogleDescriptorLoader loader = new GoogleDescriptorLoader(path);
            if (loader.isAvailable()) {
                return loader;
            }
        }
        return new GoogleDescriptorLoader(paths.length > 0 ? paths[0] : DEFAULT_DESCRIPTOR_PATH);
    }

    @Override
    public String getLoaderType() {
        return "Google Descriptor File";
    }

    private List<FileDescriptor> buildFileDescriptors(FileDescriptorSet descriptorSet)
            throws DescriptorValidationException {

        Map<String, FileDescriptor> descriptorMap = new HashMap<>();
        Map<String, com.google.protobuf.DescriptorProtos.FileDescriptorProto> protoMap = new HashMap<>();

        for (com.google.protobuf.DescriptorProtos.FileDescriptorProto proto : descriptorSet.getFileList()) {
            protoMap.put(proto.getName(), proto);
        }

        for (com.google.protobuf.DescriptorProtos.FileDescriptorProto proto : descriptorSet.getFileList()) {
            if (!descriptorMap.containsKey(proto.getName())) {
                buildFileDescriptor(proto, protoMap, descriptorMap);
            }
        }

        return new ArrayList<>(descriptorMap.values());
    }

    private FileDescriptor buildFileDescriptor(
            com.google.protobuf.DescriptorProtos.FileDescriptorProto proto,
            Map<String, com.google.protobuf.DescriptorProtos.FileDescriptorProto> protoMap,
            Map<String, FileDescriptor> descriptorMap) throws DescriptorValidationException {

        if (descriptorMap.containsKey(proto.getName())) {
            return descriptorMap.get(proto.getName());
        }

        List<FileDescriptor> dependencies = new ArrayList<>();
        for (String dependency : proto.getDependencyList()) {
            FileDescriptor depDescriptor = descriptorMap.get(dependency);
            if (depDescriptor == null) {
                com.google.protobuf.DescriptorProtos.FileDescriptorProto depProto = protoMap.get(dependency);
                if (depProto != null) {
                    depDescriptor = buildFileDescriptor(depProto, protoMap, descriptorMap);
                } else {
                    depDescriptor = tryGetWellKnownType(dependency);
                    if (depDescriptor == null) {
                        throw new IllegalStateException(
                            "Missing dependency: " + dependency + " for " + proto.getName());
                    }
                }
            }
            dependencies.add(depDescriptor);
        }

        FileDescriptor descriptor = FileDescriptor.buildFrom(
            proto,
            dependencies.toArray(new FileDescriptor[0])
        );

        descriptorMap.put(proto.getName(), descriptor);
        return descriptor;
    }

    private FileDescriptor tryGetWellKnownType(String fileName) {
        try {
            return switch (fileName) {
                case "google/protobuf/any.proto" -> com.google.protobuf.Any.getDescriptor().getFile();
                case "google/protobuf/struct.proto" -> com.google.protobuf.Struct.getDescriptor().getFile();
                case "google/protobuf/timestamp.proto" -> com.google.protobuf.Timestamp.getDescriptor().getFile();
                case "google/protobuf/duration.proto" -> com.google.protobuf.Duration.getDescriptor().getFile();
                case "google/protobuf/empty.proto" -> com.google.protobuf.Empty.getDescriptor().getFile();
                case "google/protobuf/field_mask.proto" -> com.google.protobuf.FieldMask.getDescriptor().getFile();
                case "google/protobuf/wrappers.proto" -> com.google.protobuf.StringValue.getDescriptor().getFile();
                default -> null;
            };
        } catch (Exception e) {
            return null;
        }
    }
}
