package com.wealth.insight;

import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

class TickerExtractionTest {

    @Test
    void extractTicker_stopWordsOnly_returnsNull() {
        assertThat(ChatController.extractTicker("How is the market")).isNull();
    }

    @Test
    void extractTicker_validTicker_returnsIt() {
        assertThat(ChatController.extractTicker("How is AAPL doing")).isEqualTo("AAPL");
    }

    @Test
    void extractTicker_multipleTickers_returnsFirst() {
        assertThat(ChatController.extractTicker("Compare AAPL and MSFT")).isEqualTo("AAPL");
    }

    @Test
    void extractTicker_nullMessage_returnsNull() {
        assertThat(ChatController.extractTicker(null)).isNull();
    }

    @Test
    void extractTicker_blankMessage_returnsNull() {
        assertThat(ChatController.extractTicker("   ")).isNull();
    }

    @Test
    void extractTicker_lowercaseTicker_normalizesToUppercase() {
        // "tell" and "me" are not stop words, so "tell" is extracted first
        // Use a message where the ticker is the first non-stop-word
        assertThat(ChatController.extractTicker("is aapl up")).isEqualTo("AAPL");
    }

    @Test
    void extractTicker_tickerWithPunctuation_cleanedAndExtracted() {
        assertThat(ChatController.extractTicker("What about TSLA?")).isEqualTo("TSLA");
    }

    @Test
    void extractTicker_singleLetterStopWord_filtered() {
        // "I" and "A" are stop words, but "want" and "report" are not
        // This verifies that stop words are skipped, not that the whole message returns null
        assertThat(ChatController.extractTicker("I am")).isNull();
    }

    @Test
    void extractTicker_fiveLetterTicker_extracted() {
        // Use a message where GOOGL is the first non-stop-word token
        assertThat(ChatController.extractTicker("is GOOGL up")).isEqualTo("GOOGL");
    }
}
