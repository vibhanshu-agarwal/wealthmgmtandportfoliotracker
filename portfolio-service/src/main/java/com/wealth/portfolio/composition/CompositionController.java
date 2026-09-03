package com.wealth.portfolio.composition;

import static com.wealth.portfolio.PortfolioConstants.X_USER_ID_HEADER;

import com.wealth.portfolio.PortfolioResponse;
import jakarta.validation.Valid;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestHeader;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

/**
 * Public composition write boundary. Target portfolio is resolved from the gateway-injected
 * {@code X-User-Id}; no portfolio identifier is accepted on the wire.
 */
@RestController
@RequestMapping("/api/portfolio")
public class CompositionController {

    private final CompositionWriteService writeService;

    public CompositionController(CompositionWriteService writeService) {
        this.writeService = writeService;
    }

    @PutMapping("/holdings")
    public ResponseEntity<PortfolioResponse> replaceHoldings(
            @RequestHeader(X_USER_ID_HEADER) String userId,
            @Valid @RequestBody CompositionHoldingsRequest request) {
        CompositionWriteService.Outcome outcome = writeService.replace(userId, request);
        if (outcome.created()) {
            return ResponseEntity.status(HttpStatus.CREATED).body(outcome.response());
        }
        return ResponseEntity.ok(outcome.response());
    }
}
