package com.pigfox.springboot.kafka;

import com.pigfox.springboot.config.LedgerProperties;
import com.pigfox.springboot.domain.AssetEvent;
import io.micrometer.core.instrument.Counter;
import io.micrometer.core.instrument.MeterRegistry;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.kafka.core.KafkaTemplate;
import org.springframework.stereotype.Component;

/**
 * Publishes asset lifecycle events.
 *
 * <p>Publication must never fail the caller's request. By the time this runs the asset is
 * hashed, signed and stored, so a broker outage is a lost notification, not a lost asset —
 * and answering 500 would tell the client its write failed when it did not.
 *
 * <p>That takes guarding two separate failure paths, which is easy to get half right.
 * {@code send} reports a delivery failure through the returned future, but it also throws
 * <em>synchronously</em> when it cannot resolve topic metadata at all — the broker-is-down
 * case. Handling only the future leaves that exception to propagate up through the service
 * and out as a 500. Both paths increment {@code ledger.events.failed}.
 *
 * <p>The asset id is the record key, which keeps all events for one asset on one partition
 * and therefore in order.
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
     * Sends an event, keyed by asset id. Never throws.
     *
     * @param event event to publish
     */
    public void publish(AssetEvent event) {
        try {
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
        } catch (Exception e) {
            // The synchronous path: no reachable broker means no topic metadata, and send
            // gives up after max.block.ms by throwing rather than returning a future.
            failed.increment();
            log.error("Could not hand {} for asset {} to the broker, continuing: {}",
                    event.eventType(), event.assetId(), e.getMessage());
        }
    }
}
