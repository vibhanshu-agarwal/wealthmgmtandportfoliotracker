package com.wealth.gateway;

import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicReference;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.context.annotation.Profile;
import org.springframework.data.redis.connection.ReactiveRedisConnectionFactory;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

/**
 * Recurring degraded-state monitor for the rate-limiter's Redis backend.
 *
 * <p>{@link InfrastructureHealthLogger} already performs a one-shot startup probe and logs
 * {@code [INFRA-OK]}/{@code [INFRA-FAIL]} on {@code ApplicationReadyEvent}. That alone does not
 * satisfy Req 4.4 ("regardless of whether the API_Gateway is actively serving traffic"), because a
 * request-driven check would never fire on an idle gateway. This component fills that gap with a
 * scheduled probe:
 *
 * <ul>
 *   <li>On failure — logs a WARN describing the degraded rate-limiting state, throttled to at
 *       most once per 60 seconds via a monotonic-clock gate (Req 4.4).
 *   <li>On a down→up transition — logs a single recovery INFO (Req 4.5).
 * </ul>
 *
 * <p>Runs under {@code aws} and {@code azure} profiles, matching {@link InfrastructureHealthLogger}
 * — local Docker Compose Redis is assumed healthy and this recurring probe is unnecessary there.
 */
@Component
@Profile({"aws", "azure"})
public class RedisRateLimitStateLogger {

  private static final Logger log = LoggerFactory.getLogger(RedisRateLimitStateLogger.class);
  private static final Duration PROBE_TIMEOUT = Duration.ofSeconds(2);
  private static final Duration WARN_THROTTLE_WINDOW = Duration.ofSeconds(60);

  private final ReactiveRedisConnectionFactory redisConnectionFactory;
  private final Clock clock;

  /** Tracks whether the previous probe found Redis up, to detect a down→up transition. */
  private final AtomicBoolean previouslyUp = new AtomicBoolean(true);

  /** Monotonic-clock gate: the WARN log fires only if this is null or older than the window. */
  private final AtomicReference<Instant> lastWarnAt = new AtomicReference<>();

  @Autowired
  RedisRateLimitStateLogger(ReactiveRedisConnectionFactory redisConnectionFactory) {
    this(redisConnectionFactory, Clock.systemUTC());
  }

  /** Package-private constructor for tests to inject a fixed/mutable {@link Clock}. */
  RedisRateLimitStateLogger(ReactiveRedisConnectionFactory redisConnectionFactory, Clock clock) {
    this.redisConnectionFactory = redisConnectionFactory;
    this.clock = clock;
  }

  /**
   * Probes Redis roughly every 30 seconds. The fixed rate is independent of request traffic, so
   * the degraded-state WARN and the recovery INFO fire even while the gateway is idle.
   */
  @Scheduled(fixedRate = 30_000)
  public void probe() {
    boolean up = pingSucceeded();
    boolean wasUp = previouslyUp.getAndSet(up);

    if (!up) {
      maybeLogWarn();
    } else if (!wasUp) {
      log.info("[INFRA-OK]   Redis — recovered; rate-limiter backend ready");
    }
  }

  private boolean pingSucceeded() {
    try {
      redisConnectionFactory.getReactiveConnection().ping().timeout(PROBE_TIMEOUT).block(PROBE_TIMEOUT);
      return true;
    } catch (Exception ex) {
      return false;
    }
  }

  private void maybeLogWarn() {
    Instant now = clock.instant();
    Instant previous = lastWarnAt.get();
    if (previous == null || Duration.between(previous, now).compareTo(WARN_THROTTLE_WINDOW) >= 0) {
      if (lastWarnAt.compareAndSet(previous, now)) {
        log.warn(
            "[INFRA-DEGRADED] Redis — unreachable; rate limiting is degraded (fail-open, "
                + "requests are proxied without enforcement). Check REDIS_URL / Upstash status.");
      }
    }
  }
}
