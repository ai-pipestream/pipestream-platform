package ai.pipestream.module.runtime.work;

import ai.pipestream.module.work.v1.Hello;
import ai.pipestream.module.work.v1.WorkAck;
import com.google.protobuf.Any;
import com.google.protobuf.InvalidProtocolBufferException;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * Unit tests for {@link PayloadCodec}.
 *
 * <p>Uses two arbitrary message types from the module-worker proto
 * itself (Hello, WorkAck) so the test doesn't depend on
 * engine-specific types like PipeStream. That's intentional — the
 * platform extension's tests must not pull engine classes onto their
 * classpath, mirroring the production constraint.
 */
class PayloadCodecTest {

    @Test
    void roundTripPreservesMessage() throws InvalidProtocolBufferException {
        Hello original = Hello.newBuilder()
                .setModuleId("m")
                .setInstanceId("i")
                .build();

        PayloadCodec<Hello> codec = new PayloadCodec<>(Hello.class);
        Any packed = codec.pack(original);
        Hello unpacked = codec.unpack(packed);

        assertThat(unpacked)
                .as("pack then unpack must round-trip the message bit-for-bit")
                .isEqualTo(original);
    }

    @Test
    void typeUrlReflectsMessageDescriptor() {
        Hello hello = Hello.newBuilder().setModuleId("x").build();
        Any packed = new PayloadCodec<>(Hello.class).pack(hello);
        assertThat(packed.getTypeUrl())
                .as("Any.type_url must include the fully-qualified protobuf type name "
                        + "so receivers can detect mismatched payloads")
                .endsWith("ai.pipestream.module.work.v1.Hello");
    }

    @Test
    void unpackWithWrongTypeThrows() {
        // Pack a Hello, try to unpack as a different message type.
        Any packed = new PayloadCodec<>(Hello.class).pack(Hello.newBuilder().setModuleId("x").build());
        PayloadCodec<WorkAck> wrongCodec = new PayloadCodec<>(WorkAck.class);

        assertThatThrownBy(() -> wrongCodec.unpack(packed))
                .as("unpack into a different concrete type must throw rather than "
                        + "return malformed data")
                .isInstanceOf(InvalidProtocolBufferException.class);
    }

    @Test
    void nullValueRejected() {
        PayloadCodec<Hello> codec = new PayloadCodec<>(Hello.class);
        assertThatThrownBy(() -> codec.pack(null)).isInstanceOf(NullPointerException.class);
        assertThatThrownBy(() -> codec.unpack(null)).isInstanceOf(NullPointerException.class);
    }
}
