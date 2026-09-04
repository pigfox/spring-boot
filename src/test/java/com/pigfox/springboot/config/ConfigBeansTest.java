package com.pigfox.springboot.config;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.pigfox.springboot.TestFixtures;
import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
import java.time.Instant;
import java.util.Map;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.security.authentication.AbstractAuthenticationToken;
import org.springframework.security.core.GrantedAuthority;
import org.springframework.security.oauth2.jose.jws.MacAlgorithm;
import org.springframework.security.oauth2.jwt.Jwt;
import org.springframework.security.oauth2.jwt.JwsHeader;
import org.springframework.security.oauth2.jwt.JwtClaimNames;
import org.springframework.security.oauth2.jwt.JwtDecoder;
import org.springframework.security.oauth2.jwt.JwtEncoder;
import org.springframework.security.oauth2.jwt.JwtEncoderParameters;
import org.springframework.security.oauth2.jwt.JwtClaimsSet;
import org.springframework.security.oauth2.jwt.JwtException;

class ConfigBeansTest {

    private final SecurityConfig securityConfig = new SecurityConfig();
    private final LedgerProperties properties = TestFixtures.properties();

    @Test
    @DisplayName("a token this node encodes is a token this node accepts")
    void encoderAndDecoderAgree() {
        JwtEncoder encoder = securityConfig.jwtEncoder(properties);
        JwtDecoder decoder = securityConfig.jwtDecoder(properties);
        Instant now = Instant.now();

        String token = hs256(encoder, JwtClaimsSet.builder()
                .issuer(SecurityConfig.ISSUER)
                .subject("client")
                .issuedAt(now)
                .expiresAt(now.plusSeconds(60))
                .claim("scope", "ledger.read")
                .build());

        assertThat(decoder.decode(token).getSubject()).isEqualTo("client");
    }

    @Test
    @DisplayName("a token minted by a different issuer is rejected")
    void rejectsForeignIssuer() {
        JwtEncoder encoder = securityConfig.jwtEncoder(properties);
        JwtDecoder decoder = securityConfig.jwtDecoder(properties);
        Instant now = Instant.now();
        String token = hs256(encoder, JwtClaimsSet.builder()
                .issuer("somebody-else")
                .subject("client")
                .issuedAt(now)
                .expiresAt(now.plusSeconds(60))
                .build());

        assertThatThrownBy(() -> decoder.decode(token)).isInstanceOf(JwtException.class);
    }

    @Test
    @DisplayName("an expired token is rejected")
    void rejectsExpiredToken() {
        JwtEncoder encoder = securityConfig.jwtEncoder(properties);
        JwtDecoder decoder = securityConfig.jwtDecoder(properties);
        Instant past = Instant.now().minusSeconds(7200);
        String token = hs256(encoder, JwtClaimsSet.builder()
                .issuer(SecurityConfig.ISSUER)
                .subject("client")
                .issuedAt(past)
                .expiresAt(past.plusSeconds(60))
                .build());

        assertThatThrownBy(() -> decoder.decode(token)).isInstanceOf(JwtException.class);
    }

    @Test
    @DisplayName("a token signed with a different secret is rejected")
    void rejectsWrongSignature() {
        LedgerProperties otherSecret = new LedgerProperties(
                new LedgerProperties.Auth(TestFixtures.CLIENT_ID, TestFixtures.CLIENT_SECRET,
                        "a-completely-different-hmac-material-value", properties.auth().tokenTtl(),
                        properties.auth().scope()),
                properties.crypto(), properties.chain(), properties.events());
        Instant now = Instant.now();
        String foreignToken = hs256(securityConfig.jwtEncoder(otherSecret), JwtClaimsSet.builder()
                .issuer(SecurityConfig.ISSUER)
                .subject("client")
                .issuedAt(now)
                .expiresAt(now.plusSeconds(60))
                .build());
        JwtDecoder decoder = securityConfig.jwtDecoder(properties);

        assertThatThrownBy(() -> decoder.decode(foreignToken)).isInstanceOf(JwtException.class);
    }

    @Test
    @DisplayName("the scope claim becomes SCOPE_-prefixed authorities")
    void convertsScopesToAuthorities() {
        Jwt jwt = Jwt.withTokenValue("token")
                .header("alg", "HS256")
                .claim(JwtClaimNames.SUB, "client")
                .claim("scope", "ledger.read ledger.write")
                .build();

        AbstractAuthenticationToken authentication =
                securityConfig.jwtAuthenticationConverter().convert(jwt);

        assertThat(authentication).isNotNull();
        assertThat(authentication.getAuthorities())
                .extracting(GrantedAuthority::getAuthority)
                .containsExactlyInAnyOrder("SCOPE_ledger.read", "SCOPE_ledger.write");
    }

    @Test
    @DisplayName("authorities under scp are ignored, because only scope is honoured")
    void ignoresScpClaim() {
        // Spring's default converter reads "scope" and falls back to "scp", so a token
        // minted by some other issuer could smuggle authorities in under scp. SecurityConfig
        // pins the claim name to "scope" precisely to close that door, and without this test
        // nothing notices if the pinning is removed: the default would still satisfy every
        // other assertion here, because every other token in this suite uses "scope".
        Jwt jwt = Jwt.withTokenValue("token")
                .header("alg", "HS256")
                .claim(JwtClaimNames.SUB, "client")
                .claim("scp", "ledger.read ledger.write")
                .build();

        AbstractAuthenticationToken authentication =
                securityConfig.jwtAuthenticationConverter().convert(jwt);

        assertThat(authentication).isNotNull();
        assertThat(authentication.getAuthorities()).isEmpty();
    }

