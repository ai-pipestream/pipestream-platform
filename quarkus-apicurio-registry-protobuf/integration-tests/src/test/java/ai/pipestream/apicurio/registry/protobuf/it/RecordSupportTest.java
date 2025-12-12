package ai.pipestream.apicurio.registry.protobuf.it;

import ai.pipestream.apicurio.registry.protobuf.it.proto.TestRecord;
import io.quarkus.test.common.QuarkusTestResource;
import io.quarkus.test.junit.QuarkusTest;
import io.smallrye.reactive.messaging.kafka.Record;
import jakarta.inject.Inject;
import org.apache.kafka.clients.producer.KafkaProducer;
import org.apache.kafka.clients.producer.ProducerConfig;
import org.apache.kafka.clients.producer.ProducerRecord;
import org.apache.kafka.common.serialization.UUIDSerializer;
import org.awaitility.Awaitility;
import org.eclipse.microprofile.config.ConfigProvider;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;

import java.util.Properties;
import java.util.UUID;
import java.util.concurrent.TimeUnit;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;

@QuarkusTest
public class RecordSupportTest {

    @Inject
    RecordConsumer consumer;

    @AfterEach
    public void cleanup() {
        consumer.clear();
    }

    @Test
    public void testConsumeRecordWithUuidKey() {
        // Send a message with a UUID key to the topic
        UUID key = UUID.randomUUID();
        TestRecord payload = TestRecord.newBuilder()
                .setId("record-1")
                .setName("Record Test")
                .build();

        sendProtobufMessage("record-protobuf-topic", key, payload);

        // Wait for consumer to receive it
        Awaitility.await().atMost(10, TimeUnit.SECONDS).until(() -> !consumer.getReceived().isEmpty());

        Record<UUID, TestRecord> received = consumer.getReceived().get(0);
        assertNotNull(received);
        assertEquals(key, received.key());
        assertEquals("record-1", received.value().getId());
        assertEquals("Record Test", received.value().getName());
    }

    private void sendProtobufMessage(String topic, UUID key, TestRecord payload) {
        Properties props = new Properties();
        props.put(ProducerConfig.BOOTSTRAP_SERVERS_CONFIG, 
                  ConfigProvider.getConfig().getValue("kafka.bootstrap.servers", String.class));
        props.put(ProducerConfig.KEY_SERIALIZER_CLASS_CONFIG, UUIDSerializer.class.getName());
        props.put(ProducerConfig.VALUE_SERIALIZER_CLASS_CONFIG, 
                  "io.apicurio.registry.serde.protobuf.ProtobufKafkaSerializer");
        
        // Apicurio config (needed for the serializer)
        props.put(ProducerConfig.CLIENT_ID_CONFIG, "test-producer-" + UUID.randomUUID());
        props.put("apicurio.registry.url", 
                  ConfigProvider.getConfig().getValue("mp.messaging.connector.smallrye-kafka.apicurio.registry.url", String.class));
        props.put("apicurio.registry.auto-register", "true");
        props.put("apicurio.registry.artifact-resolver-strategy", "io.apicurio.registry.serde.strategy.SimpleTopicIdStrategy");

        try (KafkaProducer<UUID, TestRecord> producer = new KafkaProducer<>(props)) {
            producer.send(new ProducerRecord<>(topic, key, payload));
            producer.flush();
        }
    }
}
