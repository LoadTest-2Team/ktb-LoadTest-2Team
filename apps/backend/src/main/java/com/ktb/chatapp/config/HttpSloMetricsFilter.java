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
import java.time.Duration;
import java.util.concurrent.atomic.AtomicInteger;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.core.Ordered;
import org.springframework.core.annotation.Order;
import org.springframework.stereotype.Component;
import org.springframework.web.filter.OncePerRequestFilter;

/**
 * Exposes low-cardinality HTTP SLI metrics that can be consumed by both
 * Prometheus and the CloudWatch Agent Prometheus receiver.
 *
 * <p>The CloudWatch Agent does not support Prometheus histograms, so latency
 * percentiles are published as a client-side summary instead. Only successful
 * API responses (2xx and 3xx) contribute to the latency distribution.</p>
 */
@Component
@Order(Ordered.HIGHEST_PRECEDENCE + 1)
public class HttpSloMetricsFilter extends OncePerRequestFilter {

    static final String SUCCESS_LATENCY_METRIC = "http.server.success.latency";
    static final String CONCURRENT_REQUESTS_METRIC = "http.server.requests.concurrent";
    static final String ERROR_RESPONSES_METRIC = "http.server.responses";
    static final String SLO_EXCEEDED_METRIC = "http.server.success.slo.exceeded";

    private final MeterRegistry meterRegistry;
    private final Duration sloThreshold;
    private final Timer successLatency;
    private final Counter clientErrors;
    private final Counter serverErrors;
    private final Counter sloExceeded;
    private final AtomicInteger concurrentRequests = new AtomicInteger();

    @Autowired
    public HttpSloMetricsFilter(
            ObjectProvider<MeterRegistry> meterRegistryProvider,
            @Value("${management.metrics.http.slo:1s}") Duration sloThreshold) {
        this(meterRegistryProvider.getIfAvailable(), sloThreshold);
    }

    HttpSloMetricsFilter(MeterRegistry meterRegistry, Duration sloThreshold) {
        if (sloThreshold.isZero() || sloThreshold.isNegative()) {
            throw new IllegalArgumentException("management.metrics.http.slo must be positive");
        }

        this.meterRegistry = meterRegistry;
        this.sloThreshold = sloThreshold;
        if (meterRegistry == null) {
            this.successLatency = null;
            this.clientErrors = null;
            this.serverErrors = null;
            this.sloExceeded = null;
            return;
        }

        this.successLatency = Timer.builder(SUCCESS_LATENCY_METRIC)
                .description("Latency of successful HTTP API responses")
                .publishPercentiles(0.95, 0.99)
                .register(meterRegistry);
        this.clientErrors = responseCounter(meterRegistry, "4xx");
        this.serverErrors = responseCounter(meterRegistry, "5xx");
        this.sloExceeded = Counter.builder(SLO_EXCEEDED_METRIC)
                .description("Successful HTTP API responses slower than the configured SLO")
                .tag("threshold", formatThreshold(sloThreshold))
                .register(meterRegistry);

        Gauge.builder(CONCURRENT_REQUESTS_METRIC, concurrentRequests, AtomicInteger::get)
                .description("HTTP API requests currently being processed")
                .register(meterRegistry);
    }

    @Override
    protected void doFilterInternal(
            HttpServletRequest request,
            HttpServletResponse response,
            FilterChain filterChain) throws ServletException, IOException {
        Timer.Sample sample = Timer.start(meterRegistry);
        concurrentRequests.incrementAndGet();
        boolean failedBeforeResponse = false;

        try {
            filterChain.doFilter(request, response);
        } catch (IOException | ServletException | RuntimeException exception) {
            failedBeforeResponse = true;
            serverErrors.increment();
            throw exception;
        } finally {
            concurrentRequests.decrementAndGet();
            if (!failedBeforeResponse) {
                recordCompletedResponse(response.getStatus(), sample);
            }
        }
    }

    @Override
    protected boolean shouldNotFilter(HttpServletRequest request) {
        String uri = request.getRequestURI();
        return meterRegistry == null
                || !uri.startsWith("/api/")
                || uri.equals("/api/health")
                || uri.startsWith("/api/v3/api-docs")
                || uri.startsWith("/api/swagger-ui")
                || uri.startsWith("/api/docs/");
    }

    private void recordCompletedResponse(int status, Timer.Sample sample) {
        if (status >= 200 && status < 400) {
            long elapsedNanos = sample.stop(successLatency);
            if (elapsedNanos > sloThreshold.toNanos()) {
                sloExceeded.increment();
            }
        } else if (status >= 400 && status < 500) {
            clientErrors.increment();
        } else if (status >= 500 && status < 600) {
            serverErrors.increment();
        }
    }

    private static Counter responseCounter(MeterRegistry meterRegistry, String statusClass) {
        return Counter.builder(ERROR_RESPONSES_METRIC)
                .description("HTTP API error responses")
                .tag("status_class", statusClass)
                .register(meterRegistry);
    }

    private static String formatThreshold(Duration threshold) {
        long milliseconds = threshold.toMillis();
        return milliseconds % 1_000 == 0
                ? (milliseconds / 1_000) + "s"
                : milliseconds + "ms";
    }
}
