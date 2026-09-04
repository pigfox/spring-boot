package com.pigfox.springboot.api.dto;

import io.swagger.v3.oas.annotations.media.Schema;

/**
 * A minted access token.
 *
 * @param accessToken signed HS256 JWT
 * @param tokenType   always {@code Bearer}
 * @param expiresIn   lifetime in seconds
 * @param scope       space-delimited granted scopes
 */
@Schema(name = "TokenResponse", description = "Issued bearer token")
public record TokenResponse(
        @Schema(description = "Signed HS256 JWT") String accessToken,
        @Schema(example = "Bearer", allowableValues = "Bearer") String tokenType,
        @Schema(description = "Lifetime in seconds", example = "900") long expiresIn,
        @Schema(example = "ledger.read ledger.write") String scope) {

    /** Token type this node issues. */
    public static final String BEARER = "Bearer";

    /**
     * @param accessToken signed JWT
     * @param expiresIn   lifetime in seconds
     * @param scope       granted scopes
     * @return a bearer token response
     */
    public static TokenResponse bearer(String accessToken, long expiresIn, String scope) {
        return new TokenResponse(accessToken, BEARER, expiresIn, scope);
    }
}
