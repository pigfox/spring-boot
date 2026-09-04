package com.pigfox.ledger.config;

import com.pigfox.ledger.domain.AssetEvent;
import java.util.HashMap;
import java.util.Map;
import org.apache.kafka.clients.consumer.ConsumerConfig;
import org.apache.kafka.clients.producer.ProducerConfig;
import org.apache.kafka.common.serialization.StringDeserializer;
import org.apache.kafka.common.serialization.StringSerializer;
import org.springframework.boot.autoconfigure.kafka.KafkaProperties;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.kafka.config.ConcurrentKafkaListenerContainerFactory;
import org.springframework.kafka.config.TopicBuilder;
import org.springframework.kafka.core.ConsumerFactory;
import org.springframework.kafka.core.DefaultKafkaConsumerFactory;
import org.springframework.kafka.core.DefaultKafkaProducerFactory;
import org.springframework.kafka.core.KafkaTemplate;
import org.springframework.kafka.core.ProducerFactory;
import org.springframework.kafka.support.serializer.ErrorHandlingDeserializer;
import org.springframework.kafka.support.serializer.JsonDeserializer;
import org.springframework.kafka.support.serializer.JsonSerializer;
import org.apache.kafka.clients.admin.NewTopic;

/**
 * Event backbone for {@code asset.events}.
 *
 * <p>Producer durability is not left to defaults. {@code acks=all} waits for every
 * in-sync replica, and {@code enable.idempotence=true} makes the broker discard a
 * duplicate produced by an internal retry, so a retried anchor notification cannot be
 * delivered twice.
 *
 * <p>Durability is bounded by {@code max.block.ms} rather than the 60s default: an
 * unreachable broker must cost an API caller a moment, not a minute. See
 * {@link com.pigfox.ledger.kafka.AssetEventPublisher} for why it must cost nothing worse
 * than that.
 *
 * <p>The consumer restricts {@link JsonDeserializer#TRUSTED_PACKAGES} to the domain
 * package. Left open, the JSON deserialiser will instantiate whatever type a record's
 * headers name, which turns topic write access into arbitrary class loading. The
 * deserialisers are also wrapped in {@link ErrorHandlingDeserializer} so a single
 * malformed record surfaces as a handled error rather than wedging the container in a
 * poison-pill loop.
 */
@Configuration
public class KafkaConfig {

    /** The one package whose types may be reconstructed from a topic record. */
    static final String TRUSTED_PACKAGE = "com.pigfox.ledger.domain";

    /**
     * @param properties Boot's Kafka settings, which supply the bootstrap servers
     * @return an idempotent, fully acknowledged producer factory
     */
    @Bean
    public ProducerFactory<String, AssetEvent> assetEventProducerFactory(KafkaProperties properties) {
        Map<String, Object> config = new HashMap<>(properties.buildProducerProperties(null));
        config.put(ProducerConfig.KEY_SERIALIZER_CLASS_CONFIG, StringSerializer.class);
        config.put(ProducerConfig.VALUE_SERIALIZER_CLASS_CONFIG, JsonSerializer.class);
        config.put(ProducerConfig.ACKS_CONFIG, "all");
        config.put(ProducerConfig.ENABLE_IDEMPOTENCE_CONFIG, true);
        config.put(ProducerConfig.MAX_IN_FLIGHT_REQUESTS_PER_CONNECTION, 5);
        config.put(ProducerConfig.RETRIES_CONFIG, Integer.MAX_VALUE);
        config.put(ProducerConfig.DELIVERY_TIMEOUT_MS_CONFIG, 120_000);
        // Bounds how long send() may block the calling thread waiting for topic metadata.
        // The default is 60s, which turns a broker outage into a minute-long stall on an
        // API request that has already done its real work. Metadata is cached after the
        // first successful send, so a healthy broker never pays this.
        config.put(ProducerConfig.MAX_BLOCK_MS_CONFIG, 2_000);
        config.put(JsonSerializer.ADD_TYPE_INFO_HEADERS, false);
        return new DefaultKafkaProducerFactory<>(config);
    }

    /**
     * @param factory the idempotent producer factory
     * @return template used by the publisher
     */
    @Bean
    public KafkaTemplate<String, AssetEvent> assetEventKafkaTemplate(
            ProducerFactory<String, AssetEvent> factory) {
        return new KafkaTemplate<>(factory);
    }

    /**
     * @param properties Boot's Kafka settings, which supply bootstrap servers and group id
     * @return a consumer factory that will only deserialise domain types
     */
    @Bean
    public ConsumerFactory<String, AssetEvent> assetEventConsumerFactory(KafkaProperties properties) {
        Map<String, Object> config = new HashMap<>(properties.buildConsumerProperties(null));
        config.put(ConsumerConfig.KEY_DESERIALIZER_CLASS_CONFIG, ErrorHandlingDeserializer.class);
        config.put(ConsumerConfig.VALUE_DESERIALIZER_CLASS_CONFIG, ErrorHandlingDeserializer.class);
        config.put(ErrorHandlingDeserializer.KEY_DESERIALIZER_CLASS, StringDeserializer.class);
        config.put(ErrorHandlingDeserializer.VALUE_DESERIALIZER_CLASS, JsonDeserializer.class);
        config.put(JsonDeserializer.TRUSTED_PACKAGES, TRUSTED_PACKAGE);
        config.put(JsonDeserializer.VALUE_DEFAULT_TYPE, AssetEvent.class);
        config.put(JsonDeserializer.USE_TYPE_INFO_HEADERS, false);
        config.put(ConsumerConfig.ENABLE_AUTO_COMMIT_CONFIG, false);
        return new DefaultKafkaConsumerFactory<>(config);
    }

    /**
     * @param factory the restricted consumer factory
     * @return the listener container factory referenced by {@code @KafkaListener}
     */
    @Bean
    public ConcurrentKafkaListenerContainerFactory<String, AssetEvent> assetEventListenerContainerFactory(
            ConsumerFactory<String, AssetEvent> factory) {
        ConcurrentKafkaListenerContainerFactory<String, AssetEvent> containerFactory =
                new ConcurrentKafkaListenerContainerFactory<>();
        containerFactory.setConsumerFactory(factory);
        return containerFactory;
    }

    /**
     * @param properties node configuration supplying the topic name
     * @return topic definition, created on startup when the broker allows it
     */
    @Bean
    public NewTopic assetEventsTopic(LedgerProperties properties) {
        return TopicBuilder.name(properties.events().topic())
                .partitions(3)
                .replicas(1)
                .build();
    }
}