    @Test
    @DisplayName("scp is ignored even when scope is present alongside it")
    void prefersScopeAndIgnoresScpBesideIt() {
        Jwt jwt = Jwt.withTokenValue("token")
                .header("alg", "HS256")
                .claim(JwtClaimNames.SUB, "client")
                .claim("scope", "ledger.read")
                .claim("scp", "ledger.write")
                .build();

        AbstractAuthenticationToken authentication =
                securityConfig.jwtAuthenticationConverter().convert(jwt);

        assertThat(authentication).isNotNull();
        assertThat(authentication.getAuthorities())
                .extracting(GrantedAuthority::getAuthority)
                .containsExactly("SCOPE_ledger.read");
    }

    @Test
    @DisplayName("a token with no scope claim carries no authorities")
    void convertsMissingScopeToNoAuthorities() {
        Jwt jwt = Jwt.withTokenValue("token")
                .header("alg", "HS256")
                .claim(JwtClaimNames.SUB, "client")
                .build();

        AbstractAuthenticationToken authentication =
                securityConfig.jwtAuthenticationConverter().convert(jwt);

        assertThat(authentication).isNotNull();
        assertThat(authentication.getAuthorities()).isEmpty();
    }

    @Test
    @DisplayName("the JSON-RPC client is built for the configured endpoint")
    void buildsWeb3jClient() {
        assertThat(new Web3Config().web3j(properties)).isNotNull();
    }

    @Test
    @DisplayName("the OpenAPI description mirrors the committed contract's identity")
    void describesApi() {
        var openApi = new OpenApiConfig().assetApi();

        assertThat(openApi.getInfo().getTitle()).isEqualTo(OpenApiConfig.TITLE);
        assertThat(openApi.getInfo().getVersion()).isEqualTo(OpenApiConfig.VERSION);
        assertThat(openApi.getInfo().getLicense().getName()).isEqualTo("Apache-2.0");
        assertThat(openApi.getServers()).singleElement()
                .extracting("url").isEqualTo("http://localhost:8087");
        assertThat(openApi.getComponents().getSecuritySchemes())
                .containsOnlyKeys(OpenApiConfig.BEARER_SCHEME);
        assertThat(openApi.getComponents().getSecuritySchemes().get(OpenApiConfig.BEARER_SCHEME))
                .extracting("scheme", "bearerFormat")
                .containsExactly("bearer", "JWT");
        assertThat(openApi.getSecurity()).singleElement()
                .satisfies(requirement -> assertThat(requirement).containsKey(OpenApiConfig.BEARER_SCHEME));
    }

    @Test
    @DisplayName("telemetry adds the @Timed aspect and a component tag on every meter")
    void configuresTelemetry() {
        TelemetryConfig telemetry = new TelemetryConfig();
        SimpleMeterRegistry registry = new SimpleMeterRegistry();
        registry.config().meterFilter(telemetry.ledgerNodeTag());

        registry.counter("some.counter").increment();

        assertThat(telemetry.timedAspect(registry)).isNotNull();
        assertThat(registry.get("some.counter").counter().getId().getTag("component"))
                .isEqualTo("spring-boot");
    }

    @Test
    @DisplayName("the properties record exposes every configured value")
    void exposesProperties() {
        LedgerProperties chainOn = TestFixtures.properties(true, TestFixtures.REGISTRY_ADDRESS);

        assertThat(chainOn.auth().clientId()).isEqualTo(TestFixtures.CLIENT_ID);
        assertThat(chainOn.auth().clientSecret()).isEqualTo(TestFixtures.CLIENT_SECRET);
        assertThat(chainOn.auth().jwtSecret()).isEqualTo(TestFixtures.JWT_SECRET);
        assertThat(chainOn.auth().scope()).isEqualTo("ledger.read ledger.write");
        assertThat(chainOn.crypto().signingKey()).isEqualTo(TestFixtures.PRIVATE_KEY);
        assertThat(chainOn.chain().enabled()).isTrue();
        assertThat(chainOn.chain().rpcUrl()).isEqualTo("http://127.0.0.1:8545");
        assertThat(chainOn.chain().registryAddress()).isEqualTo(TestFixtures.REGISTRY_ADDRESS);
        assertThat(chainOn.chain().chainId()).isEqualTo(31337L);
        assertThat(chainOn.chain().gasLimit()).isEqualTo(java.math.BigInteger.valueOf(150_000));
        assertThat(chainOn.chain().gasPrice()).isEqualTo(java.math.BigInteger.valueOf(1_000_000_000L));
        assertThat(chainOn.events().topic()).isEqualTo(TestFixtures.TOPIC);
    }

    @Test
    @DisplayName("the public route constants name exactly the two unauthenticated paths")
    void pinsPublicRoutes() {
        assertThat(Map.of(
                SecurityConfig.TOKEN_ENDPOINT, "token",
                SecurityConfig.HEALTH_ENDPOINT, "health"))
                .containsOnlyKeys("/api/v1/auth/token", "/actuator/health");
        assertThat(SecurityConfig.ISSUER).isEqualTo("https://spring-boot.local");
    }
    /**
     * Encodes with an explicit HS256 header. A shared secret can only sign a MAC, and the
     * encoder defaults to RS256, so omitting the header fails to select a key.
     */
    private String hs256(JwtEncoder encoder, JwtClaimsSet claims) {
        return encoder
                .encode(JwtEncoderParameters.from(JwsHeader.with(MacAlgorithm.HS256).build(), claims))
                .getTokenValue();
    }
}
