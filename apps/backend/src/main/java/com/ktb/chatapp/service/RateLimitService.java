package com.ktb.chatapp.service;

import jakarta.annotation.PostConstruct;
import java.time.Duration;
import java.time.Instant;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.redisson.api.RAtomicLong;
import org.redisson.api.RedissonClient;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;

import static java.net.InetAddress.*;

@Slf4j
@Service
@RequiredArgsConstructor
public class RateLimitService {

    private static final String RATE_LIMIT_KEY_PREFIX = "ratelimit:";

    private final RedissonClient redissonClient;
    @Value("${HOSTNAME:''}")
    private String hostName;

    @PostConstruct
    public void init() {
        if (!hostName.isEmpty()) {
            return;
        }
        hostName = generateHostname();
    }

    private String generateHostname() {
        try {
            return getLocalHost().getHostName();
        } catch (Exception e) {
            return "unknown-" + java.util.UUID.randomUUID().toString().substring(0, 8);
        }
    }

    public RateLimitCheckResult checkRateLimit(String _clientId, int maxRequests, Duration window) {
        String actualClientId = hostName + ":" + _clientId;
        Duration effectiveWindow = window != null ? window : Duration.ofSeconds(1);
        long windowSeconds = Math.max(1L, effectiveWindow.getSeconds());

        try {
            RAtomicLong counter = redissonClient.getAtomicLong(RATE_LIMIT_KEY_PREFIX + actualClientId);
            long count = counter.incrementAndGet();
            if (count == 1L) {
                counter.expire(Duration.ofSeconds(windowSeconds));
            }

            long ttlMillis = counter.remainTimeToLive();
            long ttlSeconds = ttlMillis > 0 ? Math.max(1L, (ttlMillis + 999) / 1000) : windowSeconds;
            long resetEpochSeconds = Instant.now().getEpochSecond() + ttlSeconds;

            if (count > maxRequests) {
                return RateLimitCheckResult.rejected(maxRequests, windowSeconds, resetEpochSeconds, ttlSeconds);
            }

            int remaining = (int) Math.max(0L, maxRequests - count);
            return RateLimitCheckResult.allowed(
                    maxRequests, remaining, windowSeconds, resetEpochSeconds, ttlSeconds);
        } catch (Exception e) {
            log.error("Rate limit check failed for client: {}", actualClientId, e);
            long resetEpochSeconds = Instant.now().getEpochSecond() + windowSeconds;
            return RateLimitCheckResult.allowed(
                    maxRequests, maxRequests, windowSeconds, resetEpochSeconds, windowSeconds);
        }
    }

}
