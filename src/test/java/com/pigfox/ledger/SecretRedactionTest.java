package com.pigfox.ledger;

import static org.assertj.core.api.Assertions.assertThat;

import com.pigfox.ledger.api.dto.TokenRequest;
import com.pigfox.ledger.auth.TokenService;
import com.pigfox.ledger.config.LedgerProperties;
import java.time.Duration;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

/**
 * Every type that holds a credential must redact it when stringified.
 *
 * <p>This is not hypothetical. A record's generated {@code toString} prints every
 * component, and these objects are stringified by code outside this repository: Spring's
 * {@code RequestResponseBodyMethodProcessor} logs a deserialised request body at DEBUG,
 * configuration binding failures quote the bound object, and any debugger or heap dump
 * viewer does the same. Running the application with {@code org.springframework.web} at
 * DEBUG wrote a live client secret straight into the log, which is how this was found —
 * and the moment someone turns web logging up is exactly the moment they are most likely
 * to paste that log somewhere.
 *
 * <p>The secrets used here are obvious sentinels, so a failure names precisely what leaked.
 */
class SecretRedactionTest {

    private static final String SECRET = "SENTINEL-client-secret-must-not-appear";
    private static final String JWT_SECRET = "SENTINEL-jwt-secret-must-not-appear-either";
    private static final String SIGNING_KEY = "SENTINEL-signing-key-must-never-appear";
    private static final String TOKEN = "SENTINEL-bearer-token-must-not-appear";

    @Test
    @DisplayName("a token request does not print the client secret")
    void tokenRequestRedactsSecret() {
        String rendered = new TokenRequest("demo", SECRET).toString();

        assertThat(rendered).doesNotContain(SECRET).contains("<redacted>");
        // The non-secret half stays legible, because a redacted log is still a log.
        assertThat(rendered).contains("demo");
    }

    @Test
    @DisplayName("bound auth configuration does not print either of its secrets")
    void authPropertiesRedactSecrets() {
        String rendered = new LedgerProperties.Auth(
                "demo", SECRET, JWT_SECRET, Duration.ofMinutes(15), "ledger.read").toString();

        assertThat(rendered)
                .doesNotContain(SECRET)
                .doesNotContain(JWT_SECRET)
                .contains("<redacted>")
                .contains("demo")
                .contains("ledger.read");
    }

    @Test
    @DisplayName("bound crypto configuration does not print the signing key")
    void cryptoPropertiesRedactSigningKey() {
        String rendered = new LedgerProperties.Crypto(SIGNING_KEY).toString();

        assertThat(rendered).doesNotContain(SIGNING_KEY).contains("<redacted>");
    }

    @Test
    @DisplayName("a whole properties tree does not print any secret it contains")
    void wholePropertiesTreeRedacts() {
        LedgerProperties properties = new LedgerProperties(
                new LedgerProperties.Auth("demo", SECRET, JWT_SECRET, Duration.ofMinutes(15), "s"),
                new LedgerProperties.Crypto(SIGNING_KEY),
                TestFixtures.properties().chain(),
                TestFixtures.properties().events());

        // The outer record's generated toString delegates to the nested ones.
        assertThat(properties.toString())
                .doesNotContain(SECRET)
                .doesNotContain(JWT_SECRET)
                .doesNotContain(SIGNING_KEY);
    }

    @Test
    @DisplayName("an issued token does not print the bearer")
    void issuedTokenRedactsBearer() {
        String rendered = new TokenService.IssuedToken(TOKEN, 900L, "ledger.read").toString();

        assertThat(rendered)
                .doesNotContain(TOKEN)
                .contains("<redacted>")
                .contains("900")
                .contains("ledger.read");
    }

    @Test
    @DisplayName("the real signing key from configuration is not printed either")
    void realSigningKeyIsRedacted() {
        assertThat(TestFixtures.properties().toString())
                .doesNotContain(TestFixtures.PRIVATE_KEY)
                .doesNotContain(TestFixtures.JWT_SECRET)
                .doesNotContain(TestFixtures.CLIENT_SECRET);
    }
}
