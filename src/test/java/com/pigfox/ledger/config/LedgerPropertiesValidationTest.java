package com.pigfox.ledger.config;

import static org.assertj.core.api.Assertions.assertThat;

import com.pigfox.ledger.TestFixtures;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.boot.test.context.runner.ApplicationContextRunner;
import org.springframework.context.annotation.Configuration;

/**
 * A missing secret must stop the node from starting. Booting with an empty signing key or
 * an empty JWT secret would leave a service that answers requests while being unable to
 * sign anything or verify a token, which is worse than not starting at all.
 */
class LedgerPropertiesValidationTest {

    private final ApplicationContextRunner runner = new ApplicationContextRunner()
            .withUserConfiguration(PropertiesHolder.class);

    private String[] validProperties() {
        return new String[]{
                "ledger.auth.client-id=" + TestFixtures.CLIENT_ID,
                "ledger.auth.client-secret=" + TestFixtures.CLIENT_SECRET,
                "ledger.auth.jwt-secret=" + TestFixtures.JWT_SECRET,
                "ledger.auth.token-ttl=PT15M",
                "ledger.auth.scope=ledger.read ledger.write",
                "ledger.crypto.signing-key=" + TestFixtures.PRIVATE_KEY,
                "ledger.chain.enabled=false",
                "ledger.chain.rpc-url=http://127.0.0.1:8545",
                "ledger.chain.registry-address=",
                "ledger.chain.chain-id=31337",
                "ledger.chain.gas-limit=150000",
                "ledger.chain.gas-price=1000000000",
                "ledger.events.topic=asset.events",
        };
    }

    private String[] withOverride(String key, String value) {
        String[] base = validProperties();
        for (int i = 0; i < base.length; i++) {
            if (base[i].startsWith(key + "=")) {
                base[i] = key + "=" + value;
            }
        }
        return base;
    }

    @Test
    @DisplayName("a fully configured node binds every property")
    void bindsValidConfiguration() {
        runner.withPropertyValues(validProperties()).run(context -> {
            assertThat(context).hasNotFailed();
            LedgerProperties properties = context.getBean(LedgerProperties.class);
            assertThat(properties.auth().clientId()).isEqualTo(TestFixtures.CLIENT_ID);
            assertThat(properties.crypto().signingKey()).isEqualTo(TestFixtures.PRIVATE_KEY);
            assertThat(properties.events().topic()).isEqualTo("asset.events");
            // A blank registry address is legal: it disables anchoring rather than failing.
            assertThat(properties.chain().registryAddress()).isEmpty();
        });
    }

    @Test
    @DisplayName("an unset LEDGER_SIGNING_KEY fails startup")
    void rejectsMissingSigningKey() {
        runner.withPropertyValues(withOverride("ledger.crypto.signing-key", ""))
                .run(context -> assertThat(context).hasFailed());
    }

    @Test
    @DisplayName("an unset LEDGER_JWT_SECRET fails startup")
    void rejectsMissingJwtSecret() {
        runner.withPropertyValues(withOverride("ledger.auth.jwt-secret", ""))
                .run(context -> assertThat(context).hasFailed());
    }

    @Test
    @DisplayName("a JWT secret too short for HS256 fails startup")
    void rejectsShortJwtSecret() {
        runner.withPropertyValues(withOverride("ledger.auth.jwt-secret", "too-short"))
                .run(context -> assertThat(context).hasFailed());
    }

    @Test
    @DisplayName("an unset LEDGER_CLIENT_ID fails startup")
    void rejectsMissingClientId() {
        runner.withPropertyValues(withOverride("ledger.auth.client-id", ""))
                .run(context -> assertThat(context).hasFailed());
    }

    @Test
    @DisplayName("an unset LEDGER_CLIENT_SECRET fails startup")
    void rejectsMissingClientSecret() {
        runner.withPropertyValues(withOverride("ledger.auth.client-secret", ""))
                .run(context -> assertThat(context).hasFailed());
    }

    @Test
    @DisplayName("a blank ETH_RPC_URL fails startup")
    void rejectsMissingRpcUrl() {
        runner.withPropertyValues(withOverride("ledger.chain.rpc-url", ""))
                .run(context -> assertThat(context).hasFailed());
    }

    @Test
    @DisplayName("a blank topic fails startup")
    void rejectsMissingTopic() {
        runner.withPropertyValues(withOverride("ledger.events.topic", ""))
                .run(context -> assertThat(context).hasFailed());
    }

    @Test
    @DisplayName("a non-positive chain id fails startup")
    void rejectsNonPositiveChainId() {
        runner.withPropertyValues(withOverride("ledger.chain.chain-id", "0"))
                .run(context -> assertThat(context).hasFailed());
    }

    @Test
    @DisplayName("a non-positive gas limit fails startup")
    void rejectsNonPositiveGasLimit() {
        runner.withPropertyValues(withOverride("ledger.chain.gas-limit", "0"))
                .run(context -> assertThat(context).hasFailed());
    }

    @Configuration(proxyBeanMethods = false)
    @EnableConfigurationProperties(LedgerProperties.class)
    static class PropertiesHolder {
    }
}
