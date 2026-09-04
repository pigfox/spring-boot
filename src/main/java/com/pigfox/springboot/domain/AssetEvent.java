package com.pigfox.springboot.domain;

import java.time.Instant;

/**
 * Event published to {@code asset.events}.
 *
 * <p>This type deliberately lives in the domain package: the Kafka consumer trusts
 * only {@code com.pigfox.springboot.domain} for deserialisation, so an attacker who can
 * write to the topic cannot name an arbitrary class as the payload type.
 *
 * @param eventId       unique event identifier, usable for consumer-side idempotency
 * @param eventType     lifecycle marker, see {@link #ASSET_REGISTERED}
 * @param assetId       identifier of the subject asset
 * @param payloadHash   0x-prefixed keccak256 of the asset payload
 * @param signerAddress address that signed the payload hash
 * @param anchorTxHash  anchor transaction hash, or {@code null} when unanchored
 * @param occurredAt    time the event was produced
 */
public record AssetEvent(
        String eventId,
        String eventType,
        String assetId,
        String payloadHash,
        String signerAddress,
        String anchorTxHash,
        Instant occurredAt) {

    /** Event type emitted when an asset is first registered. */
    public static final String ASSET_REGISTERED = "ASSET_REGISTERED";
}
