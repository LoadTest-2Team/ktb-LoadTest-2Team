package com.ktb.chatapp.config;

import io.micrometer.core.instrument.config.MeterFilter;
import java.util.Set;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

@Configuration
public class MetricsConfig {

    private static final Set<String> NON_TRAFFIC_URIS = Set.of(
            "/actuator/prometheus",
            "/api/health");

    /** Keep scrapes and health probes out of RPS, error-rate, and latency metrics. */
    @Bean
    MeterFilter excludeMonitoringRequestsFromHttpMetrics() {
        return MeterFilter.deny(id ->
                "http.server.requests".equals(id.getName())
                        && NON_TRAFFIC_URIS.contains(id.getTag("uri")));
    }
}
