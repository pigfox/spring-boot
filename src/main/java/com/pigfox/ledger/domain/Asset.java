package com.pigfox.ledger.domain;

import java.time.Instant;
import java.util.Map;

/**
 * A registered asset and the cryptographic evidence attached to it.
 *
 * @param id            node-assigned identifier
 * @param name          human readable asset name
 * @param assetType     caller-defined classification
 * @param owner         owning party
 * @param metadata      additional attributes folded into the canonical payload
 * @param payloadHash   0x-prefixed keccak256 of the canonical payload
 * @param signature     0x-prefixed 65-byte secp256k1 signature over {@code payloadHash}
 * @param signerAddress EIP-55 address of the key that produced {@code signature}
 * @param anchorTxHash  anchor transaction hash, or {@code null} when the chain was
 *                      unavailable or anchoring is disabled
 * @param createdAt     registration timestamp
 */
public record Asset(
        String id,
        String name,
        String assetType,
        String owner,
        Map<String, String> metadata,
        String payloadHash,
        String signature,
        String signerAddress,
        String anchorTxHash,
        Instant createdAt) {

    /** Compact constructor defends the map against later mutation by callers. */
    public Asset {
        metadata = metadata == null ? Map.of() : Map.copyOf(metadata);
    }

    /** @return true when this asset's hash reached the chain */
    public boolean anchored() {
        return anchorTxHash != null && !anchorTxHash.isBlank();
    }
}
