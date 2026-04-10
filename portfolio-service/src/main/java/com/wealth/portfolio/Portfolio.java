package com.wealth.portfolio;

import jakarta.persistence.*;
import java.time.Instant;
import java.util.*;

@Entity
@Table(name = "portfolios")
public class Portfolio {

    @Id
    @GeneratedValue(strategy = GenerationType.UUID)
    private UUID id;

    /**
     * References a User by its ID as a plain String.
     * <p>
     * A {@code @ManyToOne} association to {@code com.wealth.user.User} is PROHIBITED —
     * cross-module JPA relationships violate the Modulith boundary mandate.
     */
    @Column(nullable = false)
    private String userId;

    @Column(nullable = false, updatable = false)
    private Instant createdAt;

    @OneToMany(mappedBy = "portfolio", cascade = CascadeType.ALL, orphanRemoval = true)
    private List<AssetHolding> holdings = new ArrayList<>();

    protected Portfolio() {}

    public Portfolio(String userId) {
        this.userId = userId;
    }

    @PrePersist
    private void prePersist() {
        this.createdAt = Instant.now();
    }

    public UUID getId() { return id; }
    public String getUserId() { return userId; }
    public Instant getCreatedAt() { return createdAt; }
    public List<AssetHolding> getHoldings() { return Collections.unmodifiableList(holdings); }


}
