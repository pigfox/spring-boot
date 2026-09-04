package com.pigfox.springboot.config;

import jakarta.validation.Valid;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Positive;
import jakarta.validation.constraints.Size;
import java.math.BigInteger;
import java.time.Duration;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.validation.annotation.Validated;

/**
 * Externalised configuration for the node.
 *
 * <p>Every secret below is bound from a process environment variable in
 * {@code application.yml} and has no in-repo default. The {@code @NotBlank}
 * constraints turn a missing variable into a startup failure rather than a runtime
 * surprise, which is the behaviour a zero-trust deployment wants.
 */
@Validated
@ConfigurationProperties(prefix = "ledger")
public record LedgerProperties(
        @NotNull @Valid Auth auth,
        @NotNull @Valid Crypto crypto,
        @NotNull @Valid Chain chain,
        @NotNull @Valid Events events) {

    /**
     * Token endpoint configuration.
     *
     * @param clientId     expected client identifier, from {@code LEDGER_CLIENT_ID}
     * @param clientSecret expected client secret, from {@code LEDGER_CLIENT_SECRET}
     * @param jwtSecret    HS256 signing secret, from {@code LEDGER_JWT_SECRET}; HS256
     *                     requires at least 256 bits of key material
     * @param tokenTtl     lifetime of an issued token
     * @param scope        space-delimited scopes granted to a valid client
     */
    public record Auth(
            @NotBlank String clientId,
            @NotBlank String clientSecret,
            @NotBlank @Size(min = 32) String jwtSecret,
            @NotNull Duration tokenTtl,
            @NotBlank String scope) {

        /** Redacts both secrets; see {@link Crypto#toString()} for why. */
        @Override
        public String toString() {
            return "Auth[clientId=" + clientId + ", clientSecret=<redacted>, "
                    + "jwtSecret=<redacted>, tokenTtl=" + tokenTtl + ", scope=" + scope + "]";
        }
    }

    /**
     * Signing material.
     *
     * @param signingKey secp256k1 private key, from {@code LEDGER_SIGNING_KEY}
     */
    public record Crypto(@NotBlank String signingKey) {

        /**
         * Redacts the key.
         *
         * <p>A record's generated {@code toString} prints every component, and bound
         * configuration gets stringified in places this code does not control: binding
         * failure messages, {@code /actuator/configprops}, a debugger, a heap dump viewer.
         * The signing key is the one value in this service that must never appear
         * anywhere, so it does not get a default {@code toString}.
         */
        @Override
        public String toString() {
            return "Crypto[signingKey=<redacted>]";
        }
    }

    /**
     * EVM connection details.
     *
     * @param enabled         when false the node skips all chain I/O and reports assets
     *                        as unanchored
     * @param rpcUrl          JSON-RPC endpoint, from {@code ETH_RPC_URL}
     * @param registryAddress deployed {@code AssetRegistry} address; blank disables
     *                        anchoring while leaving the rest of the node functional
     * @param chainId         EIP-155 chain id used when signing raw transactions
     * @param gasLimit        gas cap for an anchor transaction
     * @param gasPrice        legacy gas price, adequate for anvil and most test nets
     */
    public record Chain(
            boolean enabled,
            @NotBlank String rpcUrl,
            String registryAddress,
            @Positive long chainId,
            @NotNull @Positive BigInteger gasLimit,
            @NotNull @Positive BigInteger gasPrice) {
    }

    /**
     * Kafka topic naming.
     *
     * @param topic topic carrying asset lifecycle events
     */
    public record Events(@NotBlank String topic) {
    }
}
