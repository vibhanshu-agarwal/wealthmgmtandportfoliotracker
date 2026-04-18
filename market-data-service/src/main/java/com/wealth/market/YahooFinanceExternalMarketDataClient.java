package com.wealth.market;

import io.github.resilience4j.retry.annotation.Retry;
import io.micrometer.core.instrument.MeterRegistry;
import io.micrometer.core.instrument.Timer;
import io.netty.channel.ChannelOption;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
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
        String symbols = tickers.stream().collect(Collectors.joining(","));
        Timer.Sample sample = Timer.start(meterRegistry);
        try {
            YahooQuoteResponse response = webClient.get()
                    .uri(uriBuilder -> uriBuilder
                            .path("/v7/finance/quote")
                            .queryParam("symbols", symbols)
                            .build())
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

    // Minimal DTOs to map Yahoo Finance JSON; shape is intentionally narrow.
    record YahooQuoteResponse(QuoteResponse quoteResponse) { }
    record QuoteResponse(List<YahooQuote> result) { }
    record YahooQuote(String symbol, BigDecimal regularMarketPrice) { }
}
