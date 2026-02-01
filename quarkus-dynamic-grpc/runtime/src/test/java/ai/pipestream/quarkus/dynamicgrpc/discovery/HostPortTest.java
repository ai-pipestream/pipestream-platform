package ai.pipestream.quarkus.dynamicgrpc.discovery;

import org.junit.jupiter.api.Test;

import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;

class HostPortTest {

    @Test
    void parse_validAddresses() {
        assertThat(HostPort.parse("localhost:38080"))
                .hasValueSatisfying(hp -> {
                    assertThat(hp.host()).isEqualTo("localhost");
                    assertThat(hp.port()).isEqualTo(38080);
                    assertThat(hp.toAddress()).isEqualTo("localhost:38080");
                });

        assertThat(HostPort.parse("192.168.1.1:9090"))
                .hasValueSatisfying(hp -> {
                    assertThat(hp.host()).isEqualTo("192.168.1.1");
                    assertThat(hp.port()).isEqualTo(9090);
                });

        assertThat(HostPort.parse("host.example.com:50052"))
                .hasValueSatisfying(hp -> {
                    assertThat(hp.host()).isEqualTo("host.example.com");
                    assertThat(hp.port()).isEqualTo(50052);
                });

        assertThat(HostPort.parse("  wiremock:8080  "))
                .hasValueSatisfying(hp -> {
                    assertThat(hp.host()).isEqualTo("wiremock");
                    assertThat(hp.port()).isEqualTo(8080);
                });

        assertThat(HostPort.parse("host:1").map(HostPort::port)).hasValue(1);
        assertThat(HostPort.parse("host:65535").map(HostPort::port)).hasValue(65535);
    }

    @Test
    void parse_invalidReturnsEmpty() {
        assertThat(HostPort.parse(null)).isEmpty();
        assertThat(HostPort.parse("")).isEmpty();
        assertThat(HostPort.parse("   ")).isEmpty();
        assertThat(HostPort.parse("localhost")).isEmpty();
        assertThat(HostPort.parse(":8080")).isEmpty();
        assertThat(HostPort.parse("host:")).isEmpty();
        assertThat(HostPort.parse("host:abc")).isEmpty();
        assertThat(HostPort.parse("host:-1")).isEmpty();
        assertThat(HostPort.parse("host:0")).isEmpty();
        assertThat(HostPort.parse("host:99999")).isEmpty();
        assertThat(HostPort.parse(":")).isEmpty();
    }
}
