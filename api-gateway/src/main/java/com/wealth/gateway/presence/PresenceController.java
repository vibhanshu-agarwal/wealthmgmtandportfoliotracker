package com.wealth.gateway.presence;

import org.springframework.security.oauth2.server.resource.authentication.JwtAuthenticationToken;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;
import reactor.core.publisher.Mono;

@RestController
@RequestMapping("/api/presence")
public class PresenceController {

    private final DemoPresenceService demoPresenceService;

    public PresenceController(DemoPresenceService demoPresenceService) {
        this.demoPresenceService = demoPresenceService;
    }

    @GetMapping("/demo")
    public Mono<DemoPresenceResponse> demo(JwtAuthenticationToken authentication) {
        return JwtSessionIdentity.fromPrincipal(authentication)
                .map(demoPresenceService::touchAndCheckAnother)
                .orElseGet(() -> Mono.just(false))
                .map(DemoPresenceResponse::new);
    }

    public record DemoPresenceResponse(boolean anotherSessionActive) {}
}
