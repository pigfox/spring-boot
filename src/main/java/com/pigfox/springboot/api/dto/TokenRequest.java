package com.pigfox.springboot.api.dto;

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

    /**
     * Redacts the secret.
     *
     * <p>A record's generated {@code toString} prints every component, and this object is
     * stringified by things outside this codebase: Spring's
     * {@code RequestResponseBodyMethodProcessor} logs the deserialised body at DEBUG, and
     * an exception or a debugger will do the same. The default therefore writes a live
     * client secret into any log the moment web logging is turned up, which is exactly
     * when someone is most likely to be sharing that log.
     */
    @Override
    public String toString() {
        return "TokenRequest[clientId=" + clientId + ", clientSecret=<redacted>]";
    }
}
