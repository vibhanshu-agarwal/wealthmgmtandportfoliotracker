package com.wealth.portfolio;

import jakarta.persistence.*;
import java.time.Instant;
import java.util.*;
import org.hibernate.annotations.OptimisticLock;

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

    @Version
    @Column(nullable = false)
    private long version;

    @Column(nullable = false)
    private Instant updatedAt;

    @OneToMany(mappedBy = "portfolio", cascade = CascadeType.ALL, orphanRemoval = true)
    @OptimisticLock(excluded = true)
    private List<AssetHolding> holdings = new ArrayList<>();

    protected Portfolio() {}

    public Portfolio(String userId) {
        this.userId = userId;
    }

    @PrePersist
    private void prePersist() {
        Instant now = Instant.now();
        this.createdAt = now;
        this.updatedAt = now;
    }

    public UUID getId() { return id; }
    public String getUserId() { return userId; }
    public Instant getCreatedAt() { return createdAt; }
    public long getVersion() { return version; }
    public Instant getUpdatedAt() { return updatedAt; }

    // Returns the live mutable list — required for JPA dirty-checking and cascade hydration.
    public List<AssetHolding> getHoldings() { return holdings; }

    /** Associates a holding with this portfolio (cascade / orphanRemoval owner). */
    public void addHolding(AssetHolding h) {
        holdings.add(h);
        h.setPortfolio(this);
    }

    /** Replaces the entire holdings set in-memory; orphanRemoval deletes omitted rows on flush. */
    public void replaceAllHoldings(List<AssetHolding> next) {
        holdings.clear();
        for (AssetHolding h : next) {
            addHolding(h);
        }
    }
}
