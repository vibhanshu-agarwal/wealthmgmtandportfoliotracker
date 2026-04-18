# Project Structure

## Root Layout

```
wealthmgmtandportfoliotracker/
├── api-gateway/          # Spring Cloud Gateway — single entry point, rate limiting via Redis
├── portfolio-service/    # Portfolio holdings, valuations, Kafka consumer for price updates
├── market-data-service/  # Market prices, MongoDB, Kafka producer
├── insight-service/      # Kafka consumer, generates AI insights
├── common-dto/           # Shared event/DTO contracts (e.g. PriceUpdatedEvent)
├── frontend/             # Next.js app
├── infrastructure/       # AWS CDK (TypeScript)
├── docs/                 # Architecture docs, ADRs, agent instructions, changelogs
├── docker-compose.yml    # Local dev infrastructure
├── build.gradle          # Root Gradle config (shared dependency management)
└── settings.gradle       # Module declarations
```

## Backend Service Layout (consistent across all services)

```
{service}/
├── src/main/java/com/wealth/{domain}/
│   ├── {Domain}Application.java      # Spring Boot entry point
│   ├── {Entity}.java                 # JPA/MongoDB entity
│   ├── {Entity}Repository.java       # Spring Data repository
│   ├── {Domain}Service.java          # Business logic
│   ├── {Domain}Controller.java       # REST controller
│   └── dto/                          # Request/response DTOs
├── src/main/resources/
│   └── application.yml
├── src/test/java/...
├── build.gradle
└── Dockerfile
```

- Package root: `com.wealth`
- Each service owns its domain package (e.g. `com.wealth.portfolio`, `com.wealth.market`)
- Shared contracts live in `common-dto` and are referenced via `implementation project(':common-dto')`

## Frontend Layout

```
frontend/src/
├── app/
│   ├── (auth)/login/         # Login page
│   └── (dashboard)/          # Protected dashboard routes
│       ├── layout.tsx
│       ├── overview/
│       ├── portfolio/
│       ├── market-data/
│       ├── ai-insights/
│       └── settings/
├── components/
│   ├── ui/                   # shadcn/ui primitives (button, card, table, etc.)
│   ├── charts/               # Recharts wrappers (AllocationChart, PerformanceChart)
│   ├── portfolio/            # Domain components (HoldingsTable, SummaryCards)
│   └── layout/               # Shell components (Sidebar, Header, ThemeProvider, etc.)
├── lib/
│   ├── api/                  # API call functions (portfolio.ts, etc.)
│   ├── hooks/                # TanStack Query hooks (usePortfolio, etc.)
│   └── utils/                # Helpers (cn.ts for Tailwind merging, format.ts)
├── types/                    # Shared TypeScript types (portfolio.ts, etc.)
└── test/msw/                 # MSW handlers and server setup for unit tests
```

## Infrastructure Layout

```
infrastructure/
├── bin/infrastructure.ts     # CDK app entry point
├── lib/                      # CDK stack definitions
└── test/                     # Jest CDK snapshot/unit tests
```

## docs/ Layout

```
docs/
├── architecture/         # Executive summary + detailed architecture docs + guardrails
├── adr/                  # Architectural Decision Records — review before proposing new designs
├── agent-instructions/   # Prompts and plans issued to AI agents
│   └── ROADMAP_AI_POWERED_WEALTH_TRACKER.md  ← ACTIVE roadmap (use this)
│   └── ROADMAP_CDK_TO_MULTICLOUD_GITOPS.md   ← Older plan, disregard unless instructed
├── changes/              # Changelogs written after each significant implementation phase
└── todos/                # Pending coding tasks with file+line references
```

## Key Conventions

- New shadcn/ui components go in `frontend/src/components/ui/`
- Domain-specific React components go in a named subfolder under `frontend/src/components/`
- API functions and their corresponding TanStack Query hooks are kept separate (`lib/api/` vs `lib/hooks/`)
- Flyway migrations for portfolio-service go in `src/main/resources/db/migration/`
- All inter-service event contracts must be defined in `common-dto`, never duplicated per-service
- After any significant implementation, add a summary to `docs/changes/`
- Review `docs/adr/` before proposing new architectural decisions
