package com.pigfox.springboot;

import static org.assertj.core.api.Assertions.assertThat;

import com.pigfox.springboot.config.LedgerProperties;
import com.pigfox.springboot.crypto.SignatureService;
import java.util.concurrent.atomic.AtomicReference;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.boot.context.event.ApplicationReadyEvent;
import org.springframework.context.ApplicationListener;
import org.springframework.context.ConfigurableApplicationContext;

/**
 * Boots the application through its real entry point.
 *
 * <p>The rest of the suite starts contexts through {@code @SpringBootTest}, which never
 * calls {@code main}. Driving the entry point itself is what proves the annotations on it
 * are right — in particular that {@code @ConfigurationPropertiesScan} is present, without
 * which {@link LedgerProperties} is never bound and the node cannot start at all.
 *
 * <p>It has to start as a real web application, because the security chain is built from
 * {@code HttpSecurity} and that bean only exists in a servlet context. Both ports are
 * randomised so the test never collides with a running node.
 *
 * <p>{@code main} returns nothing, and a booted server left running for the rest of the
 * suite would be a leak, so the context is captured by {@link ContextCapture} — registered
 * in {@code src/test/resources/META-INF/spring.factories} and armed only by the marker
 * property below, so it cannot capture or close any other test's cached context.
 */
class ApplicationTest {

    private static final AtomicReference<ConfigurableApplicationContext> STARTED =
            new AtomicReference<>();

    /** Property that arms {@link ContextCapture}; no other context sets it. */
    private static final String CAPTURE_MARKER = "ledger.test.capture-context";

    /** Captures the context {@code main} does not hand back. Must be public for Boot to load it. */
    public static class ContextCapture implements ApplicationListener<ApplicationReadyEvent> {
        @Override
        public void onApplicationEvent(ApplicationReadyEvent event) {
            ConfigurableApplicationContext context = event.getApplicationContext();
            if (context.getEnvironment().getProperty(CAPTURE_MARKER, Boolean.class, false)) {
                STARTED.set(context);
            }
        }
    }

    @AfterEach
    void closeStartedContext() {
        ConfigurableApplicationContext context = STARTED.getAndSet(null);
        if (context != null) {
            context.close();
        }
    }

    @Test
    @DisplayName("the entry point boots a fully wired node from configuration alone")
    void mainStartsTheNode() {
        Application.main(new String[]{
                "--server.port=0",
                "--management.server.port=0",
                "--spring.kafka.listener.auto-startup=false",
                "--spring.kafka.admin.auto-create=false",
                "--management.tracing.enabled=false",
                "--ledger.chain.enabled=false",
                "--ledger.auth.client-id=" + TestFixtures.CLIENT_ID,
                "--ledger.auth.client-secret=" + TestFixtures.CLIENT_SECRET,
                "--ledger.auth.jwt-secret=" + TestFixtures.JWT_SECRET,
                "--ledger.crypto.signing-key=" + TestFixtures.PRIVATE_KEY,
                "--" + CAPTURE_MARKER + "=true",
        });

        ConfigurableApplicationContext context = STARTED.get();
        assertThat(context).as("main booted a context").isNotNull();
        assertThat(context.isRunning()).isTrue();
        // Proves @ConfigurationPropertiesScan bound the environment, and that the signing
        // key from configuration produced a usable key pair.
        assertThat(context.getBean(LedgerProperties.class).crypto().signingKey())
                .isEqualTo(TestFixtures.PRIVATE_KEY);
        assertThat(context.getBean(SignatureService.class).signerAddress())
                .isEqualTo(TestFixtures.ADDRESS);
    }
}
