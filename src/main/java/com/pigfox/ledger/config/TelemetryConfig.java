package com.pigfox.ledger.config;

import io.micrometer.core.aop.TimedAspect;
import io.micrometer.core.instrument.Tag;
import io.micrometer.core.instrument.config.MeterFilter;
import io.micrometer.core.instrument.MeterRegistry;
import java.util.List;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.kafka.annotation.EnableKafka;

/**
 * Telemetry wiring beyond what auto-configuration provides.
 *
 * <p>Actuator exposes health and a Prometheus scrape endpoint, and the OTLP bridge ships
 * spans; both are configured in {@code application.yml}. What is added here is the
 * {@link TimedAspect} that makes {@code @Timed} work on the service layer, and a common
 * tag naming the component so a metric can be attributed when several services report to
 * one Prometheus.
 */
@Configuration
@EnableKafka
public class TelemetryConfig {

    /**
     * @param meterRegistry registry supplied by Boot's metrics auto-configuration
     * @return aspect backing {@code @Timed} on Spring beans
     */
    @Bean
    public TimedAspect timedAspect(MeterRegistry meterRegistry) {
        return new TimedAspect(meterRegistry);
    }

    /** @return filter stamping every meter with the emitting component */
    @Bean
    public MeterFilter ledgerNodeTag() {
        return MeterFilter.commonTags(List.of(Tag.of("component", "ledger-node")));
    }
}
