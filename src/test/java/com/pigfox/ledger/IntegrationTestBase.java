package com.pigfox.ledger;

import com.pigfox.ledger.kafka.AssetEventPublisher;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.mock.mockito.MockBean;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.springframework.test.context.TestPropertySource;
import org.springframework.test.web.servlet.MockMvc;

/**
 * Boots the whole application, security chain included, with no external dependency.
 *
 * <p>Three settings keep it hermetic. Listener auto-startup and admin topic creation are
 * off, so nothing reaches for a broker; tracing export is off, so nothing reaches for an
 * OTLP collector; and {@code ledger.chain.enabled=false} means the anchor service takes
 * its disabled path instead of dialling a node. The event publisher is the one collaborator
 * replaced by a mock — it owns the only call that would actually open a broker connection,
 * and it has its own unit test.
 *
 * <p>The signing key is registered dynamically because it is derived at runtime rather
 * than written down anywhere.
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
})
public abstract class IntegrationTestBase {

    @Autowired
    protected MockMvc mockMvc;

    @MockBean
    protected AssetEventPublisher assetEventPublisher;

    @DynamicPropertySource
    static void signingKey(DynamicPropertyRegistry registry) {
        registry.add("ledger.crypto.signing-key", () -> TestFixtures.PRIVATE_KEY);
    }
}
