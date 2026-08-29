package com.wealth.portfolio.demo;

import com.wealth.portfolio.PortfolioResponse;
import jakarta.validation.Valid;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestMethod;
import org.springframework.web.bind.annotation.RestController;

/**
 * Internal demo-reset endpoint. Protected by {@link com.wealth.portfolio.seed.InternalApiKeyFilter};
 * no caller-controlled user identity is accepted.
 */
@RestController
@RequestMapping("/api/internal/portfolio")
public class DemoResetController {

    private final DemoResetService demoResetService;

    public DemoResetController(DemoResetService demoResetService) {
        this.demoResetService = demoResetService;
    }

    @RequestMapping(value = "/demo-reset", method = {RequestMethod.POST, RequestMethod.PUT})
    public ResponseEntity<PortfolioResponse> demoReset(@Valid @RequestBody DemoResetRequest request) {
        PortfolioResponse response = demoResetService.reset(request.expectedVersion());
        return ResponseEntity.ok(response);
    }
}
