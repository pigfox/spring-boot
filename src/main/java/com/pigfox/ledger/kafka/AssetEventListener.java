package com.pigfox.ledger.kafka;

import com.pigfox.ledger.domain.AssetEvent;
import io.micrometer.core.instrument.Counter;
import io.micrometer.core.instrument.MeterRegistry;
import java.util.Collection;
import java.util.List;
import java.util.LinkedHashSet;
import java.util.Set;
import java.util.Collections;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.kafka.annotation.KafkaListener;
import org.springframework.kafka.support.Acknowledgment;
import org.springframework.stereotype.Component;

/**
 * Consumes {@code asset.events} and keeps a de-duplicated audit trail.
 *
 * <p>An idempotent producer removes duplicates introduced by its own retries, not
 * duplicates from a redelivery after a consumer restart. At-least-once delivery is the
 * guarantee that actually applies, so the consumer tracks event ids it has already seen
 * and treats a repeat as a no-op. Offsets are acknowledged only after the record has
 * been handled.
 */
@Component
public class AssetEventListener {

    private static final Logger log = LoggerFactory.getLogger(AssetEventListener.class);

    private final Set<String> seenEventIds = Collections.synchronizedSet(new LinkedHashSet<>());
    private final Counter consumed;
    private final Counter duplicates;

    public AssetEventListener(MeterRegistry meterRegistry) {
        this.consumed = Counter.builder("ledger.events.consumed")
                .description("Asset events handled by this node")
                .register(meterRegistry);
        this.duplicates = Counter.builder("ledger.events.duplicates")
                .description("Asset events redelivered and skipped")
                .register(meterRegistry);
    }

    /**
     * Handles one event.
     *
     * @param event          the deserialised event
     * @param acknowledgment offset acknowledgement, null when the container is not using
     *                       manual acknowledgement
     */
    @KafkaListener(
            topics = "${ledger.events.topic}",
            groupId = "${spring.kafka.consumer.group-id}",
            containerFactory = "assetEventListenerContainerFactory")
    public void onAssetEvent(AssetEvent event, Acknowledgment acknowledgment) {
        if (!seenEventIds.add(event.eventId())) {
            duplicates.increment();
            log.debug("Skipping already-handled event {}", event.eventId());
        } else {
            consumed.increment();
            log.info("Observed {} for asset {} anchored={} hash={}",
                    event.eventType(), event.assetId(), event.anchorTxHash() != null, event.payloadHash());
        }
        if (acknowledgment != null) {
            acknowledgment.acknowledge();
        }
    }

    /** @return event ids handled since startup, in arrival order */
    public Collection<String> handledEventIds() {
        synchronized (seenEventIds) {
            return List.copyOf(seenEventIds);
        }
    }
}
