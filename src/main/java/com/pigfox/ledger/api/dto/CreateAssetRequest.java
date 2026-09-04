package com.pigfox.ledger.api.dto;

import io.swagger.v3.oas.annotations.media.Schema;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;
import java.util.Map;

/**
 * Payload registered on the ledger. Every field here feeds the canonical form that gets
 * hashed and signed, so a change to any of them changes the asset's identity.
 *
 * @param name      asset name
 * @param assetType asset classification
 * @param owner     owning party
 * @param metadata  additional string attributes
 */
@Schema(name = "CreateAssetRequest", description = "Asset registration payload")
public record CreateAssetRequest(
        @Schema(example = "Unit 4B, Harbour Court") @NotBlank @Size(max = 200) String name,
        @Schema(example = "REAL_ESTATE") @NotBlank @Size(max = 64) String assetType,
        @Schema(example = "Estate JV") @NotBlank @Size(max = 200) String owner,
        @Schema(description = "Free-form string attributes folded into the canonical payload")
        Map<String, String> metadata) {
}
