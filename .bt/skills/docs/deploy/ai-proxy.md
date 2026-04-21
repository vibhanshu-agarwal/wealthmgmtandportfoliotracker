> ## Documentation Index
> Fetch the complete documentation index at: https://braintrust.dev/docs/llms.txt
> Use this file to discover all available pages before exploring further.

# Use the proxy (deprecated)

> Call any AI provider through a unified interface (deprecated, use the gateway instead)

<Warning>
  **Deprecated.** The AI proxy is deprecated and will no longer be regularly maintained. Use the [gateway](/deploy/gateway) instead for production-grade reliability and a similar set of features. See the [migration guide](/deploy/gateway#migrate-from-the-ai-proxy) for upgrade instructions.
</Warning>

The Braintrust AI proxy provides unified access to models from OpenAI, Anthropic, Google, AWS, Mistral, and third-party providers through a single API. Point your OpenAI SDK to the proxy URL and immediately get automatic caching, observability, and multi-provider support.

## Quickstart

You can use the proxy without a Braintrust account by providing your API key from any supported provider. If you have a Braintrust account, you can use a single Braintrust API key to access all AI providers through one interface.

The proxy is fully compatible with the OpenAI SDK. Set the API URL to `https://api.braintrust.dev/v1/proxy`.

Run the following script twice to see caching in action:

<CodeGroup dropdown>
  ```typescript theme={"theme":{"light":"github-light","dark":"github-dark-dimmed"}}
  import { OpenAI } from "openai";

  const client = new OpenAI({
    baseURL: "https://api.braintrust.dev/v1/proxy",
    apiKey: process.env.BRAINTRUST_API_KEY,
  });

  async function main() {
    const start = performance.now();
    const response = await client.chat.completions.create({
      model: "gpt-4o-mini", // Can use claude-3-5-sonnet-latest, gemini-2.5-flash, etc.
      messages: [{ role: "user", content: "What is a proxy?" }],
      seed: 1, // A seed activates the cache
    });
    console.log(response.choices[0].message.content);
    console.log(`Took ${(performance.now() - start) / 1000}s`);
  }

  main();
  ```

  ```python theme={"theme":{"light":"github-light","dark":"github-dark-dimmed"}}
  import os
  import time

  from openai import OpenAI

  client = OpenAI(
      base_url="https://api.braintrust.dev/v1/proxy",
      api_key=os.environ["BRAINTRUST_API_KEY"],
  )

  start = time.time()
  response = client.chat.completions.create(
      model="gpt-4o-mini",  # Can use claude-3-5-sonnet-latest, gemini-2.5-flash, etc.
      messages=[{"role": "user", "content": "What is a proxy?"}],
      seed=1,  # A seed activates the cache
  )
  print(response.choices[0].message.content)
  print(f"Took {time.time() - start}s")
  ```

  ```bash theme={"theme":{"light":"github-light","dark":"github-dark-dimmed"}}
  time curl -i https://api.braintrust.dev/v1/proxy/chat/completions \
    -H "Content-Type: application/json" \
    -d '{
      "model": "gpt-4o-mini",
      "messages": [{"role": "user", "content": "What is a proxy?"}],
      "seed": 1
    }' \
    -H "Authorization: Bearer $BRAINTRUST_API_KEY" \
    --compress
  ```
</CodeGroup>

The second run will be significantly faster because the proxy serves your request from its cache, rather than calling the AI provider's model. The proxy runs on Cloudflare Workers and caches requests with end-to-end encryption.

The proxy supports over 100 models including GPT-5, Claude 4, Gemini 2.5, and Llama models through providers like Together AI and AWS Bedrock. New models are added regularly.

## Configure API keys

Add provider API keys in your [organization settings](/admin/organizations#configure-ai-providers) under **AI providers**, configure them at the [project level](/admin/projects#configure-ai-providers) to override organization defaults, or set them up inline when running playgrounds or prompts. Then use your Braintrust API key to access all providers through the proxy.

Organization-level providers are available across all projects. Project-level providers override organization-level keys for that specific project, allowing you to isolate API usage, manage separate billing, or use different credentials per project. Project-level API keys take precedence over organization-level keys when making proxy requests in a project context.

Without a Braintrust account, you can use the proxy with individual provider API keys to get automatic caching.

The proxy response returns the `x-bt-used-endpoint` header, which specifies which of your configured providers was used to complete the request.

### Supported providers

Standard providers include:

* OpenAI (GPT-4o, GPT-4o-mini, o4-mini, etc.)
* Anthropic (Claude 4 Sonnet, Claude 3.5 Sonnet, etc.)
* Google (Gemini 2.5 Flash, Gemini 2.5 Pro, etc.)
* AWS Bedrock (Claude, Llama, Mistral models)
* Azure OpenAI Service
* Third-party providers (Together AI, Fireworks, Groq, Replicate, etc.)

If you need a model that isn't supported, [let us know](mailto:support@braintrust.dev).

## Enable caching

The proxy automatically caches results and reuses them when possible. Because the proxy runs on the edge, cached requests return in under 100ms. This is especially useful when developing and frequently re-running or evaluating the same prompts.

### Cache modes

There are three caching modes: `auto` (default), `always`, `never`:

* In `auto` mode, requests are cached if they have `temperature=0` or the [`seed` parameter](https://cookbook.openai.com/examples/reproducible_outputs_with_the_seed_parameter) set and they are one of the supported paths.
* In `always` mode, requests are cached as long as they are one of the supported paths.
* In `never` mode, the cache is never read or written to.

The supported paths are:

* `/auto`
* `/embeddings`
* `/chat/completions`
* `/completions`
* `/moderations`

Set the cache mode by passing the `x-bt-use-cache` header:

<CodeGroup dropdown>
  ```typescript theme={"theme":{"light":"github-light","dark":"github-dark-dimmed"}}
  import { OpenAI } from "openai";

  const client = new OpenAI({
    baseURL: "https://api.braintrust.dev/v1/proxy",
    defaultHeaders: {
      "x-bt-use-cache": "always",
    },
    apiKey: process.env.BRAINTRUST_API_KEY,
  });
  ```

  ```python theme={"theme":{"light":"github-light","dark":"github-dark-dimmed"}}
  import os
  from openai import OpenAI

  client = OpenAI(
      base_url="https://api.braintrust.dev/v1/proxy",
      default_headers={"x-bt-use-cache": "always"},
      api_key=os.getenv("BRAINTRUST_API_KEY"),
  )
  ```
</CodeGroup>

The response includes `x-bt-cached: HIT` or `MISS` to indicate cache status.

### Cache TTL

By default, cached results expire after 1 week. Set the TTL for individual requests by passing the `x-bt-cache-ttl` header. The TTL is specified in seconds and must be between 1 and 604800 (7 days).

### Cache control

The proxy supports a limited set of [Cache-Control](https://developer.mozilla.org/en-US/docs/Web/HTTP/Headers/Cache-Control) directives:

* To bypass the cache, set the `Cache-Control` header to `no-cache, no-store`. This is semantically equivalent to setting the `x-bt-use-cache` header to `never`.
* To force a fresh request, set the `Cache-Control` header to `no-cache`. Without the `no-store` directive, the response will be cached for subsequent requests.
* To request a cached response with a maximum age, set the `Cache-Control` header to `max-age=<seconds>`. If the cached data is older than the specified age, the cache will be bypassed and a new response will be generated. Combine this with `no-store` to bypass the cache for a request without overwriting the current cached response.

When cache control directives conflict with the `x-bt-use-cache` header, the cache control directives take precedence.

The proxy returns the `x-bt-cached` header in the response with `HIT` or `MISS` to indicate whether the response was served from the cache, the `Age` header to indicate the age of the cached response, and the `Cache-Control` header with the `max-age` directive to return the TTL of the cached response.

For example, to set the cache mode to `always` with a TTL of 2 days:

<CodeGroup dropdown>
  ```typescript theme={"theme":{"light":"github-light","dark":"github-dark-dimmed"}}
  import { OpenAI } from "openai";

  const client = new OpenAI({
    baseURL: "https://api.braintrust.dev/v1/proxy",
    defaultHeaders: {
      "x-bt-use-cache": "always",
      "Cache-Control": "max-age=172800",
    },
    apiKey: process.env.BRAINTRUST_API_KEY,
  });

  async function main() {
    const response = await client.chat.completions.create({
      model: "gpt-4o",
      messages: [{ role: "user", content: "What is a proxy?" }],
    });
    console.log(response.choices[0].message.content);
  }

  main();
  ```

  ```python theme={"theme":{"light":"github-light","dark":"github-dark-dimmed"}}
  import os

  from openai import OpenAI

  client = OpenAI(
      base_url="https://api.braintrust.dev/v1/proxy",
      default_headers={"x-bt-use-cache": "always", "Cache-Control": "max-age=172800"},
      api_key=os.environ["BRAINTRUST_API_KEY"],
  )

  response = client.chat.completions.create(
      model="gpt-4o",
      messages=[{"role": "user", "content": "What is a proxy?"}],
  )
  print(response.choices[0].message.content)
  ```

  ```bash theme={"theme":{"light":"github-light","dark":"github-dark-dimmed"}}
  time curl -i https://api.braintrust.dev/v1/proxy/chat/completions \
    -H "Content-Type: application/json" \
    -H "x-bt-use-cache: always" \
    -H "Cache-Control: max-age=172800" \
    -d '{
      "model": "gpt-4o",
      "messages": [{"role": "user", "content": "What is a proxy?"}]
    }' \
    -H "Authorization: Bearer $BRAINTRUST_API_KEY" \
    --compress
  ```
</CodeGroup>

### Cache encryption

The proxy uses [AES-GCM](https://en.wikipedia.org/wiki/Galois/Counter_Mode) to encrypt the cache, using a key derived from your API key. Results are cached for 1 week unless otherwise specified in request headers.

This design ensures that the cache is only accessible to you. Braintrust cannot see your data and does not store or log API keys.

<Note>
  Because the cache's encryption key is your API key, cached results are scoped to an individual user. Braintrust customers can opt into sharing cached results across users within their organization.
</Note>

## Enable logging

To log requests that you make through the proxy, specify an `x-bt-parent` header with the project or experiment you'd like to log to. While tracing, you must use a `BRAINTRUST_API_KEY` rather than a provider's key. The proxy will derive your provider's key and facilitate tracing using the `BRAINTRUST_API_KEY`.

<CodeGroup dropdown>
  ```typescript theme={"theme":{"light":"github-light","dark":"github-dark-dimmed"}}
  import { OpenAI } from "openai";

  const client = new OpenAI({
    baseURL: "https://api.braintrust.dev/v1/proxy",
    defaultHeaders: {
      "x-bt-parent": "project_id:YOUR_PROJECT_ID", // Replace with your project ID
    },
    apiKey: process.env.BRAINTRUST_API_KEY,
  });

  async function main() {
    const response = await client.chat.completions.create({
      model: "gpt-4o",
      messages: [{ role: "user", content: "What is a proxy?" }],
    });
    console.log(response.choices[0].message.content);
  }

  main();
  ```

  ```python theme={"theme":{"light":"github-light","dark":"github-dark-dimmed"}}
  import os

  from openai import OpenAI

  client = OpenAI(
      base_url="https://api.braintrust.dev/v1/proxy",
      default_headers={"x-bt-parent": "project_id:YOUR_PROJECT_ID"},  # Replace with your project ID
      api_key=os.environ["BRAINTRUST_API_KEY"],
  )

  response = client.chat.completions.create(
      model="gpt-4o",
      messages=[{"role": "user", "content": "What is a proxy?"}],
  )
  print(response.choices[0].message.content)
  ```

  ```bash theme={"theme":{"light":"github-light","dark":"github-dark-dimmed"}}
  time curl -i https://api.braintrust.dev/v1/proxy/chat/completions \
    -H "Content-Type: application/json" \
    -H "x-bt-parent: project_id:YOUR_PROJECT_ID" \
    -d '{
      "model": "gpt-4o",
      "messages": [{"role": "user", "content": "What is a proxy?"}]
    }' \
    -H "Authorization: Bearer $BRAINTRUST_API_KEY" \
    --compress
  ```
</CodeGroup>

The `x-bt-parent` header sets the trace's parent project or experiment. You can use a prefix like `project_id:`, `project_name:`, or `experiment_id:` or pass in a [span slug](/instrument/advanced-tracing#trace-distributed-systems) (`span.export()`) to nest the trace under a span within the parent object.

## Load balance across providers

If you have multiple API keys for a given model type (e.g., OpenAI and Azure for `gpt-4o`), the proxy automatically load balances across them. This is useful for working around per-account rate limits and providing resiliency if one provider is down.

To set up load balancing:

1. Add your primary provider key (e.g., OpenAI) in your [organization settings](/admin/organizations#configure-ai-providers).
2. Add Azure OpenAI as a custom provider for the same models.
3. The proxy automatically distributes requests across both.

Load balancing provides:

* Resilience if one provider is down
* Higher effective rate limits
* Geographic distribution

Configure endpoints on the [secrets page](https://www.braintrust.dev/app/settings?subroute=secrets) in your Braintrust account.

## Use reasoning models

<Note>
  For hybrid deployments, reasoning support requires `v0.0.74` or later.
</Note>

The proxy lets you write one chat completion call that works across multiple providers by standardizing support for reasoning-specific features.

* **Supported providers:** OpenAI, Anthropic, and Google
* **Unified parameters:** Consistent parameters related to reasoning:
  * `reasoning_effort`: Specify the desired level of reasoning complexity
  * `reasoning_enabled`: Explicit flag to enable or disable reasoning output (has no effect for OpenAI models)
  * `reasoning_budget`: Specify a budget for the reasoning process (requires either `reasoning_effort` or `reasoning_enabled`)
* **Structured reasoning output:** Responses include a list of `reasoning` objects as part of the assistant's message. Each object contains the `content` of the reasoning step and a unique `id`. Include these `reasoning` objects from previous turns in subsequent requests to maintain context in multi-turn conversations.
* **Streaming support:** A `reasoning_delta` is available when streaming, allowing you to process reasoning output as it is generated.
* **Type safety:** Type augmentations are available for better developer experience. For JavaScript/TypeScript, use the `@braintrust/proxy/types` module to extend OpenAI's types. For Python, the `braintrust-proxy` package provides casting utilities for input parameters and output objects.

### Non-streaming request

Here's a non-streaming chat completion request using a Google model with reasoning enabled:

<CodeGroup dropdown>
  ```typescript theme={"theme":{"light":"github-light","dark":"github-dark-dimmed"}}
  import { OpenAI } from "openai";
  import "@braintrust/proxy/types";

  async function main() {
    const openai = new OpenAI({
      baseURL: `${process.env.BRAINTRUST_API_URL || "https://api.braintrust.dev"}/v1/proxy`,
      apiKey: process.env.BRAINTRUST_API_KEY,
    });

    try {
      const response = await openai.chat.completions.create({
        model: "gemini-2.5-flash",
        reasoning_enabled: true,
        reasoning_budget: 1024,
        stream: false,
        messages: [
          {
            role: "user",
            content: "How many rs in 'ferrocarril'",
          },
          {
            role: "assistant",
            content: "There are 4 letter 'r's in the word \"ferrocarril\".",
            reasoning: [
              {
                id: "",
                content:
                  "To count the number of 'r's in the word 'ferrocarril', I'll just go through the word letter by letter.\n\n'ferrocarril' has the following letters:\nf-e-r-r-o-c-a-r-r-i-l\n\nLooking at each letter:\n- 'f': not an 'r'\n- 'e': not an 'r'\n- 'r': This is an 'r', so that's 1.\n- 'r': This is an 'r', so that's 2.\n- 'o': not an 'r'\n- 'c': not an 'r'\n- 'a': not an 'r'\n- 'r': This is an 'r', so that's 3.\n- 'r': This is an 'r', so that's 4.\n- 'i': not an 'r'\n- 'l': not an 'r'\n\nSo there are 4 'r's in the word 'ferrocarril'.",
              },
            ],
          },
          {
            role: "user",
            content: "How many e in what you said?",
          },
        ],
      });

      console.log({
        message: response.choices[0].message,
        reasoning: response.choices[0].reasoning,
      });
    } catch (error) {
      console.error("Error during non-streaming request:", error);
    }
  }

  main().catch(console.error);
  ```

  ```python theme={"theme":{"light":"github-light","dark":"github-dark-dimmed"}}
  import json
  import os

  from braintrust_proxy import as_openai_chat_message_param
  from openai import OpenAI

  client = OpenAI(
      base_url=f"{os.getenv('BRAINTRUST_API_URL') or 'https://api.braintrust.dev'}/v1/proxy",
      api_key=os.getenv("BRAINTRUST_API_KEY"),
  )

  try:
      print("Non-streaming Response:")
      response = client.chat.completions.create(
          model="gemini-2.5-flash",
          extra_body={
              "reasoning_enabled": True,
              "reasoning_budget": 1024,
          },
          stream=False,
          messages=[
              {
                  "role": "user",
                  "content": "How many rs in 'ferrocarril'",
              },
              as_openai_chat_message_param(
                  {
                      "role": "assistant",
                      "content": "There are 4 letter 'r's in the word \"ferrocarril\".",
                      "reasoning": [
                          {
                              "id": "",
                              "content": "To count the number of 'r's in the word 'ferrocarril', I'll just go through the word letter by letter.\n\n'ferrocarril' has the following letters:\nf-e-r-r-o-c-a-r-r-i-l\n\nLooking at each letter:\n- 'f': not an 'r'\n- 'e': not an 'r'\n- 'r': This is an 'r', so that's 1.\n- 'r': This is an 'r', so that's 2.\n- 'o': not an 'r'\n- 'c': not an 'r'\n- 'a': not an 'r'\n- 'r': This is an 'r', so that's 3.\n- 'r': This is an 'r', so that's 4.\n- 'i': not an 'r'\n- 'l': not an 'r'\n\nSo there are 4 'r's in the word 'ferrocarril'.",
                          },
                      ],
                  }
              ),
              {
                  "role": "user",
                  "content": "How many e in what you said?",
              },
          ],
      )

      print(
          json.dumps(
              {
                  "message": response.choices[0].message.dict(),
                  "reasoning": getattr(response.choices[0].message, "reasoning", None),
              },
              indent=2,
          )
      )
  except Exception as e:
      print("Error during non-streaming request:", e)
  ```
</CodeGroup>

### Streaming request

This example shows how to handle the `reasoning_delta` when streaming chat completion responses:

<CodeGroup dropdown>
  ```typescript theme={"theme":{"light":"github-light","dark":"github-dark-dimmed"}}
  import { OpenAI } from "openai";
  import "@braintrust/proxy/types";

  async function main() {
    const openai = new OpenAI({
      baseURL: `${process.env.BRAINTRUST_API_URL || "https://api.braintrust.dev"}/v1/proxy`,
      apiKey: process.env.BRAINTRUST_API_KEY,
    });

    try {
      console.log("Streaming Request:");
      const stream = await openai.chat.completions.create({
        model: "claude-sonnet-4",
        messages: [
          {
            role: "user",
            content: "Tell me a short story.",
          },
        ],
        reasoning_effort: "high",
        stream: true,
      });

      for await (const event of stream) {
        if (event.choices && event.choices[0].delta) {
          const delta = event.choices[0].delta;
          if (delta.content) {
            process.stdout.write(`Content: ${delta.content}`);
          }
          if (delta.reasoning) {
            console.log("\nReasoning delta:", delta.reasoning);
          }
        }
      }
      console.log("\nStreaming Finished.");
    } catch (error) {
      console.error("Error during streaming request:", error);
    }
  }

  main().catch(console.error);
  ```

  ```python theme={"theme":{"light":"github-light","dark":"github-dark-dimmed"}}
  import json
  import os

  from braintrust_proxy import from_openai_chat_completion_choice_delta
  from openai import OpenAI

  client = OpenAI(
      base_url=f"{os.getenv('BRAINTRUST_API_URL') or 'https://api.braintrust.dev'}/v1/proxy",
      api_key=os.getenv("BRAINTRUST_API_KEY"),
  )

  try:
      print("Streaming Request:")
      stream = client.chat.completions.create(
          model="claude-sonnet-4",
          reasoning_effort="high",
          stream=True,
          messages=[
              {
                  "role": "user",
                  "content": "Tell me a short story.",
              },
          ],
      )

      for event in stream:
          delta = from_openai_chat_completion_choice_delta(event.choices[0].delta)
          if delta.content:
              print(f"Content delta: {delta.content}")
          if delta.reasoning:
              print(f"Reasoning delta: {delta.reasoning.dict()}")
      print("Streaming Finished.")

  except Exception as e:
      print("Error during streaming request:", e)
  ```
</CodeGroup>

## Use alternative protocols

The proxy translates OpenAI requests into various provider APIs automatically. You can also use native Anthropic and Gemini API schemas.

### Anthropic API

<CodeGroup dropdown>
  ```bash theme={"theme":{"light":"github-light","dark":"github-dark-dimmed"}}
  curl -X POST https://api.braintrust.dev/v1/proxy/anthropic/messages \
    -H "Content-Type: application/json" \
    -H "Authorization: Bearer $BRAINTRUST_API_KEY" \
    -d '{
      "model": "claude-3-5-sonnet-20240620",
      "messages": [{"role": "user", "content": "What is a proxy?"}]
    }'
  ```
</CodeGroup>

The `anthropic-version` and `x-api-key` headers are not required.

### Gemini API

<CodeGroup dropdown>
  ```bash theme={"theme":{"light":"github-light","dark":"github-dark-dimmed"}}
  curl -X POST https://api.braintrust.dev/v1/proxy/google/models/gemini-2.5-flash:generateContent \
    -H "Content-Type: application/json" \
    -H "Authorization: Bearer $BRAINTRUST_API_KEY" \
    -d '{
      "contents": [
        {
          "role": "user",
          "parts": [{"text": "What is a proxy?"}]
        }
      ]
    }'
  ```
</CodeGroup>

## Add custom providers

Add custom models or endpoints to use with the proxy. Custom providers support self-hosted models, fine-tuned models, and proprietary AI services.

See [Custom providers](/integrations/ai-providers/custom) for setup instructions and configuration options.

## Use realtime models

The proxy supports the [OpenAI Realtime API](https://platform.openai.com/docs/guides/realtime) at the `/realtime` endpoint using WebSockets. Use the official OpenAI SDK (v6.0+) to connect to the proxy's realtime endpoint.

<Note>
  Use `https://braintrustproxy.com/v1`, not `https://api.braintrust.dev/v1/proxy`, for WebSocket-based proxying.
</Note>

### Node.js with ws library

In Node.js environments, use `OpenAIRealtimeWS` from the `openai/realtime/ws` module:

<CodeGroup dropdown>
  ```typescript theme={"theme":{"light":"github-light","dark":"github-dark-dimmed"}}
  import { OpenAIRealtimeWS } from "openai/realtime/ws";

  const rt = new OpenAIRealtimeWS(
    {
      model: "gpt-realtime",
    },
    {
      apiKey: process.env.BRAINTRUST_API_KEY,
      baseURL: "https://braintrustproxy.com/v1",
    },
  );

  rt.socket.addEventListener("open", () => {
    console.log("Connection opened!");

    rt.send({
      type: "session.update",
      session: {
        output_modalities: ["text"], // or ["audio"]
        model: "gpt-realtime",
        type: "realtime",
      },
    });

    rt.send({
      type: "conversation.item.create",
      item: {
        type: "message",
        role: "user",
        content: [{ type: "input_text", text: "Say a couple paragraphs!" }],
      },
    });

    rt.send({ type: "response.create" });
  });

  rt.on("error", (err) => {
    console.error("Error:", err);
  });

  rt.on("response.output_text.delta", (event) => {
    process.stdout.write(event.delta);
  });

  rt.on("response.done", () => rt.close());

  rt.socket.addEventListener("close", () => {
    console.log("\nConnection closed!");
  });
  ```
</CodeGroup>

### Log realtime sessions

To log realtime sessions to Braintrust, pass the `x-bt-parent` header when creating the connection:

<CodeGroup dropdown>
  ```typescript theme={"theme":{"light":"github-light","dark":"github-dark-dimmed"}}
  import { OpenAIRealtimeWS } from "openai/realtime/ws";
  import { initLogger } from "braintrust";

  async function main() {
    const logger = initLogger({ projectName: "My Realtime Project" });

    const rt = new OpenAIRealtimeWS(
      {
        model: "gpt-realtime",
        options: {
          headers: {
            "x-bt-parent": `project_id:${(await logger.project).id}`,
          },
        },
      },
      {
        apiKey: process.env.BRAINTRUST_API_KEY,
        baseURL: "https://braintrustproxy.com/v1",
      },
    );

    rt.socket.addEventListener("open", () => {
      console.log("Connection opened!");

      rt.send({
        type: "session.update",
        session: {
          output_modalities: ["text"], // or ["audio"]
          model: "gpt-realtime",
          type: "realtime",
        },
      });

      rt.send({
        type: "conversation.item.create",
        item: {
          type: "message",
          role: "user",
          content: [{ type: "input_text", text: "Say hello!" }],
        },
      });

      rt.send({ type: "response.create" });
    });

    rt.on("error", (err) => {
      console.error("Error:", err);
    });

    rt.on("response.output_text.delta", (event) =>
      process.stdout.write(event.delta),
    );

    rt.on("response.done", () => rt.close());

    rt.socket.addEventListener("close", () => {
      console.log("\nConnection closed!");
    });
  }

  main();
  ```
</CodeGroup>

The proxy automatically logs audio, transcripts, and metadata to the specified project. Pass an experiment ID or span slug to log to a specific location.

The OpenAI Realtime API uses different event names for output depending on the modality:

* Text output: `response.output_text.delta` and `response.output_text.done`
* Audio output: `response.output_audio.delta` and `response.output_audio.done`
* Audio transcripts: `response.output_audio_transcript.delta` and `response.output_audio_transcript.done`

### Compress audio

To reduce storage costs, enable audio compression by setting the `x-bt-compress-audio` header to `true` or `1`:

<CodeGroup dropdown>
  ```typescript theme={"theme":{"light":"github-light","dark":"github-dark-dimmed"}}
  import { OpenAIRealtimeWS } from "openai/realtime/ws";

  async function main() {
    const projectId = "your-project-id"; // Replace with your project ID

    const rt = new OpenAIRealtimeWS(
      {
        model: "gpt-realtime",
        options: {
          headers: {
            "x-bt-parent": `project_id:${projectId}`,
            "x-bt-compress-audio": "true",
          },
        },
      },
      {
        apiKey: process.env.BRAINTRUST_API_KEY,
        baseURL: "https://braintrustproxy.com/v1",
      },
    );
  }

  main();
  ```
</CodeGroup>

When enabled, the proxy compresses audio using MP3 encoding before logging it to Braintrust to significantly reduce storage requirements.

### Browser or Cloudflare workers

For browser and Cloudflare Workers environments, use `OpenAIRealtimeWebSocket`:

<CodeGroup dropdown>
  ```typescript theme={"theme":{"light":"github-light","dark":"github-dark-dimmed"}}
  import { OpenAIRealtimeWebSocket } from "openai/realtime/websocket";

  const rt = new OpenAIRealtimeWebSocket(
    {
      model: "gpt-realtime",
    },
    {
      apiKey: process.env.BRAINTRUST_API_KEY,
      baseURL: "https://braintrustproxy.com/v1",
    },
  );

  rt.socket.addEventListener("open", () => {
    console.log("Connection opened!");

    rt.send({
      type: "session.update",
      session: {
        output_modalities: ["text"], // or ["audio"]
        model: "gpt-realtime",
        type: "realtime",
      },
    });

    rt.send({
      type: "conversation.item.create",
      item: {
        type: "message",
        role: "user",
        content: [{ type: "input_text", text: "Say a couple paragraphs!" }],
      },
    });

    rt.send({ type: "response.create" });
  });

  rt.on("error", (err) => {
    console.error("Error:", err);
  });

  rt.on("response.output_text.delta", (event) => {
    console.log(event.delta);
  });

  rt.on("response.done", () => rt.close());

  rt.socket.addEventListener("close", () => {
    console.log("\nConnection closed!");
  });
  ```
</CodeGroup>

### Temporary credentials for realtime

For frontend or mobile applications, use temporary credentials to avoid exposing your API key. Pass the temporary credential as the `apiKey`:

<CodeGroup dropdown>
  ```typescript theme={"theme":{"light":"github-light","dark":"github-dark-dimmed"}}
  import { OpenAIRealtimeWebSocket } from "openai/realtime/websocket";

  async function main() {
    const tempCredential = await fetchTempCredentialFromBackend(); // Replace with your backend call

    const rt = new OpenAIRealtimeWebSocket(
      {
        model: "gpt-realtime",
      },
      {
        apiKey: tempCredential,
        baseURL: "https://braintrustproxy.com/v1",
      },
    );

    rt.socket.addEventListener("open", () => {
      console.log("Connection opened!");

      rt.send({
        type: "session.update",
        session: {
          output_modalities: ["text"], // or ["audio"]
          model: "gpt-realtime",
          type: "realtime",
        },
      });

      rt.send({
        type: "conversation.item.create",
        item: {
          type: "message",
          role: "user",
          content: [{ type: "input_text", text: "Say hello!" }],
        },
      });

      rt.send({ type: "response.create" });
    });

    rt.on("error", (err) => {
      console.error("Error:", err);
    });

    rt.on("response.output_text.delta", (event) => {
      console.log(event.delta);
    });

    rt.on("response.done", () => rt.close());

    rt.socket.addEventListener("close", () => {
      console.log("\nConnection closed!");
    });
  }

  declare function fetchTempCredentialFromBackend(): Promise<string>;

  main();
  ```
</CodeGroup>

## Create temporary credentials

A **temporary credential** converts your Braintrust API key (or model provider API key) to a time-limited credential that can be safely shared with end users.

* Temporary credentials can carry additional information to limit access to a particular model and enable logging to Braintrust.
* They can be used in the `Authorization` header anywhere you'd use a Braintrust API key or a model provider API key.

Use temporary credentials if you'd like your frontend or mobile app to send AI requests to the proxy directly, minimizing latency without exposing your API keys to end users.

### Issue temporary credentials

Call the `/credentials` endpoint from a privileged location, such as your app's backend, to issue temporary credentials. The temporary credential will be allowed to make requests on behalf of the Braintrust API key (or model provider API key) provided in the `Authorization` header.

The body should specify the restrictions to be applied to the temporary credentials as a JSON object. If the `logging` key is present, the proxy will log to Braintrust any requests made with this temporary credential.

The following example grants access to `gpt-4o-realtime-preview-2024-10-01` for 10 minutes, logging the requests to the project named "My project":

<CodeGroup dropdown>
  ```typescript theme={"theme":{"light":"github-light","dark":"github-dark-dimmed"}}
  const PROXY_URL =
    process.env.BRAINTRUST_PROXY_URL || "https://braintrustproxy.com/v1";
  const BRAINTRUST_API_KEY = process.env.BRAINTRUST_API_KEY;

  async function main() {
    const response = await fetch(`${PROXY_URL}/credentials`, {
      method: "POST",
      headers: {
        "Content-Type": "application/json",
        Authorization: `Bearer ${BRAINTRUST_API_KEY}`,
      },
      body: JSON.stringify({
        model: "gpt-4o-realtime-preview-2024-10-01", // Leave undefined to allow all models
        ttl_seconds: 60 * 10, // 10 minutes
        logging: {
          project_name: "My project", // Replace with your project name
        },
      }),
      cache: "no-store",
    });

    if (!response.ok) {
      const error = await response.text();
      throw new Error(`Failed to request temporary credentials: ${error}`);
    }

    const { key: tempCredential } = await response.json();
    console.log(`Authorization: Bearer ${tempCredential}`);
  }

  main();
  ```

  ```python theme={"theme":{"light":"github-light","dark":"github-dark-dimmed"}}
  import os
  import requests

  PROXY_URL = os.getenv("BRAINTRUST_PROXY_URL", "https://braintrustproxy.com/v1")
  BRAINTRUST_API_KEY = os.getenv("BRAINTRUST_API_KEY")

  def main():
      response = requests.post(
          f"{PROXY_URL}/credentials",
          headers={
              "Authorization": f"Bearer {BRAINTRUST_API_KEY}",
          },
          json={
              "model": "gpt-4o-realtime-preview-2024-10-01",  # Leave unset to allow all models
              "ttl_seconds": 60 * 10,  # 10 minutes
              "logging": {
                  "project_name": "My project",  # Replace with your project name
              },
          },
      )

      if response.status_code != 200:
          raise Exception(f"Failed to request temporary credentials: {response.text}")

      temp_credential = response.json().get("key")
      print(f"Authorization: Bearer {temp_credential}")

  if __name__ == "__main__":
      main()
  ```

  ```bash theme={"theme":{"light":"github-light","dark":"github-dark-dimmed"}}
  curl -X POST "${BRAINTRUST_PROXY_URL:-https://braintrustproxy.com/v1}/credentials" \
    -H "Content-Type: application/json" \
    -H "Authorization: Bearer ${BRAINTRUST_API_KEY}" \
    --data '{
      "model": "gpt-4o-realtime-preview-2024-10-01",
      "ttl_seconds": 600,
      "logging": {
        "project_name": "My project"
      }
    }'
  ```
</CodeGroup>

<Tip>
  Generate temporary credentials using the [web form](https://www.braintrust.dev/blog/realtime-api#generate-temporary-credentials) for quick testing.
</Tip>

### Inspect temporary credentials

Temporary credentials are formatted as [JSON Web Tokens (JWT)](https://jwt.io/introduction). Inspect the JWT's payload using a library such as [`jsonwebtoken`](https://www.npmjs.com/package/jsonwebtoken) or a web-based tool like [JWT.io](https://jwt.io/) to determine the expiration time and granted models:

<CodeGroup dropdown>
  ```typescript theme={"theme":{"light":"github-light","dark":"github-dark-dimmed"}}
  import { decode as jwtDecode } from "jsonwebtoken";

  const tempCredential = "<your temporary credential>";
  const payload = jwtDecode(tempCredential, { complete: false, json: true });
  // Example output:
  // {
  //   "aud": "braintrust_proxy",
  //   "bt": {
  //     "model": "gpt-4o",
  //     "secret": "nCCxgkBoyy/zyOJlikuHILBMoK78bHFosEzy03SjJF0=",
  //     "logging": {
  //       "project_name": "My project"
  //     }
  //   },
  //   "exp": 1729928077,
  //   "iat": 1729927977,
  //   "iss": "braintrust_proxy",
  //   "jti": "bt_tmp:331278af-937c-4f97-9d42-42c83631001a"
  // }
  console.log(JSON.stringify(payload, null, 2));
  ```
</CodeGroup>

<Note>
  Do not modify the JWT payload. This will invalidate the signature. Instead, issue a new temporary credential using the `/credentials` endpoint.
</Note>

## Use PDF input

The proxy extends the OpenAI API to support PDF input. Pass PDF URLs or base64-encoded PDFs with MIME type `application/pdf`:

<CodeGroup dropdown>
  ```bash theme={"theme":{"light":"github-light","dark":"github-dark-dimmed"}}
  curl https://api.braintrust.dev/v1/proxy/auto \
    -H "Content-Type: application/json" \
    -H "Authorization: Bearer $BRAINTRUST_API_KEY" \
    -d '{
      "model": "gpt-4o",
      "messages": [
        {"role": "user", "content": [
          {"type": "text", "text": "Extract the text from this PDF."},
          {"type": "image_url", "image_url": {"url": "https://example.com/document.pdf"}}
        ]}
      ]
    }'
  ```
</CodeGroup>

For base64-encoded PDFs, use `data:application/pdf;base64,<BASE64_DATA>` as the URL.

## Specify an organization

If you're part of multiple organizations, specify which to use with the `x-bt-org-name` header:

<CodeGroup dropdown>
  ```typescript theme={"theme":{"light":"github-light","dark":"github-dark-dimmed"}}
  import { OpenAI } from "openai";

  const client = new OpenAI({
    baseURL: "https://api.braintrust.dev/v1/proxy",
    defaultHeaders: {
      "x-bt-org-name": "Acme Inc", // Replace with your organization name
    },
    apiKey: process.env.BRAINTRUST_API_KEY,
  });
  ```

  ```python theme={"theme":{"light":"github-light","dark":"github-dark-dimmed"}}
  import os
  from openai import OpenAI

  client = OpenAI(
      base_url="https://api.braintrust.dev/v1/proxy",
      default_headers={"x-bt-org-name": "Acme Inc"},  # Replace with your organization name
      api_key=os.getenv("BRAINTRUST_API_KEY"),
  )
  ```
</CodeGroup>

## Advanced configuration

Configure proxy behavior with these headers:

* **x-bt-use-cache**: `auto | always | never` - Control caching behavior
* **x-bt-cache-ttl**: Seconds (max 604800) - Set cache TTL
* **x-bt-use-creds-cache**: `auto | always | never` - Control credentials caching (useful when rapidly updating credentials)
* **x-bt-org-name**: Organization name - Specify organization for multi-org users
* **x-bt-endpoint-name**: Endpoint name - Use a specific configured endpoint
* **x-bt-parent**: Project/experiment/span - Enable logging to Braintrust
* **x-bt-compress-audio**: `true | false` - Enable audio compression for realtime sessions

## Monitor proxy usage

Track proxy usage across your organization:

1. Create a project for proxy logs.
2. Enable logging by setting the `x-bt-parent` header when calling the proxy (see [Enable logging](#enable-logging)).
3. View logs in the <Icon icon="activity" /> **Logs** page.
4. Create dashboards to track usage, costs, and errors.

The proxy response includes the `x-bt-used-endpoint` header, which specifies which of your configured providers was used to complete the request.

## Self-hosting

Self-hosted Braintrust deployments include a built-in proxy that runs in your environment.

To configure your proxy URLs, see [Configure API URLs](/admin/organizations#configure-api-urls-self-hosted) in organization settings. For complete deployment instructions, see [Self-hosting](/admin/self-hosting).

## Integration with Braintrust

Several features in Braintrust are powered by the proxy. For example, when you create a playground, the proxy handles running the LLM calls. Similarly, if you create a prompt, when you preview the prompt's results, the proxy is used to run the LLM. However, the proxy is *not* required when you:

* Run evaluations in your code.
* Load prompts to run in your code.
* Log traces to Braintrust.

If you'd like to use it in your code to help with caching, secrets management, and other features, follow the instructions above to set it as the base URL in your OpenAI client.

## Open source

The AI proxy is open source. View the code on [GitHub](https://github.com/braintrustdata/braintrust-proxy).

## Next steps

* [Deploy prompts](/deploy/prompts) to call versioned prompts through the proxy
* [Evaluate reasoning models](/evaluate/reasoning) with standardized reasoning parameters
* [Monitor deployments](/deploy/monitor) to track production performance
* [Manage environments](/deploy/environments) to separate dev and production
* [Manage organizations](/admin/organizations) to configure AI providers
* [Manage projects](/admin/projects) for project-level provider configuration
