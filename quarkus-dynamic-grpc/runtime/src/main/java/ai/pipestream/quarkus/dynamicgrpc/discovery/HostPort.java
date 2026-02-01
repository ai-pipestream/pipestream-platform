package ai.pipestream.quarkus.dynamicgrpc.discovery;

import java.util.Optional;

/**
 * Parses and validates "host:port" format addresses for direct service connection
 * without Consul discovery.
 */
public final class HostPort {

    private final String host;
    private final int port;

    private HostPort(String host, int port) {
        this.host = host;
        this.port = port;
    }

    public String host() {
        return host;
    }

    public int port() {
        return port;
    }

    /**
     * Returns the address in "host:port" form for Stork address-list.
     */
    public String toAddress() {
        return host + ":" + port;
    }

    /**
     * Parses a string in "host:port" format.
     * <p>
     * Valid examples: "localhost:38080", "192.168.1.1:9090", "host.example.com:50052".
     * Invalid: "localhost", ":8080", "host:", "host:abc", "host:99999".
     *
     * @param s the string to parse (may be null or blank)
     * @return Optional containing HostPort if valid, empty otherwise
     */
    public static Optional<HostPort> parse(String s) {
        if (s == null || s.isBlank()) {
            return Optional.empty();
        }
        String trimmed = s.trim();
        int colon = trimmed.lastIndexOf(':');
        if (colon <= 0 || colon == trimmed.length() - 1) {
            return Optional.empty();
        }
        String hostPart = trimmed.substring(0, colon);
        String portPart = trimmed.substring(colon + 1);
        if (hostPart.isBlank() || portPart.isBlank()) {
            return Optional.empty();
        }
        try {
            int port = Integer.parseInt(portPart);
            if (port > 0 && port <= 65535) {
                return Optional.of(new HostPort(hostPart, port));
            }
        } catch (NumberFormatException ignored) {
        }
        return Optional.empty();
    }
}
