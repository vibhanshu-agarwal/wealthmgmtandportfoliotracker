package com.wealth.insight;

import com.wealth.insight.advisor.AdvisorUnavailableException;
import org.junit.jupiter.api.Test;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;

import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;

class GlobalExceptionHandlerTest {

    private final GlobalExceptionHandler handler = new GlobalExceptionHandler();

    @Test
    void handleAdvisorUnavailable_returns503WithRetryable() {
        var ex = new AdvisorUnavailableException("LLM down");

        ResponseEntity<Map<String, Object>> response = handler.handleAdvisorUnavailable(ex);

        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.SERVICE_UNAVAILABLE);
        assertThat(response.getBody()).containsEntry("error", "AI advisor unavailable");
        assertThat(response.getBody()).containsEntry("retryable", true);
    }

    @Test
    void handlePortfolioNotFound_returns404() {
        var ex = new PortfolioNotFoundException("user-123");

        var response = handler.handlePortfolioNotFound(ex);

        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.NOT_FOUND);
        assertThat(response.getBody()).containsKey("error");
    }
}
