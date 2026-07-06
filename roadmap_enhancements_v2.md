# Wealth Management & Portfolio Tracker
## High-Level Architecture Design & Backlog Refinement (v2)

This document outlines the architectural specifications and refined backlog items for the Wealth Management and Portfolio Tracker project. Designed as a **Modular Monolith** built on Spring Boot, this blueprint prepares the system for eventual cloud-native deployment while keeping current development and operational footprints highly optimized.

---

## 1. Core Architectural Strategy

### Domain Boundaries
To maintain a strict separation of concerns within the modular monolith, the system is split into two primary bounded contexts:

1. **Global Market Data Domain:** Responsible for tracking, validating, and updating standard pricing data for a static universe of **~160 core assets** sourced via Yahoo Finance tickers. 
2. **User Portfolio Domain:** Responsible for managing user identities, multiple portfolios, user-defined asset compositions, transactional history, and dynamic analytics calculations.

### Ingestion & Scaling Efficiency
* **Static Asset Pool:** By constraining the system to ~160 assets, external data fetching remains deterministic. Background cron jobs update this static master list without dynamically scaling scraper threads or introducing unbounded external API rate limits.
* **Decoupled Architecture:** User portfolio composition choices do not trigger external network calls. The portfolio context reads strictly from the internally cached global market data table.

---

## 2. Refined Backlog Items (Planning Slate for Agents)

### Item #3: Observability & Azure Application Insights Integration
* **Priority:** Medium / High
* **Status:** Approved for Implementation
* **Objective:** Configure Spring Boot telemetry, performance metrics, and dependency tracking using Azure Application Insights.
* **Cost Risk Assessment:** Low risk. Azure Monitor includes a **5 GB/month free tier** for data ingestion and 31 days of free retention, which far exceeds the profiling requirements of a single-developer/prototype environment.
* **Cost Mitigation Constraint (Mandatory):** The development agent or infrastructure script must enforce a **Daily Cap of 0.1 GB/day** inside the Azure Log Analytics / Application Insights workspace configuration. This serves as a hard circuit breaker against accidental logging loops or verbose trace spikes, guaranteeing a $0 operational footprint.
* **Agent Task Definition:** Integrate the Application Insights Java agent or starter dependency, configure standard distributed tracing, and ensure logging levels (INFO/WARN) are tightly scoped.

### Item #5: User-Defined Portfolio Composition & Asset Picker
* **Priority:** High
* **Status:** Approved for Implementation
* **Objective:** Implement a production-ready user onboarding and asset composition flow utilizing a searchable multi-select asset picker.
* **Core Product Rules:**
  * **No Automated Seeding:** Real users **must not** be automatically seeded with mock asset profiles. Automated portfolio seeding is strictly reserved for the specialized demo account state.
  * **Dynamic Composition:** Real users must be allowed to select an arbitrary number of assets (e.g., 5, 10, or 20 items) from the 160 globally tracked assets.
  * **Scoped Analytics:** The backend analytics engine must calculate performance metrics, asset allocation percentages, and daily profit/loss (PnL) *only* for the specific subset of assets owned by that individual user.
* **Implementation Effort:** **Low**. The static universe of 160 items is small enough to be loaded fully into client-side memory upon onboarding, eliminating complex pagination or type-ahead search throttling queries.
* **Agent Task Definition:** Create a cached backend endpoint `GET /api/assets` to expose the tracked list, design the portfolio persistence endpoint, and write the service logic to compute portfolio allocation metrics dynamically in-memory.

---

## 3. Data Architecture & Relational Mapping

Agents must implement the database schema using the following relational structure to cleanly separate the Global Space from the User Space:

```text
                  +------------------------+
                  |     Asset (Global)     |
                  +------------------------+
                  | id (PK) [UUID/Long]    |
                  | ticker (e.g., AAPL)    |
                  | name (Company Name)    |
                  | current_price          |
                  | last_updated           |
                  +------------------------+
                              | 1
                              |
                              | *
                  +------------------------+
                  | Portfolio_Item (User)  |
                  +------------------------+
                  | id (PK)                |
                  | portfolio_id (FK)      | <--- Many-to-Many Join
                  | asset_id (FK)          |
                  | shares_owned           |
                  | avg_buy_price          |
                  +------------------------+
                              | *
                              |
                              | 1
                  +------------------------+
                  |    Portfolio (User)    |
                  +------------------------+
                  | id (PK)                |
                  | user_id (FK)           |
                  | name (e.g., "Growth")  |
                  +------------------------+
```

---

## 4. API & Integration Contracts

### Asset Retrieval Endpoint
**Endpoint:** `GET /api/v1/assets`
* **Description:** Used by the UI during onboarding/asset picker rendering to fetch the entire supported asset universe. Highly cacheable.
* **Response Payload Example:**
```json
[
  {
    "id": 101,
    "ticker": "AAPL",
    "name": "Apple Inc.",
    "currentPrice": 175.45
  },
  {
    "id": 102,
    "ticker": "RELIANCE.NS",
    "name": "Reliance Industries Limited",
    "currentPrice": 2450.10
  }
]
```

### Portfolio Creation/Update Endpoint
**Endpoint:** `POST /api/v1/portfolios`
* **Description:** Initializes or updates a production user's custom-sliced portfolio structure.
* **Request Payload Example (User selecting an arbitrary subset):**
```json
{
  "userId": "usr_99824a",
  "portfolioName": "Core Equities",
  "items": [
    { "assetId": 101, "sharesOwned": 15.0, "avgBuyPrice": 165.00 },
    { "assetId": 102, "sharesOwned": 50.0, "avgBuyPrice": 2400.00 }
  ]
}
```

---

## 5. Calculations for Scoped Analytics

The service layer must perform analytics dynamically in-memory using the following formulas computed across the user's specific asset slice. (Note: These are expressed in standard text format to ensure clean system rendering and script parsing).

* **Total Portfolio Value:**
  Sum of (shares_owned * current_price) for all items in the portfolio slice.

* **Asset Allocation Percentage:**
  Allocation % = ((shares_owned * current_price) / Total Portfolio Value) * 100

* **Unrealized Gain/Loss (PnL):**
  PnL = (current_price - avg_buy_price) * shares_owned
