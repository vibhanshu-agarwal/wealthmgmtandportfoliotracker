package com.wealth.gateway;

import org.springframework.stereotype.Component;

@Component
public final class ReplicaTokenProvider {

    private final String replicaToken;

    public ReplicaTokenProvider() {
        this(System.getenv("CONTAINER_APP_REPLICA_NAME"));
    }

    ReplicaTokenProvider(String rawName) {
        this.replicaToken = ReplicaTokenFormula.compute(rawName);
    }

    String replicaToken() {
        return replicaToken;
    }
}
