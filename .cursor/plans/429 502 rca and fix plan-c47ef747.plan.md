<!-- c47ef747-8372-42b9-93ce-71fc86c1daf9 -->
---
todos:
  - id: "quota-request"
    content: "File AWS Support / Service Quotas requests: (1) raise ap-south-1 Lambda unreserved concurrency from 10 to >=100; (2) check Bedrock Claude 3 Haiku RPM/TPM quotas for us-east-1 and request increase if below 60 RPM (one per baseline ticker). Document both as pending in changelog."
    status: pending
  - id: "insight-fanout-cap"
    content: "Refactor InsightController.getMarketSummary() to not call Bedrock per-ticker on the list endpoint (return aiSummary=null), or cap to N tickers with per-call 2s timeout and parallel futures; map timeouts to 503 via AdvisorUnavailableException."
    status: pending
  - id: "insight-redis-scan"
    content: "Replace redisTemplate.keys() in MarketDataService.getMarketSummary() with a ZSET (market:tracked-tickers) scored by epoch-millis in processUpdate(). Enumerate with ZRANGEBYSCORE; prune stale tickers with ZREMRANGEBYSCORE (entries older than 24 h) on each processUpdate() write to prevent unbounded growth."
    status: pending
  - id: "frontend-dedup"
    content: "Collapse duplicate fetchPortfolio calls across usePortfolio/usePortfolioPerformance/useAssetAllocation in frontend/src/lib/hooks/usePortfolio.ts so the /api/portfolio and /api/market/prices backends are hit once per mount."
    status: pending
  - id: "frontend-retry-policy"
    content: "In usePortfolio.ts / useInsights.ts set retry to skip 429/503 and cap other retries at 1; add exponential retryDelay so bursts don't compound concurrency."
    status: pending
  - id: "gateway-timeouts"
    content: "Add spring.cloud.gateway.server.webflux.httpclient.connect-timeout 5s and response-timeout 20s to api-gateway/src/main/resources/application-prod.yml (surface 504 fast on slow downstreams)."
    status: pending
  - id: "memory-bump"
    content: "Raise market_data_memory_mb and insight_service_memory_mb from 1024 to 2048 in infrastructure/terraform/locals.tf to cut cold-start init time (CPU scales with memory)."
    status: pending
  - id: "provisioned-concurrency"
    content: "Add aws_lambda_provisioned_concurrency_config = 1 on the live alias for wealth-api-gateway and wealth-portfolio-service in modules/compute/main.tf (gated on a var.enable_provisioned_concurrency boolean; default true after the quota increase lands)."
    status: pending
  - id: "warmer-fallback"
    content: "Optional fallback if quota increase is delayed: add an EventBridge rate(5 minutes) rule invoking /actuator/health/liveness on each of the four Function URLs via an HTTPS connection (no provisioned concurrency needed)."
    status: pending
  - id: "market-prices-bound"
    content: "In MarketPriceController.getPrices require or cap the tickers param (e.g. 25 max) to keep Function URL responses well under the 6 MB limit and prevent future 502s from size truncation."
    status: pending
  - id: "lwa-retries"
    content: "Add AWS_LWA_READINESS_CHECK_MAX_RETRIES=20 to local.common_env and api_gateway_container_env in modules/compute/main.tf so LWA waits up to 20s for Spring Boot cold-start without crashing the extension (Extension.Crash prevention for JVM AOT init)."
    status: pending
  - id: "cloudfront-origin-timeout"
    content: "Add origin_read_timeout = 25 to the ordered_cache_behavior (api-gateway-lambda origin) in modules/cdn/main.tf so CloudFront drops 5s before its 30s hard limit and surface-502s are avoided when the gateway response-timeout (20s) fires first."
    status: pending
  - id: "bedrock-quota"
    content: "Check Bedrock Claude 3 Haiku RPM/TPM quotas in us-east-1 (AWS Console → Bedrock → Model access → Quota tab). Request increase if below 60 RPM alongside the Lambda concurrency quota request."
    status: pending
  - id: "cloudwatch-rca"
    content: "Run the CloudWatch Insights queries in section 2 to quantify Throttles per function and cold-start durations before/after; attach results to the changelog."
    status: pending
  - id: "changelog"
    content: "Append a section 14 to docs/changes/CHANGES_PHASE3_INFRA_SUMMARY_19042026.md documenting the 429/502 RCA, the hypotheses confirmed/discarded, and the implemented fixes with commit hashes."
    status: pending
