package com.pigfox.ledger.kafka;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;

import com.pigfox.ledger.domain.AssetEvent;
import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
import java.time.Instant;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.kafka.support.Acknowledgment;

class AssetEventListenerTest {

    private SimpleMeterRegistry meterRegistry;
    private AssetEventListener listener;
    private Acknowledgment acknowledgment;

    @BeforeEach
    void setUp() {
        meterRegistry = new SimpleMeterRegistry();
        listener = new AssetEventListener(meterRegistry);
        acknowledgment = mock(Acknowledgment.class);
    }

    private AssetEvent event(String eventId, String anchorTxHash) {
        return new AssetEvent(eventId, AssetEvent.ASSET_REGISTERED, "asset-1", "0xhash",
                "0xsigner", anchorTxHash, Instant.EPOCH);
    }

    @Test
    @DisplayName("a new event is counted, recorded and acknowledged")
    void handlesNewEvent() {
        listener.onAssetEvent(event("e1", "0xtx"), acknowledgment);

        assertThat(counter("ledger.events.consumed")).isEqualTo(1.0);
        assertThat(counter("ledger.events.duplicates")).isZero();
        assertThat(listener.handledEventIds()).containsExactly("e1");
        verify(acknowledgment).acknowledge();
    }

    @Test
    @DisplayName("an unanchored event is handled the same way")
    void handlesUnanchoredEvent() {
        listener.onAssetEvent(event("e1", null), acknowledgment);

        assertThat(counter("ledger.events.consumed")).isEqualTo(1.0);
    }

    @Test
    @DisplayName("a redelivered event is skipped, not counted twice")
    void skipsDuplicate() {
        listener.onAssetEvent(event("e1", "0xtx"), acknowledgment);
        listener.onAssetEvent(event("e1", "0xtx"), acknowledgment);

        assertThat(counter("ledger.events.consumed")).isEqualTo(1.0);
        assertThat(counter("ledger.events.duplicates")).isEqualTo(1.0);
        assertThat(listener.handledEventIds()).containsExactly("e1");
    }

    @Test
    @DisplayName("a redelivered event is still acknowledged, so the offset advances")
    void acknowledgesDuplicate() {
        listener.onAssetEvent(event("e1", "0xtx"), acknowledgment);
        listener.onAssetEvent(event("e1", "0xtx"), acknowledgment);

        verify(acknowledgment, times(2)).acknowledge();
    }

    @Test
    @DisplayName("distinct events are all recorded, in arrival order")
    void recordsArrivalOrder() {
        listener.onAssetEvent(event("e1", "0xtx"), acknowledgment);
        listener.onAssetEvent(event("e2", "0xtx"), acknowledgment);

        assertThat(listener.handledEventIds()).containsExactly("e1", "e2");
        assertThat(counter("ledger.events.consumed")).isEqualTo(2.0);
    }

    @Test
    @DisplayName("a container without manual acknowledgement passes null and must still work")
    void toleratesNullAcknowledgment() {
        listener.onAssetEvent(event("e1", "0xtx"), null);

        assertThat(counter("ledger.events.consumed")).isEqualTo(1.0);
    }

    @Test
    @DisplayName("the returned audit trail is a copy the caller cannot mutate")
    void returnsImmutableAuditTrail() {
        listener.onAssetEvent(event("e1", "0xtx"), acknowledgment);

        assertThat(listener.handledEventIds()).isUnmodifiable();
    }

    private double counter(String name) {
        return meterRegistry.get(name).counter().count();
    }
}
