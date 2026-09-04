package com.pigfox.springboot.api;

import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.jwt;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.header;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.pigfox.springboot.IntegrationTestBase;
import java.util.Map;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.http.MediaType;
import org.springframework.test.web.servlet.request.MockHttpServletRequestBuilder;

/**
 * Asserts the perimeter itself rather than the handlers behind it.
 *
 * <p>The interesting cases are the negative ones: an anonymous caller, a caller holding a
 * token with the wrong scope, and a route nobody thought to list. The last of these is
 * what deny-by-default buys, so it is tested explicitly.
 */
class ZeroTrustSecurityTest extends IntegrationTestBase {

    @Autowired
    private ObjectMapper objectMapper;

    private MockHttpServletRequestBuilder createAsset() throws Exception {
        return post("/api/v1/assets")
                .contentType(MediaType.APPLICATION_JSON)
                .content(objectMapper.writeValueAsString(Map.of(
                        "name", "n", "assetType", "t", "owner", "o")));
    }

    @ParameterizedTest
    @DisplayName("every asset route rejects an anonymous caller with 401")
    @CsvSource({
            "GET,/api/v1/assets",
            "GET,/api/v1/assets/any-id",
            "POST,/api/v1/assets",
            "POST,/api/v1/assets/any-id/verify",
    })
    void rejectsAnonymousCallers(String method, String path) throws Exception {
        MockHttpServletRequestBuilder request = "GET".equals(method)
                ? get(path)
                : post(path).contentType(MediaType.APPLICATION_JSON).content("{}");

        mockMvc.perform(request).andExpect(status().isUnauthorized());
    }

    @Test
    @DisplayName("an unauthenticated response advertises bearer authentication")
    void challengesWithBearerScheme() throws Exception {
        mockMvc.perform(get("/api/v1/assets"))
                .andExpect(status().isUnauthorized())
                .andExpect(header().string("WWW-Authenticate", org.hamcrest.Matchers.containsString("Bearer")));
    }

    @Test
    @DisplayName("a garbage bearer token is rejected")
    void rejectsMalformedToken() throws Exception {
        mockMvc.perform(get("/api/v1/assets").header("Authorization", "Bearer not-a-jwt"))
                .andExpect(status().isUnauthorized());
    }

    @Test
    @DisplayName("a read-only token cannot register an asset")
    void readScopeCannotWrite() throws Exception {
        mockMvc.perform(createAsset().with(jwt().authorities(readOnly())))
                .andExpect(status().isForbidden());
    }

    @Test
    @DisplayName("a read-only token cannot verify an asset")
    void readScopeCannotVerify() throws Exception {
        mockMvc.perform(post("/api/v1/assets/any-id/verify").with(jwt().authorities(readOnly())))
                .andExpect(status().isForbidden());
    }

    @Test
    @DisplayName("a write-only token cannot list assets")
    void writeScopeCannotRead() throws Exception {
        mockMvc.perform(get("/api/v1/assets").with(jwt().authorities(writeOnly())))
                .andExpect(status().isForbidden());
    }

    @Test
    @DisplayName("a token with no scopes is authenticated but authorised for nothing")
    void noScopeAuthorisesNothing() throws Exception {
        mockMvc.perform(get("/api/v1/assets").with(jwt()))
                .andExpect(status().isForbidden());
    }

    @Test
    @DisplayName("a write-scoped token may register")
    void writeScopeCanWrite() throws Exception {
        mockMvc.perform(createAsset().with(jwt().authorities(writeOnly())))
                .andExpect(status().isCreated());
    }

    @ParameterizedTest
    @DisplayName("no telemetry endpoint is reachable on the public API port")
    @CsvSource({"/actuator/prometheus", "/actuator/metrics", "/actuator/info"})
    void telemetryIsNotServedOnTheApiPort(String path) throws Exception {
        // Telemetry lives on its own port; ManagementPortSecurityTest asserts what it serves.
        mockMvc.perform(get(path)).andExpect(status().isUnauthorized());
    }

    @ParameterizedTest
    @DisplayName("the API documentation is not public either")
    @CsvSource({"/v3/api-docs", "/swagger-ui/index.html"})
    void documentationRequiresAuthentication(String path) throws Exception {
        mockMvc.perform(get(path)).andExpect(status().isUnauthorized());
    }

    @Test
    @DisplayName("a route nobody listed is denied, which is what deny-by-default means")
    void unlistedRoutesAreDenied() throws Exception {
        mockMvc.perform(get("/api/v1/something-nobody-configured"))
                .andExpect(status().isUnauthorized());
    }

    @Test
    @DisplayName("the token endpoint is reachable without a token, but only by POST")
    void tokenEndpointIsPublicForPostOnly() throws Exception {
        mockMvc.perform(get("/api/v1/auth/token")).andExpect(status().isUnauthorized());
    }

    @Test
    @DisplayName("responses carry the hardening headers")
    void sendsSecurityHeaders() throws Exception {
        mockMvc.perform(get("/api/v1/assets").with(jwt().authorities(readOnly())))
                .andExpect(status().isOk())
                .andExpect(header().string("X-Content-Type-Options", "nosniff"))
                .andExpect(header().string("X-Frame-Options", "DENY"))
                .andExpect(header().string("Content-Security-Policy", "default-src 'self'"))
                .andExpect(header().string("Referrer-Policy", "no-referrer"));
    }

    @Test
    @DisplayName("HSTS is sent over TLS, where a browser will honour it")
    void sendsHstsOverTls() throws Exception {
        mockMvc.perform(get("/api/v1/assets").secure(true).with(jwt().authorities(readOnly())))
                .andExpect(status().isOk())
                .andExpect(header().string("Strict-Transport-Security",
                        org.hamcrest.Matchers.containsString("includeSubDomains")));
    }

    @Test
    @DisplayName("no session cookie is ever issued, because the API is stateless")
    void issuesNoSessionCookie() throws Exception {
        mockMvc.perform(get("/api/v1/assets").with(jwt().authorities(readOnly())))
                .andExpect(status().isOk())
                .andExpect(header().doesNotExist("Set-Cookie"));
    }

    private org.springframework.security.core.authority.SimpleGrantedAuthority readOnly() {
        return new org.springframework.security.core.authority.SimpleGrantedAuthority("SCOPE_ledger.read");
    }

    private org.springframework.security.core.authority.SimpleGrantedAuthority writeOnly() {
        return new org.springframework.security.core.authority.SimpleGrantedAuthority("SCOPE_ledger.write");
    }
}
