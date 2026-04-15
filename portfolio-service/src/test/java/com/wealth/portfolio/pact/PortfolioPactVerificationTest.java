package com.wealth.portfolio.pact;

import au.com.dius.pact.provider.junit5.PactVerificationContext;
import au.com.dius.pact.provider.junitsupport.Provider;
import au.com.dius.pact.provider.junitsupport.State;
import au.com.dius.pact.provider.junitsupport.loader.PactFolder;
import au.com.dius.pact.provider.spring.spring7.Spring7MockMvcTestTarget;
import com.wealth.portfolio.GlobalExceptionHandler;
import com.wealth.portfolio.PortfolioController;
import com.wealth.portfolio.PortfolioResponse;
import com.wealth.portfolio.PortfolioService;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.TestTemplate;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.math.BigDecimal;
import java.time.Instant;
import java.util.List;
import java.util.UUID;

import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.when;

/**
 * Pact provider verification test for portfolio-service.
 *
 * <p>Replays consumer contracts from {@code frontend/pacts/} against the
 * {@link PortfolioController} using a standalone MockMvc setup — no full
 * application context required.
 */
@ExtendWith(MockitoExtension.class)
@Provider("portfolio-service")
@PactFolder("../frontend/pacts")
class PortfolioPactVerificationTest {

    @Mock
    private PortfolioService portfolioService;

    @InjectMocks
    private PortfolioController portfolioController;

    @BeforeEach
    void setUp(PactVerificationContext context) {
        Spring7MockMvcTestTarget testTarget = new Spring7MockMvcTestTarget();
        testTarget.setControllers(portfolioController);
        testTarget.setControllerAdvices(new GlobalExceptionHandler());
        context.setTarget(testTarget);
    }

    @TestTemplate
    @ExtendWith(au.com.dius.pact.provider.spring.spring7.PactVerificationSpring7Provider.class)
    void pactVerificationTestTemplate(PactVerificationContext context) {
        context.verifyInteraction();
    }

    @State("user has a portfolio with holdings")
    void userHasPortfolioWithHoldings() {
        var holding = new PortfolioResponse.HoldingResponse(
                UUID.randomUUID(),
                "AAPL",
                BigDecimal.TEN
        );
        var portfolio = new PortfolioResponse(
                UUID.randomUUID(),
                "user-001",
                Instant.parse("2026-04-15T10:30:00Z"),
                List.of(holding)
        );
        when(portfolioService.getByUserId(anyString())).thenReturn(List.of(portfolio));
    }
}
