package com.ktb.chatapp.service;

import java.time.Duration;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.redisson.api.RAtomicLong;
import org.redisson.api.RedissonClient;
import org.springframework.test.util.ReflectionTestUtils;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.lenient;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
@DisplayName("RateLimitService 단위 테스트")
class RateLimitServiceUnitTest {

    private static final String HOST_NAME = "test-host";
    private static final String CLIENT_ID = "client-1";
    private static final String STORE_CLIENT_ID = HOST_NAME + ":" + CLIENT_ID;
    private static final String COUNTER_KEY = "ratelimit:" + STORE_CLIENT_ID;

    @Mock
    private RedissonClient redissonClient;

    @Mock
    private RAtomicLong counter;

    private RateLimitService rateLimitService;

    @BeforeEach
    void setUp() {
        rateLimitService = new RateLimitService(redissonClient);
        ReflectionTestUtils.setField(rateLimitService, "hostName", HOST_NAME);
        lenient().when(redissonClient.getAtomicLong(COUNTER_KEY)).thenReturn(counter);
    }

    @Test
    @DisplayName("최초 요청은 카운터를 1로 증가시키고 TTL을 설정한 뒤 남은 횟수를 반환한다")
    void checkRateLimit_FirstRequest_IncrementsAndSetsExpire() {
        when(counter.incrementAndGet()).thenReturn(1L);
        when(counter.remainTimeToLive()).thenReturn(30_000L);

        RateLimitCheckResult result = rateLimitService.checkRateLimit(CLIENT_ID, 3, Duration.ofSeconds(30));

        assertThat(result.allowed()).isTrue();
        assertThat(result.limit()).isEqualTo(3);
        assertThat(result.remaining()).isEqualTo(2);
        assertThat(result.windowSeconds()).isEqualTo(30);
        assertThat(result.retryAfterSeconds()).isBetween(1L, 30L);
        verify(counter).expire(Duration.ofSeconds(30));
    }

    @Test
    @DisplayName("기존 카운트가 한도 미만이면 증가된 값을 기준으로 남은 횟수를 계산하고 TTL을 다시 걸지 않는다")
    void checkRateLimit_ExistingBelowLimit_IncrementsCount() {
        when(counter.incrementAndGet()).thenReturn(2L);
        when(counter.remainTimeToLive()).thenReturn(20_000L);

        RateLimitCheckResult result = rateLimitService.checkRateLimit(CLIENT_ID, 3, Duration.ofSeconds(30));

        assertThat(result.allowed()).isTrue();
        assertThat(result.remaining()).isEqualTo(1);
        verify(counter, never()).expire(any(Duration.class));
    }

    @Test
    @DisplayName("한도를 초과하면 차단하고 retry-after/reset epoch을 반환한다")
    void checkRateLimit_LimitReached_ReturnsRetryAfterRejected() {
        when(counter.incrementAndGet()).thenReturn(4L);
        when(counter.remainTimeToLive()).thenReturn(10_000L);

        RateLimitCheckResult result = rateLimitService.checkRateLimit(CLIENT_ID, 3, Duration.ofSeconds(30));

        assertThat(result.allowed()).isFalse();
        assertThat(result.limit()).isEqualTo(3);
        assertThat(result.remaining()).isZero();
        assertThat(result.retryAfterSeconds()).isBetween(1L, 10L);
    }

    @Test
    @DisplayName("0초 window는 최소 1초 window로 정규화된다")
    void checkRateLimit_ZeroWindow_NormalizesToOneSecond() {
        when(counter.incrementAndGet()).thenReturn(1L);
        when(counter.remainTimeToLive()).thenReturn(1000L);

        RateLimitCheckResult result = rateLimitService.checkRateLimit(CLIENT_ID, 3, Duration.ZERO);

        assertThat(result.allowed()).isTrue();
        assertThat(result.windowSeconds()).isEqualTo(1);
        assertThat(result.retryAfterSeconds()).isPositive();
        verify(counter).expire(Duration.ofSeconds(1));
    }

    @Test
    @DisplayName("null window는 최소 1초 window로 정규화된다")
    void checkRateLimit_NullWindow_NormalizesToOneSecond() {
        when(counter.incrementAndGet()).thenReturn(1L);
        when(counter.remainTimeToLive()).thenReturn(1000L);

        RateLimitCheckResult result = rateLimitService.checkRateLimit(CLIENT_ID, 3, null);

        assertThat(result.allowed()).isTrue();
        assertThat(result.windowSeconds()).isEqualTo(1);
        assertThat(result.retryAfterSeconds()).isPositive();
    }

    @Test
    @DisplayName("TTL이 없으면(만료 직후 등) window 값으로 대체한다")
    void checkRateLimit_NoTtl_FallsBackToWindow() {
        when(counter.incrementAndGet()).thenReturn(1L);
        when(counter.remainTimeToLive()).thenReturn(-1L);

        RateLimitCheckResult result = rateLimitService.checkRateLimit(CLIENT_ID, 3, Duration.ofSeconds(30));

        assertThat(result.allowed()).isTrue();
        assertThat(result.retryAfterSeconds()).isEqualTo(30L);
    }

    @Test
    @DisplayName("Redis 실패 시 요청은 허용하고 전체 한도를 남긴다 (fail-open)")
    void checkRateLimit_RedisFailure_FailsOpenDeterministically() {
        doThrow(new IllegalStateException("redis down")).when(redissonClient).getAtomicLong(anyString());

        RateLimitCheckResult result = rateLimitService.checkRateLimit(CLIENT_ID, 3, Duration.ofSeconds(30));

        assertThat(result.allowed()).isTrue();
        assertThat(result.limit()).isEqualTo(3);
        assertThat(result.remaining()).isEqualTo(3);
        assertThat(result.windowSeconds()).isEqualTo(30);
        assertThat(result.retryAfterSeconds()).isEqualTo(30);
    }

    @Test
    @DisplayName("null clientId도 host prefix가 적용된 카운터 키로 처리된다")
    void checkRateLimit_NullClientId_UsesHostPrefixedNullKey() {
        String key = "ratelimit:" + HOST_NAME + ":null";
        RAtomicLong nullKeyCounter = org.mockito.Mockito.mock(RAtomicLong.class);
        when(redissonClient.getAtomicLong(key)).thenReturn(nullKeyCounter);
        when(nullKeyCounter.incrementAndGet()).thenReturn(1L);
        when(nullKeyCounter.remainTimeToLive()).thenReturn(30_000L);

        RateLimitCheckResult result = rateLimitService.checkRateLimit(null, 3, Duration.ofSeconds(30));

        assertThat(result.allowed()).isTrue();
        verify(redissonClient).getAtomicLong(key);
    }
}
