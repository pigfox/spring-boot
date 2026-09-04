package com.pigfox.ledger.config;

import static org.assertj.core.api.Assertions.assertThat;

import com.pigfox.ledger.TestFixtures;
import com.pigfox.ledger.domain.AssetEvent;
import java.util.List;
import java.util.Map;
import org.apache.kafka.clients.consumer.ConsumerConfig;
import org.apache.kafka.clients.producer.ProducerConfig;
import org.apache.kafka.common.serialization.StringDeserializer;
import org.apache.kafka.common.serialization.StringSerializer;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.boot.autoconfigure.kafka.KafkaProperties;
import org.springframework.kafka.config.ConcurrentKafkaListenerContainerFactory;
import org.springframework.kafka.core.ConsumerFactory;
import org.springframework.kafka.core.KafkaTemplate;
import org.springframework.kafka.core.ProducerFactory;
import org.springframework.kafka.support.serializer.ErrorHandlingDeserializer;
import org.springframework.kafka.support.serializer.JsonDeserializer;
import org.springframework.kafka.support.serializer.JsonSerializer;

/**
 * These assertions look pedantic, but each one pins a property whose default is wrong for
 * this service: an unacknowledged producer can lose an event, and an unrestricted JSON
 * deserialiser turns topic write access into arbitrary class loading.
 */
class KafkaConfigTest {

    private final KafkaConfig config = new KafkaConfig();
    private final KafkaProperties kafkaProperties = kafkaProperties();

    private KafkaProperties kafkaProperties() {
        KafkaProperties properties = new KafkaProperties();
        properties.setBootstrapServers(List.of("localhost:19092"));
        properties.getConsumer().setGroupId("ledger-node");
        return properties;
    }

    private Map<String, Object> producerConfig() {
        return config.assetEventProducerFactory(kafkaProperties).getConfigurationProperties();
    }

    private Map<String, Object> consumerConfig() {
        return config.assetEventConsumerFactory(kafkaProperties).getConfigurationProperties();
    }

    @Test
    @DisplayName("the producer waits for every in-sync replica")
    void producerRequiresFullAcknowledgement() {
        assertThat(producerConfig()).containsEntry(ProducerConfig.ACKS_CONFIG, "all");
    }

    @Test
    @DisplayName("the producer is idempotent, so an internal retry cannot duplicate an event")
    void producerIsIdempotent() {
        assertThat(producerConfig())
                .containsEntry(ProducerConfig.ENABLE_IDEMPOTENCE_CONFIG, true)
                .containsEntry(ProducerConfig.RETRIES_CONFIG, Integer.MAX_VALUE)
                // Idempotence tolerates at most five in-flight requests per connection.
                .containsEntry(ProducerConfig.MAX_IN_FLIGHT_REQUESTS_PER_CONNECTION, 5);
    }

    @Test
    @DisplayName("the producer serialises keys as strings and values as JSON without type headers")
    void producerUsesJsonValues() {
        assertThat(producerConfig())
                .containsEntry(ProducerConfig.KEY_SERIALIZER_CLASS_CONFIG, StringSerializer.class)
                .containsEntry(ProducerConfig.VALUE_SERIALIZER_CLASS_CONFIG, JsonSerializer.class)
                .containsEntry(JsonSerializer.ADD_TYPE_INFO_HEADERS, false);
    }

    @Test
    @DisplayName("the consumer trusts only the domain package for deserialisation")
    void consumerRestrictsTrustedPackages() {
        assertThat(consumerConfig())
                .containsEntry(JsonDeserializer.TRUSTED_PACKAGES, "com.pigfox.ledger.domain")
                .containsEntry(JsonDeserializer.VALUE_DEFAULT_TYPE, AssetEvent.class)
                .containsEntry(JsonDeserializer.USE_TYPE_INFO_HEADERS, false);
        assertThat(KafkaConfig.TRUSTED_PACKAGE).isEqualTo(AssetEvent.class.getPackageName());
    }

    @Test
    @DisplayName("the consumer wraps deserialisers so one bad record is not a poison pill")
    void consumerHandlesDeserialisationErrors() {
        assertThat(consumerConfig())
                .containsEntry(ConsumerConfig.KEY_DESERIALIZER_CLASS_CONFIG, ErrorHandlingDeserializer.class)
                .containsEntry(ConsumerConfig.VALUE_DESERIALIZER_CLASS_CONFIG, ErrorHandlingDeserializer.class)
                .containsEntry(ErrorHandlingDeserializer.KEY_DESERIALIZER_CLASS, StringDeserializer.class)
                .containsEntry(ErrorHandlingDeserializer.VALUE_DESERIALIZER_CLASS, JsonDeserializer.class);
    }

    @Test
    @DisplayName("the consumer does not auto-commit, so offsets advance only after handling")
    void consumerCommitsManually() {
        assertThat(consumerConfig()).containsEntry(ConsumerConfig.ENABLE_AUTO_COMMIT_CONFIG, false);
    }

    @Test
    @DisplayName("the template and listener container factory are wired to those factories")
    void wiresTemplateAndContainerFactory() {
        ProducerFactory<String, AssetEvent> producerFactory =
                config.assetEventProducerFactory(kafkaProperties);
        ConsumerFactory<String, AssetEvent> consumerFactory =
                config.assetEventConsumerFactory(kafkaProperties);

        KafkaTemplate<String, AssetEvent> template = config.assetEventKafkaTemplate(producerFactory);
        ConcurrentKafkaListenerContainerFactory<String, AssetEvent> containerFactory =
                config.assetEventListenerContainerFactory(consumerFactory);

        assertThat(template.getProducerFactory()).isSameAs(producerFactory);
        assertThat(containerFactory.getConsumerFactory()).isSameAs(consumerFactory);
    }

    @Test
    @DisplayName("the topic is declared with the configured name")
    void declaresTopic() {
        assertThat(config.assetEventsTopic(TestFixtures.properties()))
                .extracting("name")
                .isEqualTo(TestFixtures.TOPIC);
        assertThat(config.assetEventsTopic(TestFixtures.properties()).numPartitions()).isEqualTo(3);
    }
}
