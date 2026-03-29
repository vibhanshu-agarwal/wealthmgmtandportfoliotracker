package com.cloud.wealth.insight;

import org.springframework.context.annotation.Configuration;

/**
 * Spring configuration entry point for the Insight bounded context.
 *
 * Bounded Context responsibilities:
 *   - Generate AI-driven personalized investment recommendations
 *   - Analyse portfolio composition against current market trends
 *
 * Inter-context communication: consumes portfolio-context Application Events only.
 * Direct dependencies on other context packages are FORBIDDEN.
 */
@Configuration
public class InsightContextConfig {
}