isProject: false
---

# Dashboard 429 and 502 — RCA and Remediation

## 1. Summary of hypotheses

### 429 Too Many Requests — primary hypothesis
AWS account-level **unreserved concurrency quota is 10** (`modules/compute/main.tf` explicitly notes this), and the dashboard fans out **~7 simultaneous browser requests** on `/overview` mount, each of which may trigger cold starts on up to 4 backend Lambdas. When account concurrency is exhausted, Lambda returns `429 TooManyRequestsException` (translated to HTTP 429 by the Function URL).

Amplifiers present in the current code:

- `frontend/src/lib/hooks/usePortfolio.ts` — three independent hooks (`usePortfolio`, `usePortfolioPerformance`, `useAssetAllocation`) each call `fetchPortfolio(userId, token)` in `frontend/src/lib/api/portfolio.ts`. Each of those internally hits BOTH `/api/portfolio` and `/api/market/prices`. TanStack Query deduplicates by `queryKey`, but the three keys are distinct → **3× duplicate fan-out** to portfolio-service and market-data-service.
- `usePortfolioSummary` sets `retry: 3, retryDelay: 1000` — any transient 429/502 is retried 3×, compounding the concurrency burst.
- `fetchWithAuth.ts` now redirects to `/login` on 401, but no backoff/de-dup on 429.

### 502 Bad Gateway — primary hypotheses
Ordered by likelihood:

1. **Cold-start of downstream Lambda via Function URL exceeds the effective wait window.** With `lambda_timeout = 60s` and 4 services cold-starting concurrently (contending for account concurrency), end-to-end latency easily crosses the browser's or CloudFront's origin-read patience. If the Lambda process dies mid-init (Extension.Crash / OOM), the Function URL returns 502.
2. **Unbounded Bedrock fan-out in `InsightController.getMarketSummary()`** (`insight-service/.../InsightController.java:48`) — for every key in `market:latest:*` it issues a **sequential** Bedrock Claude Haiku call. With 56 baseline tickers (`market-data-service/application.yml`) and a cold sentiment cache, this is ~56×(500 ms – 2 s) = 30 – 100 s → exceeds the 60 s Lambda timeout → Lambda kills the handler → 502.
3. **Slow `KEYS market:latest:*` against Upstash Redis.** `MarketDataService.getMarketSummary()` uses `redisTemplate.keys(...)`; Upstash implements `KEYS` via a full scan — for large keyspaces this is slow, and on the free tier it is rate-limited itself.
4. **Spring Cloud Gateway has no explicit `response-timeout`**, so the gateway waits until Lambda or HttpClient aborts. When the downstream Lambda crashes or is throttled, the connection is reset → gateway returns **502** (not 504).
5. **Lambda response body > 6 MB** on `/api/market/prices` with no ticker filter returns ~56 rows — small today, but the contract invites growth; Function URL truncation = 502.

---

## 2. Verification (run during/after implementation)

### 2.1 Confirm 429 is Lambda concurrency throttle
```
aws logs start-query --region ap-south-1 \
  --log-group-names /aws/lambda/wealth-api-gateway /aws/lambda/wealth-portfolio-service \
                    /aws/lambda/wealth-market-data-service /aws/lambda/wealth-insight-service \
  --start-time $(date -d '-1 hour' +%s) --end-time $(date +%s) \
  --query-string 'fields @timestamp,@message | filter @message like /Rate Exceeded/ or @message like /TooManyRequestsException/'
```
Also check the `Throttles` metric per function:
```
aws cloudwatch get-metric-statistics --region ap-south-1 --namespace AWS/Lambda \
  --metric-name Throttles --dimensions Name=FunctionName,Value=wealth-portfolio-service \
  --start-time $(date -u -d '-1 hour' +%Y-%m-%dT%H:%M:%S) \
  --end-time $(date -u +%Y-%m-%dT%H:%M:%S) --period 60 --statistics Sum
```

