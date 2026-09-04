package com.pigfox.springboot.api.dto;

import com.pigfox.springboot.service.AssetService;
import io.swagger.v3.oas.annotations.media.Schema;
import java.time.Instant;

/**
 * Outcome of a signature and anchor check.
 *
 * @param assetId          asset identifier
 * @param payloadHash      hash recomputed from the stored fields
 * @param signatureValid   true when the recovered signer matches the stored signer
 * @param recoveredAddress address recovered from the signature, null when recovery failed
 * @param signerAddress    signer recorded at registration
 * @param anchored         whether the hash is known to the chain
 * @param anchorTxHash     anchor transaction hash, null when unanchored
 * @param verifiedAt       time the check ran
 */
@Schema(name = "VerificationResponse", description = "Signature recovery and anchor status")
public record VerificationResponse(
        @Schema(format = "uuid") String assetId,
        @Schema(pattern = "^0x[0-9a-f]{64}$") String payloadHash,
        @Schema(description = "True when the address recovered from the signature equals the stored signer")
        boolean signatureValid,
        @Schema(nullable = true) String recoveredAddress,
        String signerAddress,
        boolean anchored,
        @Schema(nullable = true) String anchorTxHash,
        Instant verifiedAt) {

    /**
     * @param verification service-level result
     * @param verifiedAt   time the check ran
     * @return the API view of that result
     */
    public static VerificationResponse from(AssetService.Verification verification, Instant verifiedAt) {
        return new VerificationResponse(
                verification.asset().id(),
                verification.payloadHash(),
                verification.signatureValid(),
                verification.recoveredAddress(),
                verification.asset().signerAddress(),
                verification.anchored(),
                verification.anchorTxHash(),
                verifiedAt);
    }
}
