package ai.pipestream.quarkus.dynamicgrpc.config;

import io.quarkus.grpc.runtime.config.GrpcClientConfiguration;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.inject.Inject;

import java.util.List;
import java.util.Optional;

/**
 * CDI bean adapter that implements Quarkus's TlsClientConfig interface.
 * <p>
 * This allows us to reuse all of Quarkus's SSL helper methods 1:1 while
 * providing our own global configuration for dynamic gRPC clients.
 * </p>
 */
@ApplicationScoped
public class DynamicGrpcTlsAdapter implements GrpcClientConfiguration.TlsClientConfig {

    @Inject
    DynamicGrpcConfig config;

    @Override
    public boolean enabled() {
        return config.tls().enabled();
    }

    @Override
    public boolean trustAll() {
        return config.tls().trustAll();
    }

    @Override
    public PemTrustCertConfiguration trustCertificatePem() {
        return new PemTrustCertConfiguration() {
            @Override
            public Optional<List<String>> certs() {
                return config.tls().trustCertificatePem().certs();
            }
        };
    }

    @Override
    public JksConfiguration trustCertificateJks() {
        // Not implemented yet - can add if needed
        return new JksConfiguration() {
            @Override
            public Optional<String> path() {
                return Optional.empty();
            }

            @Override
            public Optional<String> password() {
                return Optional.empty();
            }
        };
    }

    @Override
    public PfxConfiguration trustCertificateP12() {
        // Not implemented yet - can add if needed
        return new PfxConfiguration() {
            @Override
            public Optional<String> path() {
                return Optional.empty();
            }

            @Override
            public Optional<String> password() {
                return Optional.empty();
            }
        };
    }

    @Override
    public PemKeyCertConfiguration keyCertificatePem() {
        return new PemKeyCertConfiguration() {
            @Override
            public Optional<List<String>> keys() {
                return config.tls().keyCertificatePem().keys();
            }

            @Override
            public Optional<List<String>> certs() {
                return config.tls().keyCertificatePem().certs();
            }
        };
    }

    @Override
    public JksConfiguration keyCertificateJks() {
        // Not implemented yet - can add if needed
        return new JksConfiguration() {
            @Override
            public Optional<String> path() {
                return Optional.empty();
            }

            @Override
            public Optional<String> password() {
                return Optional.empty();
            }
        };
    }

    @Override
    public PfxConfiguration keyCertificateP12() {
        // Not implemented yet - can add if needed
        return new PfxConfiguration() {
            @Override
            public Optional<String> path() {
                return Optional.empty();
            }

            @Override
            public Optional<String> password() {
                return Optional.empty();
            }
        };
    }

    @Override
    public boolean verifyHostname() {
        return config.tls().verifyHostname();
    }
}