### 2.2 Confirm 502 causes
- `aws lambda get-account-settings --region ap-south-1` — read `ConcurrentExecutions` and `UnreservedConcurrentExecutions`.
- CloudWatch Insights on each downstream service, last 1 h:
  `filter @type = "REPORT" | stats max(@duration) as p_max, avg(@initDuration) as initAvg by @logStream | sort p_max desc`
- Insights for crash markers:
  `filter @message like /Extension.Crash/ or @message like /Task timed out/ or @message like /Runtime exited/`
- For insight-service hot path, grep for Bedrock latency: `filter @message like /Bedrock/ | stats count() by bin(1m)`.

---

## 3. Fixes

Tasks are listed as concrete todos (see section 4). At a glance:

### 3.1 Concurrency — capacity + demand (both Lambda and Bedrock)
- **Request AWS quota increase** for `aws_lambda_function:unreserved-concurrent-executions` from 10 → at minimum 100 (Support → Service quotas). This alone should erase most 429s.
- **Check Bedrock Claude 3 Haiku quotas.** AWS Bedrock enforces per-model RPM and TPM limits per region (`us-east-1`). If the frontend is fanning out 56 sequential Bedrock calls, the RPM ceiling is hit just as fast as the Lambda concurrency ceiling. Navigate to AWS Console → Bedrock → Model access → Claude 3 Haiku → Request limit increase for `us-east-1`. Until increased, the per-ticker fix in 3.4 is essential.
- **Reduce frontend fan-out.** Collapse the duplicate `fetchPortfolio` calls: have `usePortfolio`, `usePortfolioPerformance`, and `useAssetAllocation` share a single cache entry (e.g. `usePortfolio` as the owner, others `useQueryClient().getQueryData()` / `select`), or key all three on the same `queryKey` and derive locally.
- **Stop retrying 429** in TanStack Query: set `retry: (fail, err) => err.status !== 429 && fail < 2` in `usePortfolioSummary` / `usePortfolio*`. Also back off 502 retries to 1, not 3.

### 3.2 Cold-start — warmer + faster
- **Provisioned concurrency = 1** on the `live` alias for `wealth-api-gateway` and `wealth-portfolio-service` (the two hottest). Add two `aws_lambda_provisioned_concurrency_config` resources in `modules/compute/main.tf`. This is safe because quota (after 3.1) will cover it; provisioned counts against the account pool.
- Alternatively (no quota increase yet): add a **scheduled EventBridge "warmer"** (rate 5 minutes) that invokes each Function URL's `/actuator/health/liveness` — single-concurrency keep-alive.
- **Bump `market_data_memory_size` and `insight_service_memory_size`** from 1024 → 2048 MB in `infrastructure/terraform/locals.tf`. Memory dictates CPU share; AOT init is CPU-bound. Cold-start drops measurably.

### 3.3 Gateway — fail fast and clearly
Edit `api-gateway/src/main/resources/application-prod.yml` to add a bounded response timeout so client sees 504 (not a hung 60 s socket) when a downstream is slow:

```yaml
spring:
  cloud:
    gateway:
      server:
        webflux:
          httpclient:
            connect-timeout: 5000
            response-timeout: 20s
```

### 3.4 `InsightController.getMarketSummary` — bound the fan-out
`insight-service/src/main/java/com/wealth/insight/InsightController.java:48` is the biggest 502 risk. Two changes:

1. Stop issuing Bedrock calls on this endpoint entirely. Return the raw `TickerSummary` map (price + history + trend) with `aiSummary = null`. The per-ticker endpoint `GET /api/insights/market-summary/{ticker}` continues to call Bedrock (single ticker, cacheable).
2. If AI summary is required on the list endpoint, cap the fan-out: take the first N (e.g. 10) tickers, run Bedrock calls with a bounded `CompletableFuture.allOf` + timeout (2 s each), and degrade missing ones to `null`.

### 3.5 `MarketDataService.getMarketSummary` — drop `KEYS`, use ZSET
Replace `redisTemplate.keys("market:latest:*")` with a **Redis ZSET** (`market:tracked-tickers`) scored by `System.currentTimeMillis()`:

