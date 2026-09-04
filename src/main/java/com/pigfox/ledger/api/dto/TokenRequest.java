package com.pigfox.ledger.api.dto;

import io.swagger.v3.oas.annotations.media.Schema;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;

/**
 * Client credentials presented to the token endpoint.
 *
 * @param clientId     client identifier
 * @param clientSecret client secret
 */
@Schema(name = "TokenRequest", description = "Client credentials grant")
public record TokenRequest(
        @Schema(example = "ledger-client") @NotBlank @Size(max = 128) String clientId,
        @Schema(format = "password") @NotBlank String clientSecret) {
}
