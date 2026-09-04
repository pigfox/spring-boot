package com.pigfox.springboot.api;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.jwt;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.pigfox.springboot.TestFixtures;
import io.micrometer.core.instrument.MeterRegistry;
import java.util.Map;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.http.MediaType;
import org.springframework.security.core.authority.SimpleGrantedAuthority;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.springframework.test.context.TestPropertySource;
import org.springframework.test.web.servlet.MockMvc;

/**
 * Registration must survive a dead broker.
 *
 * <p>This is the one integration test that uses the <em>real</em> event publisher. Every
 * other one mocks it, and that mock is precisely what hid the original bug: with no broker,
 * {@code KafkaTemplate.send} does not return a failed future, it blocks for
 * {@code max.block.ms} and then throws on the request thread. A mocked publisher never
 * throws, so the suite was green while a real deployment answered 500 to every write.
 *
 * <p>Bootstrapping to a closed port rather than an unroutable address keeps it fast: the
 * connection is refused immediately and the producer gives up at {@code max.block.ms}.
 */
@SpringBootTest
@AutoConfigureMockMvc
@TestPropertySource(properties = {
        "ledger.auth.client-id=" + TestFixtures.CLIENT_ID,
        "ledger.auth.client-secret=" + TestFixtures.CLIENT_SECRET,
        "ledger.auth.jwt-secret=" + TestFixtures.JWT_SECRET,
        "ledger.chain.enabled=false",
        "spring.kafka.listener.auto-startup=false",
        "spring.kafka.admin.auto-create=false",
        "management.tracing.enabled=false",
        // Port 1 is privileged and unbound: connections are refused, not dropped.
        "spring.kafka.bootstrap-servers=localhost:1",
})
class BrokerOutageIntegrationTest {

    @Autowired
    private MockMvc mockMvc;

    @Autowired
    private ObjectMapper objectMapper;

    @Autowired
    private MeterRegistry meterRegistry;

    @DynamicPropertySource
    static void signingKey(DynamicPropertyRegistry registry) {
        registry.add("ledger.crypto.signing-key", () -> TestFixtures.PRIVATE_KEY);
    }

    @Test
    @DisplayName("an asset is still registered, signed and returned when the broker is down")
    void registrationSurvivesBrokerOutage() throws Exception {
        String body = mockMvc.perform(post("/api/v1/assets")
                        .with(jwt().authorities(new SimpleGrantedAuthority("SCOPE_ledger.write")))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(Map.of(
                                "name", "Unit 4B, Harbour Court",
                                "assetType", "REAL_ESTATE",
                                "owner", "Estate JV"))))
                .andExpect(status().isCreated())
                .andExpect(jsonPath("$.id").isNotEmpty())
                .andExpect(jsonPath("$.signerAddress").value(TestFixtures.ADDRESS))
                .andExpect(jsonPath("$.signature").isNotEmpty())
                .andReturn()
                .getResponse()
                .getContentAsString();

        assertThat(objectMapper.readTree(body).get("payloadHash").asText())
                .matches("^0x[0-9a-f]{64}$");
        // The lost notification is visible on a counter rather than in the response.
        assertThat(meterRegistry.get("ledger.events.failed").counter().count()).isPositive();
        assertThat(meterRegistry.get("ledger.assets.registered").counter().count()).isPositive();
    }
}
