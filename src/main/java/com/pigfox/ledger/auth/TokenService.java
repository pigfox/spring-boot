package com.pigfox.ledger.auth;

import com.pigfox.ledger.config.LedgerProperties;
import com.pigfox.ledger.config.SecurityConfig;
import com.pigfox.ledger.domain.InvalidCredentialsException;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.time.Duration;
import java.time.Instant;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.security.oauth2.jose.jws.MacAlgorithm;
import org.springframework.security.oauth2.jwt.JwsHeader;
import org.springframework.security.oauth2.jwt.JwtClaimsSet;
import org.springframework.security.oauth2.jwt.JwtEncoder;
import org.springframework.security.oauth2.jwt.JwtEncoderParameters;
import org.springframework.stereotype.Service;

/**
 * Issues the short-lived HS256 tokens that the rest of the API requires.
 *
 * <p>Credential comparison uses {@link MessageDigest#isEqual}, which does not short
 * circuit on the first differing byte. A naive {@code equals} leaks the length of the
 * matching prefix through response timing, which is enough to recover a secret one byte
 * at a time.
 */
@Service
public class TokenService {

    private static final Logger log = LoggerFactory.getLogger(TokenService.class);

    private final JwtEncoder encoder;
    private final LedgerProperties.Auth auth;

    public TokenService(JwtEncoder encoder, LedgerProperties properties) {
        this.encoder = encoder;
        this.auth = properties.auth();
    }

    /**
     * Validates client credentials and mints a token.
     *
     * @param clientId     presented client identifier
     * @param clientSecret presented client secret
     * @return a signed token together with its lifetime and granted scopes
     * @throws InvalidCredentialsException when either credential does not match
     */
    public IssuedToken issue(String clientId, String clientSecret) {
        if (!matches(clientId, auth.clientId()) || !matches(clientSecret, auth.clientSecret())) {
            // Logs the attempt, never the presented values.
            log.warn("Rejected token request with invalid credentials");
            throw new InvalidCredentialsException();
        }
        Instant now = Instant.now();
        Duration ttl = auth.tokenTtl();
        JwtClaimsSet claims = JwtClaimsSet.builder()
                .issuer(SecurityConfig.ISSUER)
                .subject(clientId)
                .issuedAt(now)
                .expiresAt(now.plus(ttl))
                .claim("scope", auth.scope())
                .build();
        String token = encoder
                .encode(JwtEncoderParameters.from(
                        JwsHeader.with(MacAlgorithm.HS256).build(), claims))
                .getTokenValue();
        log.info("Issued token for client {} valid for {}s", clientId, ttl.toSeconds());
        return new IssuedToken(token, ttl.toSeconds(), auth.scope());
    }

    private boolean matches(String presented, String expected) {
        if (presented == null) {
            return false;
        }
        return MessageDigest.isEqual(
                presented.getBytes(StandardCharsets.UTF_8), expected.getBytes(StandardCharsets.UTF_8));
    }

    /**
     * A freshly minted token.
     *
     * @param token     signed JWT
     * @param expiresIn lifetime in seconds
     * @param scope     space-delimited granted scopes
     */
    public record IssuedToken(String token, long expiresIn, String scope) {
    }
}
