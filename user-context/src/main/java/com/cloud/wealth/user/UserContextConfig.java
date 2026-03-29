package com.cloud.wealth.user;

import org.springframework.context.annotation.Configuration;

/**
 * Spring configuration entry point for the User bounded context.
 *
 * Bounded Context responsibilities:
 *   - Authentication and authorization
 *   - User profile management
 *   - Billing tier management
 *
 * Schema: user_schema (logical PostgreSQL schema, isolated from other contexts)
 *
 * Inter-context communication: publishes/consumes Spring Application Events only.
 * Direct dependencies on other context packages are FORBIDDEN.
 */
@Configuration
public class UserContextConfig {
}
