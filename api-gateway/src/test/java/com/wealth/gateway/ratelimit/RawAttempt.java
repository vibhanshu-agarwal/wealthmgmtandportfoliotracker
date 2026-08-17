package com.wealth.gateway.ratelimit;

import java.util.List;
import org.springframework.test.web.reactive.server.EntityExchangeResult;

public record RawAttempt(
        List<EntityExchangeResult<String>> burstResponses,
        EntityExchangeResult<String> firstExcessResponse,
        long downstreamDelta) {}
