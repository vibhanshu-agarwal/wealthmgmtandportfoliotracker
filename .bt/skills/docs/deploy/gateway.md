> ## Documentation Index
> Fetch the complete documentation index at: https://braintrust.dev/docs/llms.txt
> Use this file to discover all available pages before exploring further.

# Use the Braintrust gateway

> Route requests to any AI provider through a unified LLM API

The Braintrust gateway provides a unified API to access LLM models from OpenAI, Anthropic, Google, AWS, and other providers. Point your SDKs to the gateway URL and immediately get automatic caching, observability, and multi-provider support. Use any [supported provider's SDK](/integrations/ai-providers) to call any provider's models—standardize on one SDK while accessing all available models.

<Note>
  The Braintrust-hosted gateway (`https://gateway.braintrust.dev`) is currently in beta and free to use. It is designed for production workloads, and uptime is tracked on the [Braintrust status page](https://status.braintrust.dev/) under AI Gateway. Pricing will be announced before general availability.
</Note>

## Quickstart

<Steps>
  <Step title="Get a Braintrust API key">
    Create a [Braintrust API key](/admin/organizations#manage-api-keys) and set it as an environment variable:

    ```bash theme={"theme":{"light":"github-light","dark":"github-dark-dimmed"}}
    export BRAINTRUST_API_KEY="<your-braintrust-api-key>"
    ```
  </Step>

  <Step title="Add your AI provider key">
    Add your provider API key in Braintrust so the gateway can call it on your behalf — you won't need to set your provider key locally.

    You can do this at the [organization level](/admin/organizations#configure-ai-providers) (available across all projects) or the [project level](/admin/projects#configure-ai-providers) (overrides organization defaults).
  </Step>

  <Step title="Point your SDK at the gateway">
    Change your SDK's base URL to `https://gateway.braintrust.dev` and pass your Braintrust API key as the API key. That's it — no other code changes needed.

    <Note>
      The [Braintrust gateway](/deploy/gateway) uses URL (`https://gateway.braintrust.dev`), regardless of whether your organization is on the US or EU [data plane](/admin/organizations#data-plane-region). For low latency, requests are routed to the nearest gateway instance: `us-east-1` (N. Virginia), `eu-west-1` (Ireland), `us-west-2` (Oregon), or `ap-southeast-1` (Singapore). Then, when [logging is enabled](/deploy/gateway#enable-logging), data is logged to your organization's configured data plane.
    </Note>

    <Tabs>
      <Tab title="OpenAI" icon="https://img.logo.dev/openai.com?token=pk_BdcHD9e5SCW3j1rnJkNyMQ">
        Set the base URL to `https://gateway.braintrust.dev`.

        <CodeGroup dropdown>
          ```typescript theme={"theme":{"light":"github-light","dark":"github-dark-dimmed"}}
          import { OpenAI } from "openai";

          const client = new OpenAI({
            baseURL: "https://gateway.braintrust.dev",
            apiKey: process.env.BRAINTRUST_API_KEY,
          });

          async function main() {
            const response = await client.responses.create({
              model: "gpt-5-mini",
              input: [
                { role: "user", content: "Say hello!" },
              ],
            });

            console.log(response.output_text);
          }

          main();
          ```

          ```python theme={"theme":{"light":"github-light","dark":"github-dark-dimmed"}}
          import os

          from openai import OpenAI

          client = OpenAI(
              base_url="https://gateway.braintrust.dev",
              api_key=os.environ["BRAINTRUST_API_KEY"],
          )

          response = client.responses.create(
              model="gpt-5-mini",
              input=[{"role": "user", "content": "Say hello!"}],
          )

          print(response.output_text)
          ```
        </CodeGroup>
      </Tab>

      <Tab title="Anthropic" icon="https://img.logo.dev/anthropic.com?token=pk_BdcHD9e5SCW3j1rnJkNyMQ">
        Set the base URL to `https://gateway.braintrust.dev`. The `anthropic-version` and `x-api-key` headers are not required when using a Braintrust API key.

        <CodeGroup dropdown>
          ```typescript theme={"theme":{"light":"github-light","dark":"github-dark-dimmed"}}
          import Anthropic from "@anthropic-ai/sdk";

          const client = new Anthropic({
            baseURL: "https://gateway.braintrust.dev",
            apiKey: process.env.BRAINTRUST_API_KEY,
          });

          async function main() {
            const response = await client.messages.create({
              model: "claude-haiku-4-5",
              messages: [{ role: "user", content: "Say hello!" }],
            });
            console.log(response.content[0].type === "text" ? response.content[0].text : "");
          }

          main();
          ```

          ```python theme={"theme":{"light":"github-light","dark":"github-dark-dimmed"}}
          import os

          from anthropic import Anthropic

          client = Anthropic(
              base_url="https://gateway.braintrust.dev",
              api_key=os.environ["BRAINTRUST_API_KEY"],
          )

          response = client.messages.create(
              model="claude-haiku-4-5",
              messages=[{"role": "user", "content": "Say hello!"}],
          )
          print(response.content[0].text)
          ```
        </CodeGroup>
      </Tab>

      <Tab title="Gemini" icon="https://img.logo.dev/google.com?token=pk_BdcHD9e5SCW3j1rnJkNyMQ">
        Set the base URL to `https://gateway.braintrust.dev` and pass your Braintrust API key as the API key.

        <CodeGroup dropdown>
          ```typescript theme={"theme":{"light":"github-light","dark":"github-dark-dimmed"}}
          import { GoogleGenAI } from "@google/genai";

          const client = new GoogleGenAI({
            apiKey: process.env.BRAINTRUST_API_KEY,
            httpOptions: {
              baseUrl: "https://gateway.braintrust.dev",
            },
          });

          async function main() {
            const response = await client.models.generateContent({
              model: "gemini-2.5-flash",
              contents: "Say hello!",
            });
            console.log(response.text);
          }

          main();
          ```

          ```python theme={"theme":{"light":"github-light","dark":"github-dark-dimmed"}}
          import os

          from google import genai

          client = genai.Client(
              api_key=os.environ["BRAINTRUST_API_KEY"],
              http_options={"base_url": "https://gateway.braintrust.dev"},
          )

          response = client.models.generate_content(
              model="gemini-2.5-flash",
              contents="Say hello!",
          )
          print(response.text)
          ```
        </CodeGroup>
      </Tab>

      <Tab title="cURL" icon="terminal">
        Use the OpenAI-compatible endpoint directly.

        ```bash theme={"theme":{"light":"github-light","dark":"github-dark-dimmed"}}
        curl -X POST https://gateway.braintrust.dev/chat/completions \
          -H "Content-Type: application/json" \
          -H "Authorization: Bearer $BRAINTRUST_API_KEY" \
          -d '{
            "model": "gpt-5-mini",
            "messages": [{"role": "user", "content": "Say hello!"}]
          }'
        ```
      </Tab>
    </Tabs>
  </Step>
</Steps>

## Use any SDK with any provider

The gateway supports using any [supported integration's](/integrations/ai-providers) SDK to call models from any provider. This means you can standardize on one SDK across your codebase while accessing all available models.

<Tabs>
  <Tab title="OpenAI SDK → Claude" icon="https://img.logo.dev/openai.com?token=pk_BdcHD9e5SCW3j1rnJkNyMQ">
    Use the OpenAI SDK to call Anthropic's Claude models.

    <CodeGroup dropdown>
      ```typescript theme={"theme":{"light":"github-light","dark":"github-dark-dimmed"}}
      import { OpenAI } from "openai";

      const client = new OpenAI({
        baseURL: "https://gateway.braintrust.dev",
        apiKey: process.env.BRAINTRUST_API_KEY,
      });

      // Call Anthropic's Claude using the OpenAI SDK
      const response = await client.responses.create({
        model: "claude-sonnet-4-5",
        input: [{ role: "user", content: "Hello!" }],
      });
      console.log(response.output_text);
      ```

      ```python theme={"theme":{"light":"github-light","dark":"github-dark-dimmed"}}
      import os
      from openai import OpenAI

      client = OpenAI(
          base_url="https://gateway.braintrust.dev",
          api_key=os.environ["BRAINTRUST_API_KEY"],
      )

      # Call Anthropic's Claude using the OpenAI SDK
      response = client.responses.create(
          model="claude-sonnet-4-5",
          input=[{"role": "user", "content": "Hello!"}],
      )
      print(response.output_text)
      ```
    </CodeGroup>
  </Tab>

  <Tab title="Anthropic SDK → Gemini" icon="https://img.logo.dev/anthropic.com?token=pk_BdcHD9e5SCW3j1rnJkNyMQ">
    Use the Anthropic SDK to call Google's Gemini models.

    <CodeGroup dropdown>
      ```typescript theme={"theme":{"light":"github-light","dark":"github-dark-dimmed"}}
      import Anthropic from "@anthropic-ai/sdk";

      const client = new Anthropic({
        baseURL: "https://gateway.braintrust.dev",
        apiKey: process.env.BRAINTRUST_API_KEY,
      });

      // Call Google's Gemini using the Anthropic SDK
      const response = await client.messages.create({
        model: "gemini-2.5-flash",
        messages: [{ role: "user", content: "Hello!" }],
      });
      console.log(response.content[0].type === "text" ? response.content[0].text : "");
      ```

      ```python theme={"theme":{"light":"github-light","dark":"github-dark-dimmed"}}
      import os
      from anthropic import Anthropic

      client = Anthropic(
          base_url="https://gateway.braintrust.dev",
          api_key=os.environ["BRAINTRUST_API_KEY"],
      )

      # Call Google's Gemini using the Anthropic SDK
      response = client.messages.create(
          model="gemini-2.5-flash",
          messages=[{"role": "user", "content": "Hello!"}],
      )
      print(response.content[0].text)
      ```
    </CodeGroup>
  </Tab>

  <Tab title="Gemini SDK → GPT" icon="https://img.logo.dev/google.com?token=pk_BdcHD9e5SCW3j1rnJkNyMQ">
    Use the Gemini SDK to call OpenAI's GPT models.

    <CodeGroup dropdown>
      ```typescript theme={"theme":{"light":"github-light","dark":"github-dark-dimmed"}}
      import { GoogleGenAI } from "@google/genai";

      const client = new GoogleGenAI({
        apiKey: process.env.BRAINTRUST_API_KEY,
        httpOptions: {
          baseUrl: "https://gateway.braintrust.dev",
        },
      });

      // Call OpenAI's GPT using the Gemini SDK
      const response = await client.models.generateContent({
        model: "gpt-5-mini",
        contents: "Hello!",
      });
      console.log(response.text);
      ```

      ```python theme={"theme":{"light":"github-light","dark":"github-dark-dimmed"}}
      import os
      from google import genai

      client = genai.Client(
          api_key=os.environ["BRAINTRUST_API_KEY"],
          http_options={"base_url": "https://gateway.braintrust.dev"},
      )

      # Call OpenAI's GPT using the Gemini SDK
      response = client.models.generate_content(
          model="gpt-5-mini",
          contents="Hello!",
      )
      print(response.text)
      ```
    </CodeGroup>
  </Tab>
</Tabs>

## Configure API keys

Configure two things for gateway requests: a Braintrust auth token to call the gateway, and AI provider keys that the gateway uses to run model requests.

<Steps>
  <Step title="Create a Braintrust auth token">
    Set `BRAINTRUST_API_KEY` to a Braintrust auth token and pass it in `Authorization: Bearer ...` when calling the gateway. This value can be either a user API key (`sk-`) or a service token (`bt-st-`).

    * Use a user API key (`sk-`) for personal development workflows tied to your user account. Create one in [organization API keys](/admin/organizations#manage-api-keys).
    * Use a service token (`bt-st-`) for CI/CD pipelines, backend services, and shared automation. Create one in [service tokens](/admin/organizations#create-service-tokens).
  </Step>

  <Step title="Add AI provider keys">
    * **Organization-level AI providers**

      Add provider API keys in your [organization settings](/admin/organizations#configure-ai-providers) under <Icon icon="sparkle" /> **AI providers**. These keys are available across all projects and act as the default credentials for gateway requests.

    * **Project-level AI providers**

      Configure provider API keys at the [project level](/admin/projects#configure-ai-providers) when a project needs separate billing, usage isolation, or different credentials. Project-level keys override organization defaults for requests made in that project's context. You can also set up providers inline when running playgrounds or prompts. See [Use project-level AI providers](#use-project-level-ai-providers) for how to specify a project when making gateway requests.
  </Step>
</Steps>

### Supported providers

The gateway supports a large and fast-moving set of models across OpenAI-compatible, Anthropic, Google, and AWS Bedrock APIs. Browse the full list on the [supported models](/deploy/supported-models) page.

If you need a model that is not listed, [let us know](mailto:support@braintrust.dev).

### Custom providers

Add custom models or endpoints to use with the gateway. Custom providers support self-hosted models, fine-tuned models, and proprietary AI services.

See [Custom providers](/integrations/ai-providers/custom) for setup instructions and configuration options.

Once you've configured a custom provider, call it immediately through the same OpenAI-compatible client using your custom model name.

<CodeGroup dropdown>
  ```typescript theme={"theme":{"light":"github-light","dark":"github-dark-dimmed"}}
  import { OpenAI } from "openai";

  const client = new OpenAI({
    baseURL: "https://gateway.braintrust.dev",
    apiKey: process.env.BRAINTRUST_API_KEY,
  });

  const response = await client.responses.create({
    model: "my-custom-model",
    input: [{ role: "user", content: "Write a haiku about gateways." }],
  });
  console.log(response.output_text);
  ```

  ```python theme={"theme":{"light":"github-light","dark":"github-dark-dimmed"}}
  import os
  from openai import OpenAI

  client = OpenAI(
      base_url="https://gateway.braintrust.dev",
      api_key=os.getenv("BRAINTRUST_API_KEY"),
  )

  response = client.responses.create(
      model="my-custom-model",
      input=[{"role": "user", "content": "Write a haiku about gateways."}],
  )
  print(response.output_text)
  ```
</CodeGroup>

## Enable logging

Log gateway requests as part of a distributed trace by setting `x-bt-parent` with `span.export()`.

<CodeGroup dropdown>
  ```typescript theme={"theme":{"light":"github-light","dark":"github-dark-dimmed"}}
  import { initLogger } from "braintrust";
  import { OpenAI } from "openai";

  const logger = initLogger({ projectName: "My Project" });

  await logger.traced(async (span) => {
    const client = new OpenAI({
      baseURL: "https://gateway.braintrust.dev",
      apiKey: process.env.BRAINTRUST_API_KEY,
    });

    const response = await client.responses.create(
      {
        model: "gpt-5-mini",
        input: [{ role: "user", content: "Say hello!" }],
      },
      {
        headers: {
          "x-bt-parent": await span.export(),
        },
      },
    );

    console.log(response.output_text);
  });
  ```

  ```python theme={"theme":{"light":"github-light","dark":"github-dark-dimmed"}}
  import os

  from braintrust import init_logger
  from openai import OpenAI

  logger = init_logger(project="My Project")

  with logger.start_span(name="my-service-request") as span:
      client = OpenAI(
          base_url="https://gateway.braintrust.dev",
          api_key=os.environ["BRAINTRUST_API_KEY"],
      )

      response = client.responses.create(
          model="gpt-5-mini",
          input=[{"role": "user", "content": "Say hello!"}],
          extra_headers={
              "x-bt-parent": span.export(),
          },
      )

      print(response.output_text)
  ```
</CodeGroup>

This nests the gateway call under your service span so the full request path appears in one trace. See [distributed tracing](/instrument/advanced-tracing#trace-distributed-systems) for details.

When `x-bt-parent` is set, the gateway returns an `x-bt-span-id` response header containing the ID of the logged span. You can use this ID to attach scores or feedback to the span after the request completes—see [Update a logged span](#update-a-logged-span).

If you don't need distributed tracing, you can also set `x-bt-parent` directly with a prefix like `project_id:`, `project_name:`, or `experiment_id:`.

## Update a logged span

When `x-bt-parent` is set, the gateway returns `x-bt-span-id` in the response headers with the ID of the logged span. Pass this ID to `updateSpan` to attach scores, metadata, or feedback to the span after the request completes — for example, after collecting user feedback or running an offline scorer.

<CodeGroup dropdown>
  ```typescript theme={"theme":{"light":"github-light","dark":"github-dark-dimmed"}}
  import { initLogger } from "braintrust";
  import { OpenAI } from "openai";

  const logger = initLogger({ projectName: "My Project" });
  const client = new OpenAI({
    baseURL: "https://gateway.braintrust.dev",
    apiKey: process.env.BRAINTRUST_API_KEY,
  });

  await logger.traced(async (span) => {
    const { data, response: raw } = await client.responses
      .create(
        {
          model: "gpt-5-mini",
          input: [{ role: "user", content: "Say hello!" }],
        },
        {
          headers: { "x-bt-parent": await span.export() },
        },
      )
      .withResponse();

    const spanId = raw.headers.get("x-bt-span-id");
    console.log(data.output_text);

    // Update the logged gateway span with a score
    if (spanId) {
      logger.updateSpan({ id: spanId, scores: { quality: 1.0 } });
    }
  });
  ```

  ```python theme={"theme":{"light":"github-light","dark":"github-dark-dimmed"}}
  import os

  from braintrust import init_logger
  from openai import OpenAI

  logger = init_logger(project="My Project")
  client = OpenAI(
      base_url="https://gateway.braintrust.dev",
      api_key=os.environ["BRAINTRUST_API_KEY"],
  )

  with logger.start_span(name="my-service-request") as span:
      raw = client.responses.with_raw_response.create(
          model="gpt-5-mini",
          input=[{"role": "user", "content": "Say hello!"}],
          extra_headers={"x-bt-parent": span.export()},
      )
      response = raw.parse()
      span_id = raw.headers.get("x-bt-span-id")
      print(response.output_text)

  # Later, update the span with a score
  if span_id:
      logger.update_span(id=span_id, scores={"quality": 1.0})
  ```
</CodeGroup>

See [Update spans](/instrument/advanced-tracing#update-spans) for more details on updating spans asynchronously.

## Enable caching

The gateway automatically caches results and reuses them when possible. This is especially useful when developing and frequently re-running or evaluating the same prompts.

<Note>
  This section describes **gateway response caching** (controlled by `x-bt-use-cache`, `x-bt-cache-ttl`, and `Cache-Control`). This is separate from **provider-side caching** features such as prompt caching (for example, Anthropic's `cache_control`) or any caching a provider may perform automatically.

  * If the gateway serves a response from its cache, it returns the cached response **without contacting the provider**, so provider-side caching does not apply for that request.
  * If the gateway forwards the request to the provider, you can still use provider-specific caching parameters/headers. The gateway does not translate provider caching settings across providers.

  If you want to rely on provider-side caching while disabling gateway response caching, set `x-bt-use-cache: never`.
</Note>

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
    baseURL: "https://gateway.braintrust.dev",
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
      base_url="https://gateway.braintrust.dev",
      default_headers={"x-bt-use-cache": "always"},
      api_key=os.getenv("BRAINTRUST_API_KEY"),
  )
  ```
</CodeGroup>

The response includes `x-bt-cached: HIT` or `MISS` to indicate cache status.

### Cache TTL

By default, cached results expire after 1 week. Set the TTL for individual requests by passing the `x-bt-cache-ttl` header. The TTL is specified in seconds and must be between 1 and 604800 (7 days).

### Cache control

The gateway supports a limited set of [Cache-Control](https://developer.mozilla.org/en-US/docs/Web/HTTP/Headers/Cache-Control) directives:

* To bypass the cache, set the `Cache-Control` header to `no-cache, no-store`. This is semantically equivalent to setting the `x-bt-use-cache` header to `never`.
* To force a fresh request, set the `Cache-Control` header to `no-cache`. Without the `no-store` directive, the response will be cached for subsequent requests.
* To request a cached response with a maximum age, set the `Cache-Control` header to `max-age=<seconds>`. If the cached data is older than the specified age, the cache will be bypassed and a new response will be generated. Combine this with `no-store` to bypass the cache for a request without overwriting the current cached response.

When cache control directives conflict with the `x-bt-use-cache` header, the cache control directives take precedence.

The gateway returns the `x-bt-cached` header in the response with `HIT` or `MISS` to indicate whether the response was served from the cache, the `Age` header to indicate the age of the cached response, and the `Cache-Control` header with the `max-age` directive to return the TTL of the cached response.

For example, to set the cache mode to `always` with a TTL of 2 days:

<CodeGroup dropdown>
  ```typescript theme={"theme":{"light":"github-light","dark":"github-dark-dimmed"}}
  import { OpenAI } from "openai";

  const client = new OpenAI({
    baseURL: "https://gateway.braintrust.dev",
    defaultHeaders: {
      "x-bt-use-cache": "always",
      "Cache-Control": "max-age=172800",
    },
    apiKey: process.env.BRAINTRUST_API_KEY,
  });

  async function main() {
    const response = await client.chat.completions.create({
      model: "gpt-5-mini",
      messages: [{ role: "user", content: "Say hello!" }],
    });
    console.log(response.choices[0].message.content);
  }

  main();
  ```

  ```python theme={"theme":{"light":"github-light","dark":"github-dark-dimmed"}}
  import os

  from openai import OpenAI

  client = OpenAI(
      base_url="https://gateway.braintrust.dev",
      default_headers={"x-bt-use-cache": "always", "Cache-Control": "max-age=172800"},
      api_key=os.environ["BRAINTRUST_API_KEY"],
  )

  response = client.chat.completions.create(
      model="gpt-5-mini",
      messages=[{"role": "user", "content": "Say hello!"}],
  )
  print(response.choices[0].message.content)
  ```

  ```bash theme={"theme":{"light":"github-light","dark":"github-dark-dimmed"}}
  time curl -i https://gateway.braintrust.dev/chat/completions \
    -H "Content-Type: application/json" \
    -H "x-bt-use-cache: always" \
    -H "Cache-Control: max-age=172800" \
    -d '{
      "model": "gpt-5-mini",
      "messages": [{"role": "user", "content": "Say hello!"}]
    }' \
    -H "Authorization: Bearer $BRAINTRUST_API_KEY" \
    --compress
  ```
</CodeGroup>

### Cache encryption

The gateway uses [AES-GCM](https://en.wikipedia.org/wiki/Galois/Counter_Mode) to encrypt the cache, using a key derived from your API key. Results are cached for 1 week unless otherwise specified in request headers.

This design ensures that the cache is only accessible to you. Braintrust cannot see your data and does not store or log API keys.

<Note>
  Because the cache's encryption key is your API key, cached results are scoped to an individual user. Braintrust customers can opt into sharing cached results across users within their organization.
</Note>

## Specify an organization

If you're part of multiple organizations, specify which to use with the `x-bt-org-name` header:

<CodeGroup dropdown>
  ```typescript theme={"theme":{"light":"github-light","dark":"github-dark-dimmed"}}
  import { OpenAI } from "openai";

  const client = new OpenAI({
    baseURL: "https://gateway.braintrust.dev",
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
      base_url="https://gateway.braintrust.dev",
      default_headers={"x-bt-org-name": "Acme Inc"},  # Replace with your organization name
      api_key=os.getenv("BRAINTRUST_API_KEY"),
  )
  ```
</CodeGroup>

## Use project-level AI providers

When making gateway requests, your Braintrust API key identifies who is making the request. By default, the gateway uses your organization-level AI provider credentials. To use AI provider credentials configured at the project level, specify the project with the `x-bt-project-id` header. See [Configure API keys](#configure-api-keys) for where to set organization-level vs project-level provider keys.

To find your project ID, navigate to your project's configuration page and use the **Copy Project ID** button at the bottom of the page.

<CodeGroup dropdown>
  ```typescript theme={"theme":{"light":"github-light","dark":"github-dark-dimmed"}}
  import { OpenAI } from "openai";

  const client = new OpenAI({
    baseURL: "https://gateway.braintrust.dev",
    defaultHeaders: {
      "x-bt-project-id": "PROJECT_ID", // Replace with your project ID
    },
    apiKey: process.env.BRAINTRUST_API_KEY,
  });
  ```

  ```python theme={"theme":{"light":"github-light","dark":"github-dark-dimmed"}}
  import os
  from openai import OpenAI

  client = OpenAI(
      base_url="https://gateway.braintrust.dev",
      default_headers={"x-bt-project-id": "PROJECT_ID"},  # Replace with your project ID
      api_key=os.getenv("BRAINTRUST_API_KEY"),
  )
  ```
</CodeGroup>

<Note>
  If you're part of multiple organizations, you may also need to specify the organization using `x-bt-org-name` (see [Specify an organization](#specify-an-organization)). You can send both headers in the same request:
</Note>

<CodeGroup dropdown>
  ```typescript theme={"theme":{"light":"github-light","dark":"github-dark-dimmed"}}
  import { OpenAI } from "openai";

  const client = new OpenAI({
    baseURL: "https://gateway.braintrust.dev",
    defaultHeaders: {
      "x-bt-project-id": "PROJECT_ID", // Replace with your project ID
      "x-bt-org-name": "Acme Inc", // Replace with your organization name
    },
    apiKey: process.env.BRAINTRUST_API_KEY,
  });
  ```

  ```python theme={"theme":{"light":"github-light","dark":"github-dark-dimmed"}}
  import os
  from openai import OpenAI

  client = OpenAI(
      base_url="https://gateway.braintrust.dev",
      default_headers={
          "x-bt-project-id": "PROJECT_ID",  # Replace with your project ID
          "x-bt-org-name": "Acme Inc",  # Replace with your organization name
      },
      api_key=os.getenv("BRAINTRUST_API_KEY"),
  )
  ```
</CodeGroup>

This ensures the gateway uses the AI provider credentials configured for the specified project rather than the organization defaults.

## Advanced configuration

Configure gateway behavior with these request headers:

* **x-bt-use-cache**: `auto | always | never` - Control caching behavior
* **x-bt-cache-ttl**: Seconds (max 604800) - Set cache TTL
* **x-bt-org-name**: Organization name - Specify organization for multi-org users
* **x-bt-project-id**: Project ID - Use project-level AI provider credentials
* **x-bt-endpoint-name**: Endpoint name - Use a specific configured endpoint
* **x-bt-parent**: Project/experiment/span - Enable logging to Braintrust

The gateway returns these response headers:

* **x-bt-span-id**: ID of the logged span - returned when `x-bt-parent` is set; use with `updateSpan` to attach scores or feedback
* **x-bt-cached**: `HIT | MISS` - Indicates whether the response was served from cache
* **x-bt-used-endpoint**: The configured provider endpoint used to complete the request

## Monitor gateway usage

Track gateway usage across your organization:

1. Create a project for gateway logs.
2. Enable logging by setting the `x-bt-parent` header when calling the gateway (see [Enable logging](#enable-logging)).
3. View logs in the <Icon icon="activity" /> **Logs** page.
4. Create dashboards to track usage, costs, and errors.

The gateway response includes the `x-bt-used-endpoint` header, which specifies which of your configured providers was used to complete the request.

## Migrate from the AI proxy

If you're currently using the [AI proxy](/deploy/ai-proxy), migrating to the gateway requires only a URL change. All headers, features, and SDK patterns are fully compatible.

1. Update the base URL from `https://api.braintrust.dev/v1/proxy` to `https://gateway.braintrust.dev`.
2. No other code changes are required.

<CodeGroup dropdown>
  ```typescript theme={"theme":{"light":"github-light","dark":"github-dark-dimmed"}}
  import { OpenAI } from "openai";

  const client = new OpenAI({
    // Before:
    // baseURL: "https://api.braintrust.dev/v1/proxy",
    // After:
    baseURL: "https://gateway.braintrust.dev",
    apiKey: process.env.BRAINTRUST_API_KEY,
  });
  ```

  ```python theme={"theme":{"light":"github-light","dark":"github-dark-dimmed"}}
  import os
  from openai import OpenAI

  client = OpenAI(
      # Before:
      # base_url="https://api.braintrust.dev/v1/proxy",
      # After:
      base_url="https://gateway.braintrust.dev",
      api_key=os.environ["BRAINTRUST_API_KEY"],
  )
  ```
</CodeGroup>

## Next steps

* [Deploy overview](/deploy) for more deployment options
* [Deploy prompts](/deploy/prompts) to call versioned prompts through the gateway
* [Deploy functions](/deploy/functions) to deploy tools, scorers, and workflows
* [Monitor deployments](/deploy/monitor) to track production performance and errors
* [Manage environments](/deploy/environments) to separate dev, staging, and production
