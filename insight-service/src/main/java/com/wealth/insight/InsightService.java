package com.wealth.insight;

import com.wealth.insight.advisor.AdvisorUnavailableException;
import com.wealth.insight.advisor.AnalysisResult;
import com.wealth.insight.advisor.InsightAdvisor;
import com.wealth.insight.dto.PortfolioDto;
import com.wealth.insight.dto.PortfolioHoldingDto;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.core.ParameterizedTypeReference;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import org.springframework.web.client.HttpClientErrorException;
import org.springframework.web.client.RestClient;

import java.util.List;

/**
 * Orchestrates portfolio analysis by fetching portfolio data from portfolio-service
 * via REST and delegating to the active {@link InsightAdvisor} implementation.
 */
@Service
public class InsightService {

    private static final Logger log = LoggerFactory.getLogger(InsightService.class);

    private final RestClient restClient;
    private final InsightAdvisor insightAdvisor;

    public InsightService(
            RestClient.Builder restClientBuilder,
            @Value("${insight.portfolio-service.base-url}") String portfolioServiceBaseUrl,
            InsightAdvisor insightAdvisor) {
        this.restClient = restClientBuilder.baseUrl(portfolioServiceBaseUrl).build();
        this.insightAdvisor = insightAdvisor;
    }

    /**
     * Fetches the user's portfolio from portfolio-service and runs AI analysis.
     *
     * @param userId the user whose portfolio to analyze
     * @return analysis result
     * @throws PortfolioNotFoundException if portfolio-service returns 404
     * @throws AdvisorUnavailableException if the AI advisor fails
     */
    public AnalysisResult analyzePortfolio(String userId) {
        PortfolioDto portfolio = fetchPortfolio(userId);
        return insightAdvisor.analyze(portfolio);
    }

    private PortfolioDto fetchPortfolio(String userId) {
        try {
            List<PortfolioDto> portfolios = restClient.get()
                    .uri("/api/portfolio")
                    .header("X-User-Id", userId)
                    .retrieve()
                    .body(new ParameterizedTypeReference<>() {});

            if (portfolios == null || portfolios.isEmpty()) {
                throw new PortfolioNotFoundException(userId);
            }
            return portfolios.getFirst();
        } catch (HttpClientErrorException e) {
            if (e.getStatusCode() == HttpStatus.NOT_FOUND) {
                throw new PortfolioNotFoundException(userId);
            }
            log.error("Failed to fetch portfolio for user {}: {}", userId, e.getMessage());
            throw new AdvisorUnavailableException("Portfolio service unavailable", e);
        } catch (PortfolioNotFoundException e) {
            throw e;
        } catch (Exception e) {
            log.error("Failed to fetch portfolio for user {}: {}", userId, e.getMessage());
            throw new AdvisorUnavailableException("Portfolio service unavailable", e);
        }
    }
}
