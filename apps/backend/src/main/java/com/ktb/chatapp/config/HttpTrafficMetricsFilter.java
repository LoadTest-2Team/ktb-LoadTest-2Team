package com.ktb.chatapp.config;

import io.micrometer.core.instrument.Counter;
import io.micrometer.core.instrument.Gauge;
import io.micrometer.core.instrument.MeterRegistry;
import io.micrometer.core.instrument.Timer;
import jakarta.servlet.FilterChain;
import jakarta.servlet.ServletException;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import java.io.IOException;
import java.math.BigDecimal;
import java.time.Duration;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.core.Ordered;
import org.springframework.core.annotation.Order;
import org.springframework.stereotype.Component;
import org.springframework.web.filter.OncePerRequestFilter;

/**
 * Low-cardinality traffic metrics that can be exported safely as CloudWatch
 * custom metrics. URI, user, and request identifiers are deliberately absent.
 */
@Component
@Order(Ordered.HIGHEST_PRECEDENCE + 10)
public class HttpTrafficMetricsFilter extends OncePerRequestFilter {

    private final AtomicInteger concurrentRequests = new AtomicInteger();
    private final Timer successLatency;
    private final Map<Duration, Counter> sloExceededCounters;

    public HttpTrafficMetricsFilter(
            MeterRegistry meterRegistry,
            @Value("${app.metrics.http.slo-thresholds:1s,1.5s}") Duration[] sloThresholds) {
        Gauge.builder("http.server.requests.concurrent", concurrentRequests, AtomicInteger::get)
                .description("Current HTTP requests being processed")
                .register(meterRegistry);

        successLatency = Timer.builder("http.server.success.latency")
                .description("Latency of completed 2xx HTTP requests")
                .register(meterRegistry);

        sloExceededCounters = new LinkedHashMap<>();
        for (Duration threshold : sloThresholds) {
            Counter counter = Counter.builder("http.server.success.slo.exceeded")
                    .description("Successful HTTP requests slower than the configured SLO threshold")
                    .tag("threshold", formatThreshold(threshold))
                    .register(meterRegistry);
            sloExceededCounters.put(threshold, counter);
        }
    }

    @Override
    protected boolean shouldNotFilter(HttpServletRequest request) {
        String path = request.getRequestURI();
        return path.startsWith("/actuator/") || "/api/health".equals(path);
    }

    @Override
    protected void doFilterInternal(
            HttpServletRequest request,
            HttpServletResponse response,
            FilterChain filterChain) throws ServletException, IOException {
        concurrentRequests.incrementAndGet();
        long startedAt = System.nanoTime();
        boolean completed = false;

        try {
            filterChain.doFilter(request, response);
            completed = true;
        } finally {
            concurrentRequests.decrementAndGet();
            if (completed && response.getStatus() >= 200 && response.getStatus() < 300) {
                long elapsedNanos = System.nanoTime() - startedAt;
                successLatency.record(elapsedNanos, TimeUnit.NANOSECONDS);
                sloExceededCounters.forEach((threshold, counter) -> {
                    if (elapsedNanos > threshold.toNanos()) {
                        counter.increment();
                    }
                });
            }
        }
    }

    private static String formatThreshold(Duration threshold) {
        if (threshold.compareTo(Duration.ofSeconds(1)) >= 0) {
            return BigDecimal.valueOf(threshold.toNanos(), 9)
                    .stripTrailingZeros()
                    .toPlainString() + "s";
        }
        return threshold.toMillis() + "ms";
    }
}
