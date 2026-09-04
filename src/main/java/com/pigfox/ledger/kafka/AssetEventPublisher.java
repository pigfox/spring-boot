package com.pigfox.ledger.kafka;

import com.pigfox.ledger.config.LedgerProperties;
import com.pigfox.ledger.domain.AssetEvent;
import io.micrometer.core.instrument.Counter;
import io.micrometer.core.instrument.MeterRegistry;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.kafka.core.KafkaTemplate;
import org.springframework.stereotype.Component;

/**
 * Publishes asset lifecycle events.
 *
 * <p>Publication is asynchronous and non-fatal. The asset is already stored and signed
 * by the time this runs, so a broker outage is recorded on a counter and logged rather
 * than failing the caller's request. The asset id is the record key, which keeps all
 * events for one asset on one partition and therefore in order.
 */
@Component
public class AssetEventPublisher {

    private static final Logger log = LoggerFactory.getLogger(AssetEventPublisher.class);

    private final KafkaTemplate<String, AssetEvent> template;
    private final String topic;
    private final Counter published;
    private final Counter failed;

    public AssetEventPublisher(
            KafkaTemplate<String, AssetEvent> template,
            LedgerProperties properties,
            MeterRegistry meterRegistry) {
        this.template = template;
        this.topic = properties.events().topic();
        this.published = Counter.builder("ledger.events.published")
                .description("Asset events accepted by the broker")
                .tag("topic", topic)
                .register(meterRegistry);
        this.failed = Counter.builder("ledger.events.failed")
                .description("Asset events the broker did not accept")
                .tag("topic", topic)
                .register(meterRegistry);
    }

    /**
     * Sends an event, keyed by asset id.
     *
     * @param event event to publish
     */
    public void publish(AssetEvent event) {
        template.send(topic, event.assetId(), event).whenComplete((result, error) -> {
            if (error == null) {
                published.increment();
                log.debug("Published {} for asset {}", event.eventType(), event.assetId());
            } else {
                failed.increment();
                log.error("Failed to publish {} for asset {}: {}",
                        event.eventType(), event.assetId(), error.getMessage());
            }
        });
    }
}
