package com.pigfox.ledger.auth;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.nimbusds.jose.jwk.source.ImmutableSecret;
import com.pigfox.ledger.TestFixtures;
import com.pigfox.ledger.config.LedgerProperties;
import com.pigfox.ledger.config.SecurityConfig;
import com.pigfox.ledger.domain.InvalidCredentialsException;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import javax.crypto.SecretKey;
import javax.crypto.spec.SecretKeySpec;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.security.oauth2.jose.jws.MacAlgorithm;
import org.springframework.security.oauth2.jwt.Jwt;
import org.springframework.security.oauth2.jwt.JwtDecoder;
import org.springframework.security.oauth2.jwt.JwtEncoder;
import org.springframework.security.oauth2.jwt.NimbusJwtDecoder;
import org.springframework.security.oauth2.jwt.NimbusJwtEncoder;

class TokenServiceTest {

    private final LedgerProperties properties = TestFixtures.properties();
    private final SecretKey key = new SecretKeySpec(
            TestFixtures.JWT_SECRET.getBytes(StandardCharsets.UTF_8), "HmacSHA256");
    private final JwtEncoder encoder = new NimbusJwtEncoder(new ImmutableSecret<>(key));
    private final JwtDecoder decoder = NimbusJwtDecoder.withSecretKey(key)
            .macAlgorithm(MacAlgorithm.HS256)
            .build();
    private final TokenService service = new TokenService(encoder, properties);

    @Test
    @DisplayName("valid credentials mint a token carrying the configured scopes")
    void issuesToken() {
        TokenService.IssuedToken issued = service.issue(TestFixtures.CLIENT_ID, TestFixtures.CLIENT_SECRET);

        assertThat(issued.token()).isNotBlank();
        assertThat(issued.expiresIn()).isEqualTo(Duration.ofMinutes(15).toSeconds());
        assertThat(issued.scope()).isEqualTo("ledger.read ledger.write");
    }

    @Test
    @DisplayName("the minted token is a verifiable HS256 JWT with the expected claims")
    void mintsVerifiableToken() {
        String token = service.issue(TestFixtures.CLIENT_ID, TestFixtures.CLIENT_SECRET).token();

        Jwt decoded = decoder.decode(token);

        assertThat(decoded.getIssuer()).hasToString(SecurityConfig.ISSUER);
        assertThat(decoded.getSubject()).isEqualTo(TestFixtures.CLIENT_ID);
        assertThat(decoded.getClaimAsString("scope")).isEqualTo("ledger.read ledger.write");
        assertThat(decoded.getHeaders()).containsEntry("alg", "HS256");
        assertThat(decoded.getIssuedAt()).isNotNull();
        assertThat(decoded.getExpiresAt()).isAfter(decoded.getIssuedAt());
    }

    @Test
    @DisplayName("the token expires after the configured lifetime")
    void honoursConfiguredLifetime() {
        LedgerProperties shortLived = new LedgerProperties(
                new LedgerProperties.Auth(TestFixtures.CLIENT_ID, TestFixtures.CLIENT_SECRET,
                        TestFixtures.JWT_SECRET, Duration.ofSeconds(30), "ledger.read"),
                properties.crypto(), properties.chain(), properties.events());

        TokenService.IssuedToken issued = new TokenService(encoder, shortLived)
                .issue(TestFixtures.CLIENT_ID, TestFixtures.CLIENT_SECRET);

        assertThat(issued.expiresIn()).isEqualTo(30L);
        assertThat(issued.scope()).isEqualTo("ledger.read");
        Jwt decoded = decoder.decode(issued.token());
        assertThat(Duration.between(decoded.getIssuedAt(), decoded.getExpiresAt()))
                .isEqualTo(Duration.ofSeconds(30));
    }

    @Test
    @DisplayName("a wrong client id is rejected")
    void rejectsWrongClientId() {
        assertThatThrownBy(() -> service.issue("someone-else", TestFixtures.CLIENT_SECRET))
                .isInstanceOf(InvalidCredentialsException.class);
    }

    @Test
    @DisplayName("a wrong secret is rejected")
    void rejectsWrongSecret() {
        assertThatThrownBy(() -> service.issue(TestFixtures.CLIENT_ID, "wrong"))
                .isInstanceOf(InvalidCredentialsException.class);
    }

    @Test
    @DisplayName("a secret that is a prefix of the real one is rejected")
    void rejectsPrefixOfSecret() {
        assertThatThrownBy(() -> service.issue(
                TestFixtures.CLIENT_ID, TestFixtures.CLIENT_SECRET.substring(0, 5)))
                .isInstanceOf(InvalidCredentialsException.class);
    }

    @Test
    @DisplayName("null credentials are rejected rather than throwing a null pointer")
    void rejectsNullCredentials() {
        assertThatThrownBy(() -> service.issue(null, TestFixtures.CLIENT_SECRET))
                .isInstanceOf(InvalidCredentialsException.class);
        assertThatThrownBy(() -> service.issue(TestFixtures.CLIENT_ID, null))
                .isInstanceOf(InvalidCredentialsException.class);
    }

    @Test
    @DisplayName("the rejection reveals nothing about which credential was wrong")
    void rejectionIsUniform() {
        String wrongId = catchMessage(() -> service.issue("x", TestFixtures.CLIENT_SECRET));
        String wrongSecret = catchMessage(() -> service.issue(TestFixtures.CLIENT_ID, "x"));

        assertThat(wrongId).isEqualTo(wrongSecret).isEqualTo("Invalid client credentials");
    }

    private String catchMessage(Runnable call) {
        try {
            call.run();
            throw new AssertionError("expected InvalidCredentialsException");
        } catch (InvalidCredentialsException e) {
            return e.getMessage();
        }
    }
}
