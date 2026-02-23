package ai.pipestream.server.util;

import jakarta.enterprise.context.ApplicationScoped;

@ApplicationScoped
public class ChunkSizeCalculator {

    private static final int MIN_CHUNK_SIZE = 1 * 1024 * 1024; // 1MB
    private static final int MAX_CHUNK_SIZE = 100 * 1024 * 1024; // 100MB
    private static final int DEFAULT_CHUNK_SIZE = 8 * 1024 * 1024; // 8MB

    public int calculateChunkSize(long totalSizeBytes) {
        if (totalSizeBytes <= 0) {
            return DEFAULT_CHUNK_SIZE;
        }
        
        // For files > 5GB, use 50MB chunks (Check largest first!)
        if (totalSizeBytes > 5L * 1024 * 1024 * 1024) {
            return 50 * 1024 * 1024;
        }

        // For files > 1GB, use 10MB chunks
        if (totalSizeBytes > 1024 * 1024 * 1024) {
            return 10 * 1024 * 1024;
        }
        
        // Default for smaller files
        return DEFAULT_CHUNK_SIZE;
    }
}