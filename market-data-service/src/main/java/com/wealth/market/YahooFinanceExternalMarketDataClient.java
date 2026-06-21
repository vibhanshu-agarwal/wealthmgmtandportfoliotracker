package com.wealth.market;

import io.github.resilience4j.retry.annotation.Retry;
import io.micrometer.core.instrument.MeterRegistry;
import io.micrometer.core.instrument.Timer;
import io.netty.channel.ChannelOption;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.http.HttpHeaders;
import org.springframework.http.client.reactive.ReactorClientHttpConnector;
import org.springframework.stereotype.Component;
import org.springframework.web.reactive.function.client.WebClient;
import org.springframework.web.reactive.function.client.WebClientResponseException;
import reactor.core.publisher.Mono;
import reactor.netty.http.client.HttpClient;

import java.math.BigDecimal;
import java.time.Duration;
import java.util.*;
import java.util.stream.Collectors;

@Component
class YahooFinanceExternalMarketDataClient implements ExternalMarketDataClient {

    private static final Logger log = LoggerFactory.getLogger(YahooFinanceExternalMarketDataClient.class);

    private final WebClient webClient;
    private final ExternalMarketDataProperties props;
    private final MeterRegistry meterRegistry;

    // ── Cached Yahoo session (cookie + crumb) ─────────────────────────────────
    // Yahoo's /v7/finance/quote endpoint requires a session cookie plus a matching
    // "crumb" token, otherwise it returns 401 Unauthorized. The handshake is performed
    // lazily on first use and cached; it is refreshed once if a request later 401s
    // (the crumb can expire). Guarded by sessionLock so concurrent batches share one
    // handshake rather than racing.
    private final Object sessionLock = new Object();
    private volatile String cookie;
    private volatile String crumb;

    YahooFinanceExternalMarketDataClient(ExternalMarketDataProperties props, MeterRegistry meterRegistry) {
        this.props = props;
        this.meterRegistry = meterRegistry;
        int timeoutMs = Math.max(1, props.getTimeoutMs());
        Duration timeout = Duration.ofMillis(timeoutMs);
        HttpClient httpClient = HttpClient.create()
                .responseTimeout(timeout)
                .option(ChannelOption.CONNECT_TIMEOUT_MILLIS, Math.min(timeoutMs, Integer.MAX_VALUE));
        this.webClient = WebClient.builder()
                .baseUrl(props.getBaseUrl())
                // A browser-like User-Agent is mandatory: Yahoo rejects the default Java/Netty
                // UA on the quote and crumb endpoints. Applied to every request (cookie, crumb, quote).
                .defaultHeader(HttpHeaders.USER_AGENT, props.getUserAgent())
                .clientConnector(new ReactorClientHttpConnector(httpClient))
                .build();
    }

    @Override
    @Retry(name = "externalMarketData")
    public Map<String, BigDecimal> getLatestPrices(Collection<String> tickers) {
        if (tickers == null || tickers.isEmpty()) {
            return Collections.emptyMap();
        }

        int batchSize = Math.max(1, props.getBatchSize());
        List<String> tickerList = new ArrayList<>(new LinkedHashSet<>(tickers));
        Map<String, BigDecimal> result = new HashMap<>();

        for (int i = 0; i < tickerList.size(); i += batchSize) {
            List<String> batch = tickerList.subList(i, Math.min(i + batchSize, tickerList.size()));
            result.putAll(fetchBatch(batch));
        }

        return result;
    }

    private Map<String, BigDecimal> fetchBatch(List<String> tickers) {
        String symbols = String.join(",", tickers);
        ensureSession();
        Timer.Sample sample = Timer.start(meterRegistry);
        try {
            try {
                return executeQuote(symbols);
            } catch (WebClientResponseException.Unauthorized unauthorized) {
                // Crumb/cookie likely expired (or this was the first call after a cold start).
                // Refresh the session once and retry — Yahoo returns 401 for a stale crumb.
                log.warn("Yahoo Finance API returned 401 for symbols={}; refreshing crumb/cookie and retrying once.",
                        symbols);
                meterRegistry.counter("market.data.provider.requests",
                        "provider", props.getProvider(), "outcome", "unauthorized_retry").increment();
                invalidateSession();
                ensureSession();
                return executeQuote(symbols);
            }
        } catch (WebClientResponseException e) {
            meterRegistry.counter("market.data.provider.requests",
                    "provider", props.getProvider(), "outcome", "http_error").increment();
            log.warn("Yahoo Finance API failed for symbols={} with status={} message={}. " +
                            "Yahoo Finance API failed, falling back to cached database prices.",
                    symbols, e.getStatusCode(), e.getMessage());
            throw e;
        } catch (Exception e) {
            meterRegistry.counter("market.data.provider.requests",
                    "provider", props.getProvider(), "outcome", "error").increment();
            log.warn("Yahoo Finance API failed for symbols={} with exception={}. " +
                            "Yahoo Finance API failed, falling back to cached database prices.",
                    symbols, e.toString());
            throw e;
        } finally {
            sample.stop(Timer.builder("market.data.provider.quote.batch")
                    .description("HTTP round-trip for one Yahoo quote batch")
                    .tag("provider", props.getProvider())
                    .register(meterRegistry));
        }
    }