- `processUpdate()` does `ZADD market:tracked-tickers <epochMs> <ticker>` after writing the latest price.
- `processUpdate()` also calls `ZREMRANGEBYSCORE market:tracked-tickers 0 <(now - 24h in ms)>` to evict tickers that have stopped receiving updates — prevents unbounded SET growth and stops the backend from returning dead/stale entries forever.
- `getMarketSummary()` replaces `redisTemplate.keys(...)` with `redisTemplate.opsForZSet().range("market:tracked-tickers", 0, -1)`.

This eliminates the `KEYS` full-scan that triggers Upstash rate limits and blocks the Redis event loop.

### 3.6 Graceful 503 on insight overload
Already have `GlobalExceptionHandler` mapping `AdvisorUnavailableException` → 503. Make `InsightController.getMarketSummary` also wrap any timeout in `AdvisorUnavailableException` (instead of `ResponseEntity.internalServerError()`), so the frontend receives 503 and can show "AI temporarily unavailable" without blowing up with 502.

### 3.7 Ensure prod response size is bounded
- Confirm `GET /api/market/prices` without `tickers=` is not abused. Either make the param required server-side, or cap the result size. `MarketPriceController.java:31` currently returns all rows.

### 3.8 CloudFront — align origin timeout with the total budget

CloudFront has a hard **30-second origin timeout** on the `PriceClass_100` tier. The total time budget for any request is:

```
Cold-start JVM init time (up to ~15 s with LWA readiness retries)
+ Spring context hydration
+ Database / Redis query time
+ Bedrock call time (up to ~12 s on first call)
──────────────────────────────────────────────────────
Must be STRICTLY < 30 s (CloudFront will drop and return 502 even if Lambda succeeds)
```

Actions:
- Keep `response-timeout: 20s` in the gateway (3.3) — this ensures the gateway itself returns 504 before CloudFront's 30 s timer fires, making the failure visible and debuggable (504 vs 502).
- Set `origin_read_timeout = 25` in the CloudFront API `ordered_cache_behavior` in `modules/cdn/main.tf` (default is 30; lowering to 25 creates a 5 s buffer for CloudFront overhead).
- Ensure `InsightController` per-ticker Bedrock timeout is ≤ 10 s (gateway response-timeout 20 s minus ~10 s for everything else). This is enforced by the `CompletableFuture` timeout in fix 3.4.

CloudFront by default does **not** retry on 5xx for dynamic responses (`/api/*` cache behavior has `max_ttl = 0`), so a single slow Lambda will not cause amplification here.

### 3.9 LWA readiness — extend retries for cold starts
Add `AWS_LWA_READINESS_CHECK_MAX_RETRIES = "20"` to `local.common_env` in `modules/compute/main.tf` (and to `api_gateway_container_env`). The current default of 3–5 retries at 1 s intervals gives the JVM only ~5 s before LWA marks it not-ready and crashes the extension. With 20 retries the JVM has ~20 s — enough for a Spring Boot + AOT cold start under memory pressure — while staying safely within the 30 s CloudFront budget when combined with the 20 s gateway response-timeout.

---

## 4. Execution order

All work is done on branch `architecture/fix-429-502-issues` forked from `architecture/cloud-native-extraction`.

**Wave 1 — highest ROI, lowest risk (start in parallel):**
- File Lambda + Bedrock quota requests (async; non-blocking for code work)
- Fix `InsightController.getMarketSummary` fan-out (3.4)
- Fix `MarketDataService` ZSET replacement (3.5 — eliminates Upstash `KEYS` rate-limit)
- Frontend dedup + retry policy (3.4 frontend, reduces concurrency burst)

**Wave 2 — infra config (after Wave 1 deployed):**
- Gateway response-timeout (3.3)
- LWA `AWS_LWA_READINESS_CHECK_MAX_RETRIES=20` (3.9)
- CloudFront origin_read_timeout (3.8)
- Memory bump market-data + insight (3.2)

**Wave 3 — post quota increase:**
- Provisioned concurrency on `live` alias for api-gateway + portfolio-service (3.2)
- EventBridge warmer (optional fallback)

After Wave 1 lands, re-run the CloudWatch Insights verification queries (section 2) to confirm throttle counts drop before proceeding to Wave 3.
