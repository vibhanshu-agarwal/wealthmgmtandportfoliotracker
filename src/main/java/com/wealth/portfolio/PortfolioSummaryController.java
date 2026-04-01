package com.wealth.portfolio;

import com.wealth.portfolio.dto.PortfolioSummaryDto;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

@RestController
@RequestMapping("/api/portfolio")
public class PortfolioSummaryController {

    private final PortfolioService portfolioService;

    public PortfolioSummaryController(PortfolioService portfolioService) {
        this.portfolioService = portfolioService;
    }

    @GetMapping("/summary")
    public ResponseEntity<PortfolioSummaryDto> getSummary(
            @RequestParam(defaultValue = "user-001") String userId
    ) {
        return ResponseEntity.ok(portfolioService.getSummary(userId));
    }
}
