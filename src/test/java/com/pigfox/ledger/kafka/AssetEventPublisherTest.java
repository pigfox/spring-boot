package com.pigfox.ledger.kafka;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import com.pigfox.ledger.TestFixtures;
import com.pigfox.ledger.domain.AssetEvent;
import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
import java.time.Instant;
import java.util.concurrent.CompletableFuture;
import org.apache.kafka.clients.producer.ProducerRecord;
import org.apache.kafka.clients.producer.RecordMetadata;
import org.apache.kafka.common.TopicPartition;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.springframework.kafka.core.KafkaTemplate;
import org.springframework.kafka.support.SendResult;

class AssetEventPublisherTest {

    private KafkaTemplate<String, AssetEvent> template;
    private SimpleMeterRegistry meterRegistry;
    private AssetEventPublisher publisher;

    private final AssetEvent event = new AssetEvent("e1", AssetEvent.ASSET_REGISTERED, "asset-1",
            "0xhash", "0xsigner", "0xtx", Instant.EPOCH);

    @BeforeEach
    @SuppressWarnings("unchecked")
    void setUp() {
        template = mock(KafkaTemplate.class);
        meterRegistry = new SimpleMeterRegistry();
        publisher = new AssetEventPublisher(template, TestFixtures.properties(), meterRegistry);
    }

    @Test
    @DisplayName("an accepted record increments the published counter")
    void countsPublished() {
        when(template.send(eq(TestFixtures.TOPIC), eq("asset-1"), any(AssetEvent.class)))
                .thenReturn(CompletableFuture.completedFuture(sendResult()));

        publisher.publish(event);

        assertThat(counter("ledger.events.published")).isEqualTo(1.0);
        assertThat(counter("ledger.events.failed")).isZero();
    }

    @Test
    @DisplayName("a broker failure increments the failed counter and does not propagate")
    void countsFailuresWithoutThrowing() {
        when(template.send(eq(TestFixtures.TOPIC), eq("asset-1"), any(AssetEvent.class)))
                .thenReturn(CompletableFuture.failedFuture(new IllegalStateException("broker down")));

        publisher.publish(event);

        assertThat(counter("ledger.events.failed")).isEqualTo(1.0);
        assertThat(counter("ledger.events.published")).isZero();
    }

    @Test
    @DisplayName("the asset id is the record key, which keeps one asset's events in order")
    void keysByAssetId() {
        ArgumentCaptor<String> key = ArgumentCaptor.forClass(String.class);
        when(template.send(eq(TestFixtures.TOPIC), key.capture(), any(AssetEvent.class)))
                .thenReturn(CompletableFuture.completedFuture(sendResult()));

        publisher.publish(event);

        assertThat(key.getValue()).isEqualTo("asset-1");
    }

    @Test
    @DisplayName("counters are tagged with the topic")
    void tagsCountersWithTopic() {
        assertThat(meterRegistry.find("ledger.events.published").counter().getId().getTag("topic"))
                .isEqualTo(TestFixtures.TOPIC);
    }

    private double counter(String name) {
        return meterRegistry.get(name).counter().count();
    }

    private SendResult<String, AssetEvent> sendResult() {
        return new SendResult<>(
                new ProducerRecord<>(TestFixtures.TOPIC, "asset-1", event),
                new RecordMetadata(new TopicPartition(TestFixtures.TOPIC, 0), 0, 0, 0L, 0, 0));
    }
}
