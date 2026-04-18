package com.wealth.insight;

import org.junit.jupiter.api.RepeatedTest;

import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.ThreadLocalRandom;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Property-based tests for ticker extraction logic.
 *
 * <p>Property 7: Known tickers found in arbitrary text.
 * <p>Property 8: First valid ticker wins when multiple present.
 * <p>Property 9: Stop-word-only messages return null.
 */
class TickerExtractionPropertyTest {

    /**
     * Property 7: For any known ticker (1-5 uppercase, not a stop word)
     * placed as the first non-stop-word token in a message,
     * extractTicker returns that ticker.
     */
    @RepeatedTest(100)
    void knownTicker_asFirstNonStopWord_isExtracted() {
        ThreadLocalRandom rng = ThreadLocalRandom.current();
        String ticker = randomNonStopWordTicker(rng);
        // Build message: stop words + ticker + more stop words
        String message = randomStopWords(rng) + " " + ticker + " " + randomStopWords(rng);

        String result = ChatController.extractTicker(message);

        assertThat(result).isEqualTo(ticker);
    }

    /**
     * Property 8: For any message with 2+ valid tickers,
     * extractTicker returns the first one.
     */
    @RepeatedTest(100)
    void multipleTickers_returnsFirst() {
        ThreadLocalRandom rng = ThreadLocalRandom.current();
        String first = randomNonStopWordTicker(rng);
        String second = randomNonStopWordTicker(rng);
        // Ensure they're different
        while (second.equals(first)) {
            second = randomNonStopWordTicker(rng);
        }

        // Place first ticker before second, with stop words around them
        String message = randomStopWords(rng) + " " + first + " " + randomStopWords(rng) + " " + second;

        String result = ChatController.extractTicker(message);

        assertThat(result).isEqualTo(first);
    }

    /**
     * Property 9: For any message composed entirely of stop-list words,
     * extractTicker returns null.
     */
    @RepeatedTest(100)
    void stopWordsOnly_returnsNull() {
        ThreadLocalRandom rng = ThreadLocalRandom.current();
        String message = randomStopWords(rng);
        // Ensure message is not empty
        if (message.isBlank()) {
            message = "HOW IS THE";
        }

        String result = ChatController.extractTicker(message);

        assertThat(result).isNull();
    }

    // --- Helpers ---

    private static final List<String> STOP_WORD_LIST = List.copyOf(ChatController.STOP_WORDS);

    private static String randomNonStopWordTicker(ThreadLocalRandom rng) {
        // Generate a random 2-5 letter uppercase string that is NOT a stop word
        while (true) {
            int len = rng.nextInt(2, 6);
            StringBuilder sb = new StringBuilder(len);
            for (int i = 0; i < len; i++) {
                sb.append((char) ('A' + rng.nextInt(26)));
            }
            String candidate = sb.toString();
            if (!ChatController.STOP_WORDS.contains(candidate)) {
                return candidate;
            }
        }
    }

    private static String randomStopWords(ThreadLocalRandom rng) {
        int count = rng.nextInt(0, 5);
        List<String> words = new ArrayList<>();
        for (int i = 0; i < count; i++) {
            words.add(STOP_WORD_LIST.get(rng.nextInt(STOP_WORD_LIST.size())).toLowerCase());
        }
        return String.join(" ", words);
    }
}
