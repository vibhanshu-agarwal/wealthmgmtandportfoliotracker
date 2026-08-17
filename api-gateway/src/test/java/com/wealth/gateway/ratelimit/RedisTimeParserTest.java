package com.wealth.gateway.ratelimit;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import org.junit.jupiter.api.Test;

class RedisTimeParserTest {

    @Test
    void rejectsNonZeroExitCode() {
        assertThatThrownBy(() -> RedisTimeParser.parse(1, "123\n456"))
                .isInstanceOf(IllegalStateException.class)
                .hasMessage("redis-cli TIME exited 1");
    }

    @Test
    void rejectsEmptyStdoutOnZeroExit() {
        assertThatThrownBy(() -> RedisTimeParser.parse(0, ""))
                .isInstanceOf(IllegalStateException.class)
                .hasMessage("malformed redis-cli TIME output: ");
    }

    @Test
    void rejectsNonNumericFirstLine() {
        assertThatThrownBy(() -> RedisTimeParser.parse(0, "not-a-number\n456"))
                .isInstanceOf(IllegalStateException.class)
                .hasMessage("malformed redis-cli TIME output: not-a-number\n456");
    }

    @Test
    void acceptsNumericFirstLineWithTrailingWhitespace() {
        assertThat(RedisTimeParser.parse(0, "  12345  \n999")).isEqualTo("12345");
    }

    @Test
    void returnsTrimmedSecondsOnHappyPath() {
        assertThat(RedisTimeParser.parse(0, "1712345678\n123456")).isEqualTo("1712345678");
    }
}
