/**
 * Insight module — AI-driven personalized portfolio recommendations.
 *
 * <p>Responsibilities:
 * <ul>
 *   <li>Analyse portfolio composition against market trends</li>
 *   <li>Generate personalised investment recommendations</li>
 * </ul>
 *
 * <p>Allowed dependencies: none (no other com.wealth.* module may be imported directly).
 * <p>Inter-module communication: consumes portfolio-context Application Events only.
 */
@org.springframework.modulith.ApplicationModule(
        displayName = "Insight",
        allowedDependencies = {}
)
package com.wealth.insight;
