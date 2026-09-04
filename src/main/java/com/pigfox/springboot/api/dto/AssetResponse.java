package com.pigfox.springboot.api.dto;

import com.pigfox.springboot.domain.Asset;
import io.swagger.v3.oas.annotations.media.Schema;
import java.time.Instant;
import java.util.Map;

/**
 * A registered asset as returned by the API.
 *
 * @param id            asset identifier
 * @param name          asset name
 * @param assetType     asset classification
 * @param owner         owning party
 * @param metadata      additional attributes
 * @param payloadHash   0x-prefixed keccak256 of the canonical payload
 * @param signature     0x-prefixed 65-byte secp256k1 signature
 * @param signerAddress EIP-55 address of the signing key
 * @param anchored      whether the hash reached the chain
 * @param anchorTxHash  anchor transaction hash, null when unanchored
 * @param createdAt     registration timestamp
 */
@Schema(name = "AssetResponse", description = "Registered asset with its cryptographic evidence")
public record AssetResponse(
        @Schema(format = "uuid") String id,
        String name,
        String assetType,
        String owner,
        Map<String, String> metadata,
        @Schema(pattern = "^0x[0-9a-f]{64}$") String payloadHash,
        @Schema(pattern = "^0x[0-9a-f]{130}$") String signature,
        @Schema(pattern = "^0x[0-9a-fA-F]{40}$") String signerAddress,
        boolean anchored,
        @Schema(nullable = true) String anchorTxHash,
        Instant createdAt) {

    /**
     * @param asset stored asset
     * @return the API view of that asset
     */
    public static AssetResponse from(Asset asset) {
        return new AssetResponse(
                asset.id(),
                asset.name(),
                asset.assetType(),
                asset.owner(),
                asset.metadata(),
                asset.payloadHash(),
                asset.signature(),
                asset.signerAddress(),
                asset.anchored(),
                asset.anchorTxHash(),
                asset.createdAt());
    }
}
