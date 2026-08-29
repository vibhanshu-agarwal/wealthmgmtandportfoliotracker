package com.wealth.gateway.presence;

import org.springframework.security.oauth2.server.resource.authentication.JwtAuthenticationToken;

import java.security.Principal;
import java.util.Optional;

/**
 * Validated JWT subject plus optional session identifier. A missing or blank {@code jti} is the
 * explicit legacy no-presence path — not an authentication failure.
 */
public record JwtSessionIdentity(String sub, Optional<String> jti) {

    public static Optional<JwtSessionIdentity> fromPrincipal(Principal principal) {
        if (!(principal instanceof JwtAuthenticationToken jwtToken)) {
            return Optional.empty();
        }
        String sub = jwtToken.getToken().getClaimAsString("sub");
        if (sub == null || sub.isBlank()) {
            return Optional.empty();
        }
        String rawJti = jwtToken.getToken().getClaimAsString("jti");
        Optional<String> jti = (rawJti == null || rawJti.isBlank())
                ? Optional.empty()
                : Optional.of(rawJti);
        return Optional.of(new JwtSessionIdentity(sub, jti));
    }

    public boolean isDemoAccount() {
        return DemoPresenceService.DEMO_USER_ID.equals(sub);
    }

    /** Demo account with a usable session identifier — the only path that touches Redis. */
    public boolean hasPresenceSession() {
        return isDemoAccount() && jti.isPresent();
    }
}