    /** Performs the actual quote request (cookie + crumb applied). Exceptions propagate to the caller. */
    private Map<String, BigDecimal> executeQuote(String symbols) {
        String sessionCookie = this.cookie;
        String sessionCrumb = this.crumb;

        YahooQuoteResponse response = webClient.get()
                .uri(uriBuilder -> {
                    uriBuilder.path("/v7/finance/quote").queryParam("symbols", symbols);
                    if (sessionCrumb != null && !sessionCrumb.isBlank()) {
                        uriBuilder.queryParam("crumb", sessionCrumb);
                    }
                    return uriBuilder.build();
                })
                .headers(headers -> {
                    if (sessionCookie != null && !sessionCookie.isBlank()) {
                        headers.add(HttpHeaders.COOKIE, sessionCookie);
                    }
                })
                .retrieve()
                .onStatus(status -> status.is5xxServerError(),
                        clientResponse -> clientResponse.createException().flatMap(error -> {
                            log.warn("Yahoo Finance API failed with 5xx for symbols={}. " +
                                            "Yahoo Finance API failed, falling back to cached database prices.",
                                    symbols);
                            return Mono.error(error);
                        }))
                .onStatus(status -> status.value() == 429,
                        clientResponse -> clientResponse.createException().flatMap(error -> {
                            log.warn("Yahoo Finance API rate limited (429) for symbols={}. " +
                                            "Yahoo Finance API failed, falling back to cached database prices.",
                                    symbols);
                            return Mono.error(error);
                        }))
                .bodyToMono(YahooQuoteResponse.class)
                .block();

        if (response == null || response.quoteResponse() == null || response.quoteResponse().result() == null) {
            meterRegistry.counter("market.data.provider.requests",
                    "provider", props.getProvider(), "outcome", "empty_body").increment();
            return Collections.emptyMap();
        }

        Map<String, BigDecimal> prices = new HashMap<>();
        for (YahooQuote quote : response.quoteResponse().result()) {
            if (quote.symbol() != null && quote.regularMarketPrice() != null) {
                prices.put(quote.symbol(), quote.regularMarketPrice());
            }
        }
        meterRegistry.counter("market.data.provider.requests",
                "provider", props.getProvider(), "outcome", "success").increment();
        return prices;
    }

    // ── Session handshake (cookie + crumb) ─────────────────────────────────────

    /**
     * Ensures a cookie + crumb are cached. Best-effort: if the handshake fails the request still
     * proceeds without a crumb (and is retried once on the resulting 401), so a transient Yahoo
     * hiccup degrades to the existing cached-price fallback rather than throwing here.
     */
    private void ensureSession() {
        if (cookie != null && crumb != null) {
            return;
        }
        synchronized (sessionLock) {
            if (cookie != null && crumb != null) {
                return;
            }
            try {
                String freshCookie = fetchCookie();
                String freshCrumb = fetchCrumb(freshCookie);
                if (freshCookie != null && !freshCookie.isBlank()
                        && freshCrumb != null && !freshCrumb.isBlank()) {
                    this.cookie = freshCookie;
                    this.crumb = freshCrumb;
                    meterRegistry.counter("market.data.provider.session",
                            "provider", props.getProvider(), "outcome", "established").increment();
                    log.info("Yahoo Finance session established (crumb acquired).");
                } else {
                    meterRegistry.counter("market.data.provider.session",
                            "provider", props.getProvider(), "outcome", "incomplete").increment();
                    log.warn("Yahoo Finance session handshake incomplete (cookie present={}, crumb present={}); "
                                    + "proceeding without crumb.",
                            freshCookie != null && !freshCookie.isBlank(),
                            freshCrumb != null && !freshCrumb.isBlank());
                }
            } catch (Exception e) {
                meterRegistry.counter("market.data.provider.session",
                        "provider", props.getProvider(), "outcome", "error").increment();
                log.warn("Yahoo Finance session handshake failed; proceeding without crumb. cause={}", e.toString());
            }
        }
    }

    private void invalidateSession() {
        this.cookie = null;
        this.crumb = null;
    }

    /** GET the cookie URL and concatenate the returned Set-Cookie pairs into a Cookie header value. */
    private String fetchCookie() {
        return webClient.get()
                .uri(props.getCookieUrl())
                // fc.yahoo.com commonly answers 404 while still setting the cookie, so read the
                // response regardless of status rather than letting retrieve() throw.
                .exchangeToMono(clientResponse -> {
                    String cookieHeader = clientResponse.cookies().values().stream()
                            .flatMap(List::stream)
                            .map(c -> c.getName() + "=" + c.getValue())
                            .collect(Collectors.joining("; "));
                    return Mono.just(cookieHeader);
                })
                .block();
    }

    /** GET the crumb endpoint with the session cookie; the response body is the crumb token. */
    private String fetchCrumb(String cookieHeader) {
        if (cookieHeader == null || cookieHeader.isBlank()) {
            return null;
        }
        return webClient.get()
                .uri(uriBuilder -> uriBuilder.path(props.getCrumbPath()).build())
                .header(HttpHeaders.COOKIE, cookieHeader)
                .retrieve()
                .bodyToMono(String.class)
                .block();
    }

    // Minimal DTOs to map Yahoo Finance JSON; shape is intentionally narrow.
    record YahooQuoteResponse(QuoteResponse quoteResponse) { }
    record QuoteResponse(List<YahooQuote> result) { }
    record YahooQuote(String symbol, BigDecimal regularMarketPrice) { }
}
