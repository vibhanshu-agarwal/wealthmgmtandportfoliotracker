<!-- 68f35382-8cb5-4040-ae4a-e22513dba2b5 -->
---
todos:
  - id: "optional-config-align"
    content: "(Optional) Unify Mongo URI property namespace across application.yml and application-prod.yml + env var docs if you want one canonical key."
    status: pending
isProject: false
---
# MongoDB connectivity in this project

## What the code actually does

MongoDB is only used in **`market-data-service`**. It already depends on the standard Spring Boot integration:

```15:15:market-data-service/build.gradle
    implementation 'org.springframework.boot:spring-boot-starter-data-mongodb'
```

That starter is the usual way to get **Spring Data MongoDB** (repositories, `MongoTemplate`, mapping) plus the **MongoDB Java driver** and **Spring Boot auto-configuration** for connection settings and beans.

Concrete usage:

| Mechanism | Where |
|-----------|--------|
| `MongoRepository` | [`AssetPriceRepository`](market-data-service/src/main/java/com/wealth/market/AssetPriceRepository.java) extends `MongoRepository<AssetPrice, String>` with a derived query `findByTickerIn`. |
| Document mapping | [`AssetPrice`](market-data-service/src/main/java/com/wealth/market/AssetPrice.java) uses `@Document(collection = "market_prices")` and `@Id`. |
| Application writes | [`MarketPriceService`](market-data-service/src/main/java/com/wealth/market/MarketPriceService.java) uses `assetPriceRepository.findById` / `save`. |
| Connectivity probe | [`InfrastructureHealthLogger`](market-data-service/src/main/java/com/wealth/market/InfrastructureHealthLogger.java) injects `MongoTemplate` and runs `executeCommand("{ ping: 1 }")`. |

Configuration:

- **Local / default**: [`application.yml`](market-data-service/src/main/resources/application.yml) sets `spring.mongodb.uri` (Spring Boot 4–style namespace) from `SPRING_MONGODB_URI` (see [`docker-compose.yml`](docker-compose.yml) `SPRING_MONGODB_URI: mongodb://mongodb:27017/market_db`).
- **Prod profile**: [`application-prod.yml`](market-data-service/src/main/resources/application-prod.yml) sets `spring.data.mongodb.uri` from `SPRING_DATA_MONGODB_URI` (Terraform/Lambda, per comment).

Other modules (`portfolio-service`, `insight-service`, `api-gateway`) do not reference MongoDB in Gradle or Java from the search performed.

## Clarifying “MongoDB plugin” vs “Spring Data MongoDB”

In Spring Boot there is no separate Gradle “plugin” for MongoDB in the sense of build tooling. People usually mean one of:

1. **`spring-boot-starter-data-mongodb`** — **already in use**; this *is* Spring Data MongoDB on the **blocking** stack.
2. **`spring-boot-starter-data-mongodb-reactive`** — same idea for **WebFlux/reactive** (`ReactiveMongoTemplate`, `ReactiveMongoRepository`); only relevant if you move the data layer to reactive APIs end-to-end.
3. **Driver-only / manual `MongoClient`** — not a different “plugin”; you would drop Spring Data and write BSON/collection code yourself.

So the premise “it does not use Spring Boot MongoDB plugin” does not match this repo: **`market-data-service` already uses Spring Boot’s MongoDB Data starter, i.e. Spring Data MongoDB.**

## Would switching help?

- **Switching to “Spring Data MongoDB”** — **No benefit**; you are already on it via the starter.
- **Switching to driver-only** — Possible **downsides** (more boilerplate, no repository/query derivation, you own connection lifecycle and mapping) unless you have a rare requirement (minimal classpath, non-Spring runtime, or you need APIs Spring Data does not expose).
- **Switching to reactive Mongo starter** — **Benefit** only if you **standardize on reactive** persistence and are willing to refactor services and tests; it is not a free upgrade for a mostly MVC + blocking repository codebase.
- **Optional housekeeping**: Align Mongo URI property keys across profiles (`spring.mongodb.uri` vs `spring.data.mongodb.uri`) so env vars and docs stay consistent; Boot 4 may still accept legacy keys, but one canonical key reduces confusion.

## Conclusion

**No meaningful gain from “switching to the MongoDB plugin or Spring Data MongoDB”** for this service—you already have the recommended blocking stack. Next steps would only be reactive migration, driver-only for special constraints, or small config consistency—not replacing the current approach with Spring Data.
