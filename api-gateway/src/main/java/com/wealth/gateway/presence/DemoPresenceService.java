package com.wealth.gateway.presence;

import org.springframework.beans.factory.ObjectProvider;
import org.springframework.data.domain.Range;
import org.springframework.data.redis.core.ReactiveStringRedisTemplate;
import org.springframework.stereotype.Service;
import reactor.core.publisher.Mono;
import reactor.core.scheduler.Schedulers;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.util.HexFormat;

@Service
public class DemoPresenceService {

    /** Compiled demo UUID from {@code V15__Reconcile_Auth_Seed_Users.sql}. */
    public static final String DEMO_USER_ID = "00000000-0000-0000-0000-0000000d3110";

    public static final String PRESENCE_KEY = "presence:demo";
    static final Duration KEY_EXPIRY_SLACK = Duration.ofSeconds(30);
    static final Duration BACKGROUND_TOUCH_TIMEOUT = Duration.ofMillis(250);

    private final ReactiveStringRedisTemplate redisTemplate;
    private final DemoPresenceProperties properties;
    private final Clock clock;

    public DemoPresenceService(
            ReactiveStringRedisTemplate redisTemplate,
            DemoPresenceProperties properties,
            ObjectProvider<Clock> clockProvider) {
        this.redisTemplate = redisTemplate;
        this.properties = properties;
        this.clock = clockProvider.getIfAvailable(Clock::systemUTC);
    }

    /**
     * Lower-case SHA-256 hex digest of the UTF-8 session identifier. Never logs or returns the raw
     * input.
     */
    public static String hashSessionKey(String jti) {
        try {
            byte[] digest = MessageDigest.getInstance("SHA-256")
                    .digest(jti.getBytes(StandardCharsets.UTF_8));
            return HexFormat.of().formatHex(digest);
        } catch (NoSuchAlgorithmException e) {
            throw new IllegalStateException("SHA-256 unavailable", e);
        }
    }

    /** Best-effort touch for routed demo traffic; no-op for non-demo and legacy tokens. */
    public Mono<Void> touch(JwtSessionIdentity identity) {
        if (!identity.hasPresenceSession()) {
            return Mono.empty();
        }
        return touchRedis(hashSessionKey(identity.jti().orElseThrow()))
                .onErrorComplete();
    }

    /**
     * Dispatches a presence touch off the request critical path. Slow or hung Redis work must not
     * delay routed traffic; failures and timeouts fail open.
     */
    public void scheduleBackgroundTouch(JwtSessionIdentity identity) {
        if (!identity.hasPresenceSession()) {
            return;
        }
        touch(identity)
                .timeout(BACKGROUND_TOUCH_TIMEOUT)
                .onErrorComplete()
                .subscribeOn(Schedulers.boundedElastic())
                .subscribe();
    }

    /**
     * Records the caller, sweeps stale members, and returns whether another active session exists.
     * Redis failures fail open to {@code false}.
     */
    public Mono<Boolean> touchAndCheckAnother(JwtSessionIdentity identity) {
        if (!identity.hasPresenceSession()) {
            return Mono.just(false);
        }
        String member = hashSessionKey(identity.jti().orElseThrow());
        long now = epochSeconds(clock.instant());
        long ttlSeconds = properties.ttlSeconds();
        long staleMaxScore = now - ttlSeconds - 1;

        return touchRedis(member)
                .then(redisTemplate.opsForZSet().removeRangeByScore(
                        PRESENCE_KEY, Range.closed(0d, (double) staleMaxScore)))
                .then(redisTemplate.opsForZSet().size(PRESENCE_KEY))
                .flatMap(count -> redisTemplate.opsForZSet()
                        .score(PRESENCE_KEY, member)
                        .map(score -> count - 1)
                        .defaultIfEmpty(count))
                .map(others -> others > 0)
                .onErrorReturn(false);
    }

    private Mono<Void> touchRedis(String member) {
        long now = epochSeconds(clock.instant());
        Duration keyExpiry = properties.ttl().plus(KEY_EXPIRY_SLACK);
        return redisTemplate.opsForZSet()
                .add(PRESENCE_KEY, member, now)
                .then(redisTemplate.expire(PRESENCE_KEY, keyExpiry))
                .then();
    }

    static long epochSeconds(Instant instant) {
        return instant.getEpochSecond();
    }
}
