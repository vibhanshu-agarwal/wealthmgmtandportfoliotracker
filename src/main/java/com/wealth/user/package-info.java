/**
 * User module — authentication and profile management.
 *
 * <p>Responsibilities:
 * <ul>
 *   <li>User registration and authentication</li>
 *   <li>Profile management</li>
 * </ul>
 *
 * <p>Allowed dependencies: none (no other com.wealth.* module may be imported directly).
 * <p>Inter-module communication: Spring Application Events only.
 */
@org.springframework.modulith.ApplicationModule(
        displayName = "User",
        allowedDependencies = {}
)
package com.wealth.user;
