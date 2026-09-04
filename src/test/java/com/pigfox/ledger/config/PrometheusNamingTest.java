package com.pigfox.ledger.config;

import static org.assertj.core.api.Assertions.assertThat;

import io.micrometer.prometheusmetrics.PrometheusConfig;
import io.micrometer.prometheusmetrics.PrometheusMeterRegistry;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

/**
 * Pins the names that actually reach Prometheus, not the names the code asks for.
 *
 * <p>The two differ. A counter is exposed with a {@code _total} suffix, dots become
 * underscores, and — the trap this test exists for — the Prometheus client treats a
 * trailing {@code created} as its own reserved suffix and strips it. A counter named
 * {@code ledger.assets.created} is therefore scraped as {@code ledger_assets_total}, which
 * says nothing about what was counted. A dashboard or alert built on a scraped name breaks
 * silently when that name changes, so the exposed form belongs in a test.
 */
class PrometheusNamingTest {

    private final PrometheusMeterRegistry registry =
            new PrometheusMeterRegistry(PrometheusConfig.DEFAULT);

    private String scrape() {
        return registry.scrape();
    }

    @Test
    @DisplayName("the registration counter is scraped under a name that says what it counts")
    void exposesRegistrationCounter() {
        registry.counter("ledger.assets.registered").increment();

        assertThat(scrape()).contains("ledger_assets_registered_total");
    }

    @Test
    @DisplayName("naming a counter *.created would lose the word Prometheus reserves")
    void demonstratesTheCreatedSuffixTrap() {
        registry.counter("ledger.assets.created").increment();

        // This is why the counter is not called that.
        assertThat(scrape())
                .contains("ledger_assets_total")
                .doesNotContain("ledger_assets_created_total");
    }

    @Test
    @DisplayName("the anchor failure and event counters survive translation intact")
    void exposesRemainingCounters() {
        registry.counter("ledger.assets.anchor.failures").increment();
        registry.counter("ledger.events.published", "topic", "asset.events").increment();
        registry.counter("ledger.events.failed", "topic", "asset.events").increment();
        registry.counter("ledger.events.consumed").increment();
        registry.counter("ledger.events.duplicates").increment();

        assertThat(scrape())
                .contains("ledger_assets_anchor_failures_total")
                .contains("ledger_events_published_total")
                .contains("ledger_events_failed_total")
                .contains("ledger_events_consumed_total")
                .contains("ledger_events_duplicates_total")
                .contains("topic=\"asset.events\"");
    }
}
