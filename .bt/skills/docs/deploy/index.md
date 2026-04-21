> ## Documentation Index
> Fetch the complete documentation index at: https://braintrust.dev/docs/llms.txt
> Use this file to discover all available pages before exploring further.

# Deploy

> Ship your AI applications to production with confidence

Once you've built and evaluated your AI application, Braintrust helps you deploy it to production with the same tools you used during development. Deploy prompts, functions, and workflows through a unified API that works across any provider.

## Why deploy with Braintrust

Deploying through Braintrust gives you:

* **Unified API**: Call any AI provider (OpenAI, Anthropic, Google, AWS, etc.) through a single interface. Use any [supported provider's SDK](/integrations/ai-providers) to call any provider's models.
* **Automatic observability**: Every production request is logged and traceable.
* **Caching**: Reduce costs and latency with built-in response caching.
* **Version control**: Deploy prompts and functions with full version history.
* **Environment management**: Separate dev, staging, and production configurations.
* **Fallbacks**: Automatically retry failed requests with backup providers.

## Deploy prompts and functions

[Prompts](/deploy/prompts) created in Braintrust can be called from your application code. Changes to prompts in the UI immediately affect production behavior, enabling rapid iteration without redeployment.

```typescript theme={"theme":{"light":"github-light","dark":"github-dark-dimmed"}}
const logger = initLogger({ projectName: "My Project" });

// Call a deployed prompt by slug
const response = await logger.invoke("my-prompt-slug", {
  input: { question: "What is the capital of France?" },
});
```

Deploy [functions](/deploy/functions) (tools, scorers, workflows) the same way. Braintrust handles versioning, rollbacks, and observability automatically.

## Use the Braintrust gateway

The [Braintrust gateway](/deploy/gateway) provides a unified API to access LLM models from OpenAI, Anthropic, Google, AWS, Mistral, and third-party providers. Point your SDKs to the gateway URL and immediately get automatic caching, observability, and multi-provider support.

```typescript theme={"theme":{"light":"github-light","dark":"github-dark-dimmed"}}
import { OpenAI } from "openai";

// Use any supported provider's SDK to call any provider's models
const client = new OpenAI({
  baseURL: "https://gateway.braintrust.dev/v1",
  apiKey: process.env.BRAINTRUST_API_KEY,
});

// Call Anthropic's Claude using the OpenAI SDK
const response = await client.chat.completions.create({
  model: "claude-sonnet-4",
  messages: [{ role: "user", content: "Hello!" }],
});
```

## Manage environments

[Environments](/deploy/environments) separate your development, staging, and production configurations. Set different prompts, functions, or API keys per environment:

```typescript theme={"theme":{"light":"github-light","dark":"github-dark-dimmed"}}
const logger = initLogger({
  projectName: "My Project",
  environment: "production",
});
```

Changes to development environments don't affect production until you promote them.

## Monitor deployments

Every production request flows through the same observability system you used during development. View logs, filter by errors, score online, and [create dashboards](/deploy/monitor) to track performance.

Set up alerts to notify you when error rates spike or latency exceeds thresholds.

## Next steps

* [Use the Braintrust gateway](/deploy/gateway) to route requests to any AI provider with production-grade reliability
* [Deploy prompts](/deploy/prompts) to ship and version prompts in production
* [Deploy functions](/deploy/functions) to deploy tools, scorers, and workflows
* [Monitor deployments](/deploy/monitor) to track production performance and errors
* [Manage environments](/deploy/environments) to separate dev, staging, and production
