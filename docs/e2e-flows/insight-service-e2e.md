# Insight Service End-to-End (E2E) Flow

This document describes the flow of data and control for the `insight-service` in the Wealth Management and Portfolio Tracker application, starting from the frontend.

## 1. Frontend Layer (Next.js)
The flow begins in the **AI Insights Page** (`frontend/src/app/(dashboard)/ai-insights/page.tsx`), which serves as an entry point for users to view market summaries and interact with an AI-powered chat.

*   **`MarketSummaryGrid`**: A client-side component that uses a TanStack Query hook (`useMarketSummary`) to fetch a map of all tracked tickers.
*   **`ChatInterface`**: A conversational UI component that allows users to ask about specific tickers or their portfolio, using Next.js Server Actions or direct API calls.

## 2. API Call & Routing
*   **Local Proxy**: The frontend makes requests to `/api/insights/market-summary` or `/api/chat`.
*   **Next.js Rewrite**: `next.config.ts` rewrites these calls to the **API Gateway** at `http://127.0.0.1:8080` (local) or to `https://vibhanshu-ai-portfolio.dev` (production CloudFront origin).
*   **API Gateway**: The Spring Cloud Gateway (`api-gateway/src/main/resources/application.yml`) routes requests based on the path:
    *   `/api/insights/**` → `http://localhost:8083` (local) / `INSIGHT_SERVICE_URL` Lambda Function URL (production)
    *   `/api/chat/**` → same target as `/api/insights/**`

## 3. Insight Service Controllers
The `insight-service` exposes REST controllers to handle incoming requests:
*   **`InsightController`**: Handles `/api/insights/market-summary`. It orchestrates the retrieval of market data and enriches it with AI-generated sentiment.
*   **`ChatController`**: Handles `/api/chat`. It extracts ticker symbols from natural language messages, fetches current prices, and generates a conversational response using AI.

## 4. Data Layer & Real-time Integration (Redis & Kafka)
The `insight-service` maintains its own view of market data for low-latency access:
*   **Redis Storage**: `MarketDataService` manages `market:latest:{ticker}` (current price) and `market:history:{ticker}` (last 10 prices) in Redis.
*   **Kafka Listener**: The `InsightEventListener` listens to the `market-prices` Kafka topic. When a `PriceUpdatedEvent` is received, it updates the Redis cache.

## 5. AI Enrichment (AiInsightService)
The `AiInsightService` provides sentiment analysis and is implemented via two profile-scoped adapters:
*   **`MockAiInsightService`**: Default implementation (`@Profile("!bedrock")`) — active for local development and CI. Zero-latency deterministic responses.
*   **`BedrockAiInsightService`**: AWS Bedrock (Claude Haiku 4.5) integration (`@Profile("bedrock")`) — active on Lambda (`SPRING_PROFILES_ACTIVE=prod,aws,bedrock`) or for opt-in local smoke-testing (`local,bedrock`).

## 6. Portfolio Analysis (Downstream REST Call)
For portfolio-level analysis (e.g., `/api/insights/{userId}/analyze`):
*   **`InsightService`**: Fetches the user's holdings from the **`portfolio-service`** via a REST call to `/api/portfolio`.
*   **`InsightAdvisor`**: Processes the portfolio data to generate risk and diversification advice.

## Summary Flow Diagram
```mermaid
graph TD
    A[Frontend: AI Insights Page] -->|/api/insights/*| B[Next.js Rewrite]
    B -->|Port 8080| C[API Gateway]
    C -->|Port 8083| D[Insight Service]
    
    subgraph "Insight Service"
        D1[InsightController / ChatController]
        D2[MarketDataService]
        D3[AiInsightService]
        D4[InsightEventListener]
        
        D1 --> D2
        D1 --> D3
        D4 -->|Updates| D2
    end
    
    D2 <-->|Cache| E[(Redis)]
    D4 <-->|Listen| F[[Kafka: market-prices]]
    D1 -.->|REST| G[Portfolio Service]
```

## 7. Production Deployment Topology (AWS / Terraform)
The `insight-service` is packaged as a container image (ECR) and deployed as an **AWS Lambda function on arm64 / Graviton2** via the **Lambda Web Adapter** sidecar. Provisioned by `infrastructure/terraform/modules/compute`:

- **Lambda alias `live`** is published per deploy; the **Function URL** (`AuthType = NONE`) attaches to the `live` alias rather than `$LATEST`.
- **Origin protection**: the Function URL is protected by the `X-Origin-Verify` header injected by CloudFront on the api-gateway hop. Direct invocation without the header returns 403.
- **External managed dependencies** (no in-VPC AWS resources): **Aiven Kafka** (`SPRING_KAFKA_BOOTSTRAP_SERVERS`, mTLS via the canonical `truststore.jks` shipped from `common-dto`/`TruststoreExtractor`) and **Upstash Redis** (`rediss://`, TLS).
- **AI Profile**: production runs with `SPRING_PROFILES_ACTIVE=prod,aws,bedrock`, activating `BedrockAiInsightService` against the Claude Haiku 4.5 model. Local development defaults to the deterministic `MockAiInsightService` (`@Profile("!bedrock")`).
- **Cold-start mitigation**: when `enable_warming = true`, the Terraform `warming` module uses EventBridge Rules + API Destinations (`rate(5 minutes)`) to GET `/actuator/health` on the Function URL; a CloudWatch alarm on `ConcurrentExecutions ≥ 8` notifies SNS. Optional escalation: provisioned concurrency on the `live` alias via `enable_provisioned_concurrency`.
- **Concurrency**: `reserved_concurrent_executions` is intentionally **omitted** (ap-south-1 account cap is 10 unreserved executions; reserving any would block other functions).
