package com.pigfox.ledger.api;

import static org.assertj.core.api.Assertions.assertThat;

import com.pigfox.ledger.TestFixtures;
import com.pigfox.ledger.kafka.AssetEventPublisher;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.mock.mockito.MockBean;
import org.springframework.boot.test.web.client.TestRestTemplate;
import org.springframework.boot.test.web.server.LocalManagementPort;
import org.springframework.boot.test.web.server.LocalServerPort;
import org.springframework.http.HttpStatus;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.springframework.test.context.TestPropertySource;

/**
 * Telemetry runs on its own port in production, so it is tested on its own port here.
 *
 * <p>This is the one test that starts a real server with the real two-port topology. It
 * matters because the split changes the answer: a MockMvc test against the API context
 * cannot see the management context at all, so a claim like "health is public" is only
 * meaningful when asserted against the port that actually serves it. The ports are
 * randomised rather than pinned to 8087 and 55437 so the suite never collides with a
 * running instance.
 */
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
@TestPropertySource(properties = {
        "ledger.auth.client-id=" + TestFixtures.CLIENT_ID,
        "ledger.auth.client-secret=" + TestFixtures.CLIENT_SECRET,
        "ledger.auth.jwt-secret=" + TestFixtures.JWT_SECRET,
        "ledger.chain.enabled=false",
        "spring.kafka.listener.auto-startup=false",
        "spring.kafka.admin.auto-create=false",
        "management.tracing.enabled=false",
        "management.server.port=0",
})
class ManagementPortSecurityTest {

    @MockBean
    private AssetEventPublisher assetEventPublisher;

    @Autowired
    private TestRestTemplate rest;

    @LocalServerPort
    private int apiPort;

    @LocalManagementPort
    private int managementPort;

    @DynamicPropertySource
    static void signingKey(DynamicPropertyRegistry registry) {
        registry.add("ledger.crypto.signing-key", () -> TestFixtures.PRIVATE_KEY);
    }

    private HttpStatus statusOnManagementPort(String path) {
        return HttpStatus.valueOf(rest
                .getForEntity("http://localhost:" + managementPort + path, String.class)
                .getStatusCode()
                .value());
    }

    @Test
    @DisplayName("the two ports are distinct, as the deployment expects")
    void servesTwoPorts() {
        assertThat(managementPort).isNotEqualTo(apiPort).isPositive();
    }

    @Test
    @DisplayName("the health probe is public, because an orchestrator has no token")
    void healthIsPublic() {
        assertThat(statusOnManagementPort("/actuator/health")).isEqualTo(HttpStatus.OK);
    }

    @ParameterizedTest
    @DisplayName("the liveness and readiness probes are public too")
    @CsvSource({"/actuator/health/liveness", "/actuator/health/readiness"})
    void healthGroupsArePublic(String path) {
        assertThat(statusOnManagementPort(path)).isEqualTo(HttpStatus.OK);
    }

    @ParameterizedTest
    @DisplayName("no other telemetry endpoint is public, health is the only exemption")
    @CsvSource({"/actuator/prometheus", "/actuator/metrics", "/actuator/info"})
    void otherEndpointsRequireAuthentication(String path) {
        assertThat(statusOnManagementPort(path)).isEqualTo(HttpStatus.UNAUTHORIZED);
    }

    @Test
    @DisplayName("deny-by-default reaches the management port too")
    void unlistedManagementRoutesAreDenied() {
        assertThat(statusOnManagementPort("/actuator/env")).isEqualTo(HttpStatus.UNAUTHORIZED);
        assertThat(statusOnManagementPort("/nothing-here")).isEqualTo(HttpStatus.UNAUTHORIZED);
    }

    @Test
    @DisplayName("the API port serves no telemetry, and the API itself still needs a token")
    void apiPortServesNoTelemetry() {
        assertThat(rest.getForEntity(
                "http://localhost:" + apiPort + "/actuator/health", String.class).getStatusCode().value())
                .isEqualTo(HttpStatus.UNAUTHORIZED.value());
        assertThat(rest.getForEntity(
                "http://localhost:" + apiPort + "/api/v1/assets", String.class).getStatusCode().value())
                .isEqualTo(HttpStatus.UNAUTHORIZED.value());
    }
}
