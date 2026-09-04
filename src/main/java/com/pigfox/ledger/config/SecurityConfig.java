package com.pigfox.ledger.config;

import com.nimbusds.jose.jwk.source.ImmutableSecret;
import java.nio.charset.StandardCharsets;
import javax.crypto.SecretKey;
import javax.crypto.spec.SecretKeySpec;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.http.HttpMethod;
import org.springframework.security.config.Customizer;
import org.springframework.security.config.annotation.method.configuration.EnableMethodSecurity;
import org.springframework.security.config.annotation.web.builders.HttpSecurity;
import org.springframework.security.config.http.SessionCreationPolicy;
import org.springframework.security.oauth2.jose.jws.MacAlgorithm;
import org.springframework.security.oauth2.jwt.JwtDecoder;
import org.springframework.security.oauth2.jwt.JwtEncoder;
import org.springframework.security.oauth2.jwt.NimbusJwtDecoder;
import org.springframework.security.oauth2.jwt.NimbusJwtEncoder;
import org.springframework.security.oauth2.server.resource.authentication.JwtAuthenticationConverter;
import org.springframework.security.oauth2.server.resource.authentication.JwtGrantedAuthoritiesConverter;
import org.springframework.security.web.SecurityFilterChain;

/**
 * Zero-trust HTTP perimeter.
 *
 * <p>The chain is deny-by-default: {@code anyRequest().authenticated()} is the last rule,
 * so a route added tomorrow is protected without anyone remembering to protect it. Only
 * two things are public, and both are unavoidable — the token endpoint that bootstraps a
 * credential, and the health probe an orchestrator needs before a token exists. The API
 * documentation is not public; a reader needs a token like any other caller.
 *
 * <p>There is no session: {@link SessionCreationPolicy#STATELESS} means every request
 * carries its own proof. CSRF protection is switched off for exactly that reason — there
 * is no ambient cookie authority for an attacker to ride on.
 *
 * <p>Coarse route rules are only the first gate. Writes carry a second, independent
 * check at the method level via {@link EnableMethodSecurity}, so a mistake in the URL
 * matchers above is not on its own enough to authorise a write.
 */
@Configuration
@EnableMethodSecurity
public class SecurityConfig {

    /** Public: the token endpoint, since a caller cannot present a token to obtain one. */
    static final String TOKEN_ENDPOINT = "/api/v1/auth/token";
    /** Public: liveness and readiness, which must answer before any credential exists. */
    static final String HEALTH_ENDPOINT = "/actuator/health";
    /**
     * Expected issuer of every accepted token.
     *
     * <p>A URI rather than a bare name: the {@code iss} claim is a StringOrURI, and
     * Spring's {@code Jwt.getIssuer()} converts it to a URL, so a bare name would make
     * every reader of that claim throw.
     */
    public static final String ISSUER = "https://ledger-node.local";

    /**
     * @param http           builder supplied by Spring Security
     * @param jwtDecoder     decoder for the HS256 tokens this node issues
     * @return the single, deny-by-default filter chain
     * @throws Exception if the chain cannot be built
     */
    @Bean
    public SecurityFilterChain filterChain(HttpSecurity http, JwtDecoder jwtDecoder) throws Exception {
        return http
                .csrf(csrf -> csrf.disable())
                .sessionManagement(session -> session.sessionCreationPolicy(SessionCreationPolicy.STATELESS))
                .authorizeHttpRequests(auth -> auth
                        .requestMatchers(HttpMethod.POST, TOKEN_ENDPOINT).permitAll()
                        .requestMatchers(HEALTH_ENDPOINT, HEALTH_ENDPOINT + "/**").permitAll()
                        .anyRequest().authenticated())
                .oauth2ResourceServer(oauth2 -> oauth2.jwt(jwt -> jwt
                        .decoder(jwtDecoder)
                        .jwtAuthenticationConverter(jwtAuthenticationConverter())))
                .headers(headers -> headers
                        .frameOptions(frame -> frame.deny())
                        .contentTypeOptions(Customizer.withDefaults())
                        .httpStrictTransportSecurity(hsts -> hsts.includeSubDomains(true))
                        .referrerPolicy(Customizer.withDefaults())
                        .contentSecurityPolicy(csp -> csp.policyDirectives("default-src 'self'")))
                .build();
    }

    /**
     * Maps the space-delimited {@code scope} claim onto {@code SCOPE_}-prefixed
     * authorities, which is what the {@code @PreAuthorize} expressions on the
     * controllers check.
     *
     * @return converter from token to authenticated principal
     */
    @Bean
    public JwtAuthenticationConverter jwtAuthenticationConverter() {
        JwtGrantedAuthoritiesConverter authorities = new JwtGrantedAuthoritiesConverter();
        authorities.setAuthorityPrefix("SCOPE_");
        authorities.setAuthoritiesClaimName("scope");
        JwtAuthenticationConverter converter = new JwtAuthenticationConverter();
        converter.setJwtGrantedAuthoritiesConverter(authorities);
        return converter;
    }

    /**
     * @param properties node configuration supplying {@code LEDGER_JWT_SECRET}
     * @return decoder that accepts only HS256 tokens issued by this node
     */
    @Bean
    public JwtDecoder jwtDecoder(LedgerProperties properties) {
        NimbusJwtDecoder decoder = NimbusJwtDecoder
                .withSecretKey(secretKey(properties))
                .macAlgorithm(MacAlgorithm.HS256)
                .build();
        // Pins the algorithm and the issuer: a token minted elsewhere is rejected even if
        // it happens to be well formed.
        decoder.setJwtValidator(org.springframework.security.oauth2.jwt.JwtValidators
                .createDefaultWithIssuer(ISSUER));
        return decoder;
    }

    /**
     * @param properties node configuration supplying {@code LEDGER_JWT_SECRET}
     * @return encoder used by the token endpoint
     */
    @Bean
    public JwtEncoder jwtEncoder(LedgerProperties properties) {
        return new NimbusJwtEncoder(new ImmutableSecret<>(secretKey(properties)));
    }

    private SecretKey secretKey(LedgerProperties properties) {
        return new SecretKeySpec(
                properties.auth().jwtSecret().getBytes(StandardCharsets.UTF_8), "HmacSHA256");
    }
}
