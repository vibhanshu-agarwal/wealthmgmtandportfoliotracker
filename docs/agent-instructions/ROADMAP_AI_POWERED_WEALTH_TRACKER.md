# Project Plan: AI-Powered Wealth Management & Portfolio Tracker

**Target Domain:** `vibhanshu-portfolio-ai.com`  
**Stack:** Java 25 (LTS), Spring Boot 4.x, PostgreSQL, MongoDB  
**Core Goal:** Demonstrate a Senior-level Cloud Architecture using modern Java features, multi-model persistence, and Enterprise AI (Amazon Bedrock) while maintaining an ultra-low-cost footprint.

---

## ## Phase 1: Infrastructure & Networking (Plan 1: "Ultra-Low Cost")
*Goal: Secure, global entry point with nearly zero fixed monthly costs.*

### Components
* **Domain & DNS:** Registered via **Amazon Route 53**.
* **SSL/TLS:** Free certificate via **AWS Certificate Manager (ACM)**.
* **CDN:** **Amazon CloudFront** to provide a global edge and HTTPS termination.
* **Compute:** **AWS Lambda** with **Function URLs** (Java 25 / Spring Cloud Function).

### Architectural Workflow
1. **Route 53** points the domain to a **CloudFront Distribution**.
2. **CloudFront** handles HTTPS and forwards requests to the **Lambda Function URL**.
3. **Lambda** executes the Spring Boot 4 logic and returns the response.

---

## ## Phase 2: Application Layer (Java 25 & Spring Boot 4.x)
*Goal: Leverage the latest LTS features for performance and modularity.*

### Key Technologies
* **Framework:** **Spring Boot 4.x** (Native support for Jakarta EE 11 and Virtual Threads).
* **Persistence (Polyglot):**
    * **PostgreSQL (Relational):** Master record for user profiles, transactions, and portfolio snapshots.
    * **MongoDB (Document):** Flexible storage for market data feeds, metadata, and unstructured AI-generated insights.
* **Connectivity:** **RDS Proxy** to manage PostgreSQL connection pooling during Lambda scaling.

### Implementation Tasks
* Use **Java 25** features (Scoped Values, Structured Concurrency) to handle asynchronous API calls to financial data providers.
* Implement **Spring Data MongoDB** and **Spring Data JPA** for seamless dual-database management.

---

## ## Phase 3: AI Integration (Amazon Bedrock)
*Goal: Incorporate Enterprise-grade Generative AI using high-level abstractions.*

### AI Strategy
* **Model Selection:** **Anthropic Claude 3.5 Sonnet** (Analysis) and **Amazon Titan** (Embeddings).
* **Security:** Use **IAM Roles** (Execution Roles) to grant Lambda permission to invoke Bedrock. **No API keys stored in code.**
* **Retrieval-Augmented Generation (RAG):**
    * Store financial documents and unstructured reports in **Amazon S3**.
    * Use **Bedrock Knowledge Bases** to automate the vectorization and retrieval process.

### Use Cases to Implement
* **Portfolio "Chat":** Natural language interaction with both PostgreSQL (structured) and MongoDB (unstructured) data.
* **Risk Assessment:** Use Claude to analyze market news stored in MongoDB against current holdings in PostgreSQL.

---

## ## Phase 4: The "Architect Demo" (Plan 2: Scaling Up)
*Goal: Demonstrate Enterprise High Availability (HA) and Scalability during interviews.*

### Transition Steps
1. **Containerize:** Build a Docker image of the Java 25 app and push to **Amazon ECR**.
2. **Deploy via ECS Express:** Automated deployment creating an **Application Load Balancer (ALB)** and **Fargate** tasks across multiple Availability Zones.
3. **Update DNS:** Change the **Route 53** Alias record to point from CloudFront to the new **ALB**.
4. **Cleanup:** After the demo, run `delete-service` to stop hourly charges and revert to the Lambda setup.

---

## ## Estimated Monthly Cost Summary (Plan 1)

| Service | Cost (Est. / Mo) | Reason |
| :--- | :--- | :--- |
| **Route 53** | ~$1.50 | 1 Hosted Zone + Domain Amortization |
| **Lambda** | $0.00 | Under 1M requests/mo (Free Tier) |
| **CloudFront** | $0.00 | Under 1TB data transfer (Free Tier) |
| **Amazon Bedrock** | ~$0.10 - $1.00 | Pay-per-token usage |
| **PostgreSQL / MongoDB** | Variable | Use **Free Tier** for RDS and **MongoDB Atlas Free Tier** |
| **Total** | **~$2.00 - $3.00** | **Production-ready URL with AI capability** |

---

## ## Key Interview "Signal" Points
* **Polyglot Persistence:** "I used PostgreSQL for transactional integrity and MongoDB for the flexible schema required by fast-changing market data."
* **Modern Java:** "Leveraging Java 25 and Spring Boot 4.0 allowed me to use Virtual Threads for non-blocking I/O when fetching news for the AI engine."
* **FinOps Architecture:** "The system is architected to survive at a $3/month baseline, with the ability to scale to a full containerized cluster as needed."