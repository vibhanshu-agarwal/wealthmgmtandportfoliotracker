> ## Documentation Index
> Fetch the complete documentation index at: https://braintrust.dev/docs/llms.txt
> Use this file to discover all available pages before exploring further.

# Trace LLM calls

> Trace LLM calls from AI providers and frameworks

Braintrust traces your LLM calls with auto-instrumentation. In most languages, you enable tracing once at startup and every request to a supported AI provider or framework is logged — inputs, outputs, model parameters, latency, token usage, and costs — with no per-call code changes.

For languages that don't yet support auto-instrumentation, you can [wrap each client instance](#wrap-functions) to get the same coverage.

The examples on this page use OpenAI, but Braintrust supports [many providers and frameworks](#supported-libraries).

<Tip>
  Braintrust's [CLI](/reference/cli/quickstart) can help you instrument your code.
</Tip>

## Auto-instrumentation

Auto-instrumentation patches supported AI libraries at startup so every LLM call is captured without wrapping individual clients. This is the recommended way to set up tracing.

The steps below walk you through installing dependencies, setting environment variables, and running a traced LLM call end-to-end.

<Tip>
  If you're using Java or .NET, or if auto-instrumentation isn't working in your environment, try [wrap functions](#wrap-functions) instead.
</Tip>

<Tabs>
  <Tab title="TypeScript">
    Auto-instrumentation in TypeScript uses a startup hook that patches supported AI libraries automatically.

    <Steps>
      <Step title="Install the dependencies">
        ```bash theme={"theme":{"light":"github-light","dark":"github-dark-dimmed"}}
        npm install braintrust openai
        ```
      </Step>

      <Step title="Set your environment variables">
        ```bash theme={"theme":{"light":"github-light","dark":"github-dark-dimmed"}}
        export BRAINTRUST_API_KEY="your-api-key"
        export OPENAI_API_KEY="your-api-key"
        ```
      </Step>

      <Step title="Trace your LLM calls">
        This example traces a single OpenAI call:

        ```typescript theme={"theme":{"light":"github-light","dark":"github-dark-dimmed"}}
        import { initLogger } from "braintrust";
        import OpenAI from "openai";

        // Call once at startup — all LLM calls are traced automatically
        initLogger({
          apiKey: process.env.BRAINTRUST_API_KEY,
          projectName: "My Project (TypeScript)",
        });

        const client = new OpenAI({ apiKey: process.env.OPENAI_API_KEY });
        const response = await client.responses.create({
          model: "gpt-5-mini",
          input: "What is the capital of France?",
        });
        ```
      </Step>

      <Step title="Run your app">
        Run with the `--import` flag to enable auto-instrumentation:

        ```bash theme={"theme":{"light":"github-light","dark":"github-dark-dimmed"}}
        node --import braintrust/hook.mjs app.js
        ```

        <Accordion title="Using a bundler?">
          If you're using a bundler (Vite, Webpack, esbuild, Rollup) or a framework that uses one (Next.js, Nuxt, SvelteKit), use the appropriate [bundler plugin](https://github.com/braintrustdata/braintrust-sdk-javascript/tree/main/js/src/auto-instrumentations/bundler) (included in Braintrust's JavaScript SDK) instead of the `--import` flag.
        </Accordion>

        <Accordion title="Node.js version requirements">
          Requires Node.js 18.19.0+ or 20.6.0+ for `--import` flag support. Check with `node --version`.
        </Accordion>
      </Step>
    </Steps>
  </Tab>

  <Tab title="Python">
    Auto-instrumentation in Python uses `auto_instrument()` to patch supported AI libraries at startup.

    <Steps>
      <Step title="Install the dependencies">
        ```bash theme={"theme":{"light":"github-light","dark":"github-dark-dimmed"}}
        pip install braintrust openai
        ```
      </Step>

      <Step title="Set your environment variables">
        ```bash theme={"theme":{"light":"github-light","dark":"github-dark-dimmed"}}
        export BRAINTRUST_API_KEY="your-api-key"
        export OPENAI_API_KEY="your-api-key"
        ```
      </Step>

      <Step title="Trace your LLM calls">
        This example traces a single OpenAI call:

        ```python theme={"theme":{"light":"github-light","dark":"github-dark-dimmed"}}
        import os
        import braintrust

        # Call once at startup — all LLM calls are traced automatically
        braintrust.auto_instrument()
        braintrust.init_logger(
            api_key=os.environ["BRAINTRUST_API_KEY"],
            project="My Project (Python)",
        )

        from openai import OpenAI

        client = OpenAI(api_key=os.environ["OPENAI_API_KEY"])
        response = client.responses.create(
            model="gpt-5-mini",
            input="What is the capital of France?",
        )
        ```

        <Accordion title="Disabling specific integrations">
          `braintrust.auto_instrument()` enables every supported Python integration by default. Disable individual integrations by passing `False` for a specific keyword.

          | Parameter          | Default | Library or framework         |
          | ------------------ | ------- | ---------------------------- |
          | `openai`           | `True`  | OpenAI Python SDK            |
          | `anthropic`        | `True`  | Anthropic Python SDK         |
          | `litellm`          | `True`  | LiteLLM                      |
          | `pydantic_ai`      | `True`  | Pydantic AI                  |
          | `google_genai`     | `True`  | Google GenAI                 |
          | `openrouter`       | `True`  | OpenRouter native Python SDK |
          | `mistral`          | `True`  | Mistral Python SDK           |
          | `agno`             | `True`  | Agno                         |
          | `agentscope`       | `True`  | AgentScope                   |
          | `claude_agent_sdk` | `True`  | Claude Agent SDK             |
          | `dspy`             | `True`  | DSPy                         |
          | `adk`              | `True`  | Google ADK                   |
          | `langchain`        | `True`  | LangChain and LangGraph      |
          | `openai_agents`    | `True`  | OpenAI Agents SDK            |

          For example:

          ```python theme={"theme":{"light":"github-light","dark":"github-dark-dimmed"}}
          braintrust.auto_instrument(openrouter=False)
          ```
        </Accordion>
      </Step>

      <Step title="Run your app">
        ```bash theme={"theme":{"light":"github-light","dark":"github-dark-dimmed"}}
        python app.py
        ```
      </Step>
    </Steps>
  </Tab>

  <Tab title="Ruby">
    Auto-instrumentation in Ruby uses the `braintrust/setup` require to patch supported AI libraries on load.

    <Steps>
      <Step title="Install the dependencies">
        Add the Braintrust gem to your Gemfile, using the `braintrust/setup` require to enable auto-instrumentation on load:

        ```ruby theme={"theme":{"light":"github-light","dark":"github-dark-dimmed"}}
        # Gemfile
        gem "braintrust", require: "braintrust/setup"
        gem "ruby-openai"
        ```

        ```bash theme={"theme":{"light":"github-light","dark":"github-dark-dimmed"}}
        bundle install
        ```
      </Step>

      <Step title="Set your environment variables">
        ```bash theme={"theme":{"light":"github-light","dark":"github-dark-dimmed"}}
        export BRAINTRUST_API_KEY="your-api-key"
        export OPENAI_API_KEY="your-api-key"
        ```
      </Step>

      <Step title="Trace your LLM calls">
        This example traces a single OpenAI call:

        ```ruby theme={"theme":{"light":"github-light","dark":"github-dark-dimmed"}}
        require 'bundler/setup'
        Bundler.require

        Braintrust.init(
          api_key: ENV['BRAINTRUST_API_KEY'],
          default_project: 'My Project (Ruby)'
        )

        client = OpenAI::Client.new(access_token: ENV['OPENAI_API_KEY'])
        response = client.responses.create(
          parameters: {
            model: 'gpt-5-mini',
            input: 'What is the capital of France?'
          }
        )
        ```
      </Step>

      <Step title="Run your app">
        ```bash theme={"theme":{"light":"github-light","dark":"github-dark-dimmed"}}
        ruby app.rb
        ```
      </Step>
    </Steps>
  </Tab>

  <Tab title="Go">
    Auto-instrumentation in Go uses [Orchestrion](https://github.com/DataDog/orchestrion) for compile-time tracing. Each provider integration is installed as a separate Go module.

    <Steps>
      <Step title="Install and register the integration">
        Install the Braintrust SDK, the provider connector module, and Orchestrion:

        ```bash theme={"theme":{"light":"github-light","dark":"github-dark-dimmed"}}
        go get github.com/braintrustdata/braintrust-sdk-go
        go get github.com/braintrustdata/braintrust-sdk-go/trace/contrib/openai
        go get github.com/openai/openai-go
        go install github.com/DataDog/orchestrion@v1.6.1
        ```

        Then create `orchestrion.tool.go` in your project root to register which integrations Orchestrion should instrument:

        ```go #skip-compile theme={"theme":{"light":"github-light","dark":"github-dark-dimmed"}}
        //go:build tools

        package main

        import (
        	_ "github.com/DataDog/orchestrion"
        	_ "github.com/braintrustdata/braintrust-sdk-go/trace/contrib/openai"
        )
        ```

        <Accordion title="Other providers">
          Each tracing integration is published as its own Go module. Install the ones you need and add them to `orchestrion.tool.go`:

          ```bash theme={"theme":{"light":"github-light","dark":"github-dark-dimmed"}}
          go get github.com/braintrustdata/braintrust-sdk-go/trace/contrib/anthropic
          go get github.com/braintrustdata/braintrust-sdk-go/trace/contrib/genai
          go get github.com/braintrustdata/braintrust-sdk-go/trace/contrib/genkit
          go get github.com/braintrustdata/braintrust-sdk-go/trace/contrib/adk
          go get github.com/braintrustdata/braintrust-sdk-go/trace/contrib/cloudwego/eino
          go get github.com/braintrustdata/braintrust-sdk-go/trace/contrib/langchaingo
          go get github.com/braintrustdata/braintrust-sdk-go/trace/contrib/github.com/sashabaranov/go-openai
          ```

          Or use the `trace/contrib/all` meta-module to install and register every integration at once:

          ```bash theme={"theme":{"light":"github-light","dark":"github-dark-dimmed"}}
          go get github.com/braintrustdata/braintrust-sdk-go/trace/contrib/all
          ```

          ```go #skip-compile theme={"theme":{"light":"github-light","dark":"github-dark-dimmed"}}
          //go:build tools

          package main

          import (
          	_ "github.com/DataDog/orchestrion"
          	_ "github.com/braintrustdata/braintrust-sdk-go/trace/contrib/all"
          )
          ```
        </Accordion>
      </Step>

      <Step title="Set your environment variables">
        ```bash theme={"theme":{"light":"github-light","dark":"github-dark-dimmed"}}
        export BRAINTRUST_API_KEY="your-api-key"
        export OPENAI_API_KEY="your-api-key"
        ```
      </Step>

      <Step title="Trace your LLM calls">
        This example traces a single OpenAI call. The Go SDK sends traces to Braintrust via [OpenTelemetry](https://opentelemetry.io/), so you create a `TracerProvider` and pass it to Braintrust:

        ```go theme={"theme":{"light":"github-light","dark":"github-dark-dimmed"}}
        package main

        import (
        	"context"
        	"fmt"
        	"log"
        	"os"

        	"github.com/braintrustdata/braintrust-sdk-go"
        	"github.com/openai/openai-go"
        	"github.com/openai/openai-go/option"
        	"github.com/openai/openai-go/responses"
        	"go.opentelemetry.io/otel"
        	sdktrace "go.opentelemetry.io/otel/sdk/trace"
        )

        func main() {
        	tp := sdktrace.NewTracerProvider()
        	defer tp.Shutdown(context.Background())
        	otel.SetTracerProvider(tp)

        	_, err := braintrust.New(tp,
        		braintrust.WithProject("My Project (Go)"),
        		braintrust.WithAPIKey(os.Getenv("BRAINTRUST_API_KEY")),
        	)
        	if err != nil {
        		log.Fatal(err)
        	}

        	client := openai.NewClient(option.WithAPIKey(os.Getenv("OPENAI_API_KEY")))

        	response, err := client.Responses.New(context.Background(), responses.ResponseNewParams{
        		Model: "gpt-5-mini",
        		Input: responses.ResponseNewParamsInputUnion{OfString: openai.String("What is the capital of France?")},
        	})
        	if err != nil {
        		log.Fatal(err)
        	}

        	fmt.Println(response.OutputText())
        }
        ```
      </Step>

      <Step title="Build and run your app">
        Build with Orchestrion to enable auto-instrumentation:

        ```bash theme={"theme":{"light":"github-light","dark":"github-dark-dimmed"}}
        go mod tidy
        orchestrion go build -o myapp
        ./myapp
        ```

        <Accordion title="Enable Orchestrion via GOFLAGS">
          Instead of running `orchestrion go build`, you can set a `GOFLAGS` environment variable to enable Orchestrion for normal `go build` commands:

          ```bash theme={"theme":{"light":"github-light","dark":"github-dark-dimmed"}}
          export GOFLAGS="-toolexec='orchestrion toolexec'"
          go build ./...
          ```
        </Accordion>
      </Step>
    </Steps>
  </Tab>
</Tabs>

Run your app and check [Braintrust](https://www.braintrust.dev/app) — your LLM calls will appear in the project logs.

<Note>
  Streaming responses are fully supported — Braintrust automatically collects streamed chunks and logs the complete response as a single span.
</Note>

## Wrap functions

Wrap functions let you explicitly instrument individual client instances. This is an alternative to auto-instrumentation, useful if you prefer explicit control or if auto-instrumentation isn't supported by the libraries you're using. Unlike auto-instrumentation, you need to wrap each client instance in your application.

<Tabs>
  <Tab title="TypeScript">
    ```typescript theme={"theme":{"light":"github-light","dark":"github-dark-dimmed"}}
    import { initLogger, wrapOpenAI } from "braintrust";
    import OpenAI from "openai";

    initLogger({
      apiKey: process.env.BRAINTRUST_API_KEY,
      projectName: "My Project (TypeScript)",
    });

    // Wrap the OpenAI client to trace all calls
    const client = wrapOpenAI(new OpenAI({ apiKey: process.env.OPENAI_API_KEY }));
    const response = await client.responses.create({
      model: "gpt-5-mini",
      input: "What is the capital of France?",
    });
    ```
  </Tab>

  <Tab title="Python">
    ```python theme={"theme":{"light":"github-light","dark":"github-dark-dimmed"}}
    import os
    import braintrust
    from braintrust import wrap_openai
    from openai import OpenAI

    braintrust.init_logger(
        api_key=os.environ["BRAINTRUST_API_KEY"],
        project="My Project (Python)",
    )

    # Wrap the OpenAI client to trace all calls
    client = wrap_openai(OpenAI(api_key=os.environ["OPENAI_API_KEY"]))
    response = client.responses.create(
        model="gpt-5-mini",
        input="What is the capital of France?",
    )
    ```
  </Tab>

  <Tab title="Ruby">
    Use `Braintrust.instrument!` with a `target:` to instrument a specific client instance:

    ```ruby theme={"theme":{"light":"github-light","dark":"github-dark-dimmed"}}
    require 'braintrust'
    require 'openai'

    Braintrust.init(
      api_key: ENV['BRAINTRUST_API_KEY'],
      default_project: 'My Project (Ruby)',
      auto_instrument: false
    )

    # Wrap a specific OpenAI client to trace all calls
    client = OpenAI::Client.new(access_token: ENV['OPENAI_API_KEY'])
    Braintrust.instrument!(:ruby_openai, target: client)

    response = client.responses.create(
      parameters: {
        model: 'gpt-5-mini',
        input: 'What is the capital of France?'
      }
    )
    ```

    Use `:openai` if you're using the [`openai`](https://rubygems.org/gems/openai) gem, or `:ruby_openai` for the [`ruby-openai`](https://rubygems.org/gems/ruby-openai) gem.
  </Tab>

  <Tab title="Go">
    The Go SDK provides tracing middleware that you pass to your AI provider's client constructor:

    ```go theme={"theme":{"light":"github-light","dark":"github-dark-dimmed"}}
    package main

    import (
    	"context"
    	"fmt"
    	"log"
    	"os"

    	"github.com/braintrustdata/braintrust-sdk-go"
    	traceopenai "github.com/braintrustdata/braintrust-sdk-go/trace/contrib/openai"
    	"github.com/openai/openai-go"
    	"github.com/openai/openai-go/option"
    	"github.com/openai/openai-go/responses"
    	"go.opentelemetry.io/otel"
    	sdktrace "go.opentelemetry.io/otel/sdk/trace"
    )

    func main() {
    	tp := sdktrace.NewTracerProvider()
    	defer tp.Shutdown(context.Background())
    	otel.SetTracerProvider(tp)

    	_, err := braintrust.New(tp,
    		braintrust.WithProject("My Project (Go)"),
    		braintrust.WithAPIKey(os.Getenv("BRAINTRUST_API_KEY")),
    	)
    	if err != nil {
    		log.Fatal(err)
    	}

    	// Create an OpenAI client with tracing middleware
    	client := openai.NewClient(
    		option.WithAPIKey(os.Getenv("OPENAI_API_KEY")),
    		option.WithMiddleware(traceopenai.NewMiddleware()),
    	)

    	response, err := client.Responses.New(context.Background(), responses.ResponseNewParams{
    		Model: "gpt-5-mini",
    		Input: responses.ResponseNewParamsInputUnion{OfString: openai.String("What is the capital of France?")},
    	})
    	if err != nil {
    		log.Fatal(err)
    	}

    	fmt.Println(response.OutputText())
    }
    ```
  </Tab>

  <Tab title="Java">
    ```bash theme={"theme":{"light":"github-light","dark":"github-dark-dimmed"}}
    # build.gradle
    dependencies {
        implementation 'dev.braintrust:braintrust-sdk-java:+'
        implementation 'com.openai:openai-java:+'
    }
    ```

    ```java #skip-compile theme={"theme":{"light":"github-light","dark":"github-dark-dimmed"}}
    import com.openai.client.OpenAIClient;
    import com.openai.client.okhttp.OpenAIOkHttpClient;
    import com.openai.models.chat.completions.ChatCompletionCreateParams;
    import com.openai.models.chat.completions.ChatCompletionUserMessageParam;
    import dev.braintrust.Braintrust;
    import dev.braintrust.config.BraintrustConfig;
    import dev.braintrust.instrumentation.openai.BraintrustOpenAI;

    class Main {
        public static void main(String[] args) {
            var config = BraintrustConfig.builder()
                .apiKey(System.getenv("BRAINTRUST_API_KEY"))
                .defaultProjectName("My Project (Java)")
                .build();
            var braintrust = Braintrust.get(config);
            var openTelemetry = braintrust.openTelemetryCreate();

            // Wrap the OpenAI client to trace all calls
            OpenAIClient openaiClient = OpenAIOkHttpClient.builder()
                .apiKey(System.getenv("OPENAI_API_KEY"))
                .build();
            OpenAIClient client = BraintrustOpenAI.wrapOpenAI(openTelemetry, openaiClient);

            var params = ChatCompletionCreateParams.builder()
                .model("gpt-5-mini")
                .addMessage(ChatCompletionUserMessageParam.builder()
                    .content("What is the capital of France?")
                    .build())
                .build();
            var response = client.chat().completions().create(params);
        }
    }
    ```
  </Tab>

  <Tab title=".NET">
    ```bash theme={"theme":{"light":"github-light","dark":"github-dark-dimmed"}}
    dotnet add package Braintrust.Sdk
    dotnet add package Braintrust.Sdk.OpenAI
    dotnet add package OpenAI
    ```

    ```csharp #skip-compile theme={"theme":{"light":"github-light","dark":"github-dark-dimmed"}}
    using System;
    using Braintrust.Sdk;
    using Braintrust.Sdk.Config;
    using Braintrust.Sdk.OpenAI;
    using OpenAI;
    using OpenAI.Chat;

    var config = BraintrustConfig.Of(
        ("BRAINTRUST_API_KEY", Environment.GetEnvironmentVariable("BRAINTRUST_API_KEY")),
        ("BRAINTRUST_DEFAULT_PROJECT_NAME", "My Project (.NET)"));
    var braintrust = Braintrust.Sdk.Braintrust.Get(config);
    var activitySource = braintrust.GetActivitySource();

    // Wrap the OpenAI client to trace all calls
    var openAIClient = BraintrustOpenAI.WrapOpenAI(
        activitySource, Environment.GetEnvironmentVariable("OPENAI_API_KEY"));

    var chatClient = openAIClient.GetChatClient("gpt-5-mini");
    var response = await chatClient.CompleteChatAsync(
        new ChatMessage[]
        {
            new UserChatMessage("What is the capital of France?")
        });
    ```
  </Tab>
</Tabs>

## Braintrust gateway

The [Braintrust gateway](/deploy/gateway) provides a unified OpenAI-compatible API for accessing models from many providers. When you call a model through the gateway, your requests are automatically traced — no SDK instrumentation or wrap functions needed. The gateway also provides automatic caching and observability across providers.

## Supported libraries

To help you log traces, Braintrust's SDKs support auto-instrumentation and wrap functions for many common libraries. Select a language to get started.

<Tabs>
  <Tab title="TypeScript">
    | Library                                                                                        | Min. library version | Setup guide                                                         |
    | ---------------------------------------------------------------------------------------------- | -------------------- | ------------------------------------------------------------------- |
    | [openai](https://www.npmjs.com/package/openai)                                                 | 4.0.0                | [OpenAI](/integrations/ai-providers/openai)                         |
    | [@anthropic-ai/sdk](https://www.npmjs.com/package/@anthropic-ai/sdk)                           | 0.60.0               | [Anthropic](/integrations/ai-providers/anthropic)                   |
    | [@google/genai](https://www.npmjs.com/package/@google/genai)                                   | 1.0.0                | [Gemini](/integrations/ai-providers/gemini)                         |
    | [ai](https://www.npmjs.com/package/ai) (Vercel AI SDK)                                         | 3.0.0                | [Vercel AI SDK](/integrations/sdk-integrations/vercel)              |
    | [@anthropic-ai/claude-agent-sdk](https://www.npmjs.com/package/@anthropic-ai/claude-agent-sdk) | 0.1.0                | [Claude Agent SDK](/integrations/agent-frameworks/claude-agent-sdk) |
    | [@mistralai/mistralai](https://www.npmjs.com/package/@mistralai/mistralai)                     | 1.0.0                | [Mistral](/integrations/ai-providers/mistral)                       |
    | [@openrouter/agent](https://www.npmjs.com/package/@openrouter/agent)                           | 0.1.2                | [OpenRouter Agent](/integrations/agent-frameworks/openrouter-agent) |
    | [@openrouter/sdk](https://www.npmjs.com/package/@openrouter/sdk)                               | 0.9.11               | [OpenRouter SDK](/integrations/sdk-integrations/openrouter)         |
  </Tab>

  <Tab title="Python">
    | Library                                                        | Min. library version | Setup guide                                                           |
    | -------------------------------------------------------------- | -------------------- | --------------------------------------------------------------------- |
    | [openai](https://pypi.org/project/openai/)                     | 1.71.0               | [OpenAI](/integrations/ai-providers/openai)                           |
    | [anthropic](https://pypi.org/project/anthropic/)               | 0.48.0               | [Anthropic](/integrations/ai-providers/anthropic)                     |
    | [google-genai](https://pypi.org/project/google-genai/)         | 1.30.0               | [Gemini](/integrations/ai-providers/gemini)                           |
    | [openrouter](https://pypi.org/project/openrouter/)             | 0.6.0                | [OpenRouter](/integrations/ai-providers/openrouter)                   |
    | [mistralai](https://pypi.org/project/mistralai/)               | 1.12.4               | [Mistral](/integrations/ai-providers/mistral)                         |
    | [openai-agents](https://pypi.org/project/openai-agents/)       | 0.0.19               | [OpenAI Agents SDK](/integrations/agent-frameworks/openai-agents-sdk) |
    | [claude-agent-sdk](https://pypi.org/project/claude-agent-sdk/) | 0.1.10               | [Claude Agent SDK](/integrations/agent-frameworks/claude-agent-sdk)   |
    | [litellm](https://pypi.org/project/litellm/)                   | 1.74.0               | [LiteLLM](/integrations/sdk-integrations/litellm)                     |
    | [google-adk](https://pypi.org/project/google-adk/)             | 1.14.1               | [Google ADK](/integrations/agent-frameworks/google)                   |
    | [agentscope](https://pypi.org/project/agentscope/)             | 1.0.0                | [AgentScope](/integrations/agent-frameworks/agentscope)               |
    | [pydantic-ai](https://pypi.org/project/pydantic-ai/)           | 0.1.9                | [Pydantic AI](/integrations/agent-frameworks/pydantic-ai)             |
    | [agno](https://pypi.org/project/agno/)                         | 2.1.0                | [Agno](/integrations/sdk-integrations/agno)                           |
    | [dspy](https://pypi.org/project/dspy/)                         | 2.6.0                | [DSPy](/integrations/sdk-integrations/dspy)                           |
    | [langchain-core](https://pypi.org/project/langchain-core/)     | 0.3.28               | [LangChain](/integrations/sdk-integrations/langchain)                 |
  </Tab>

  <Tab title="Ruby">
    | Library                                              | Min. library version | Setup guide                                        |
    | ---------------------------------------------------- | -------------------- | -------------------------------------------------- |
    | [openai](https://rubygems.org/gems/openai)           | 0.1.0                | [OpenAI](/integrations/ai-providers/openai)        |
    | [ruby-openai](https://rubygems.org/gems/ruby-openai) | 7.0.0                | [OpenAI](/integrations/ai-providers/openai)        |
    | [anthropic](https://rubygems.org/gems/anthropic)     | 0.3.0                | [Anthropic](/integrations/ai-providers/anthropic)  |
    | [ruby\_llm](https://rubygems.org/gems/ruby_llm)      | 1.8.0                | [RubyLLM](/integrations/sdk-integrations/ruby-llm) |
  </Tab>

  <Tab title="Go">
    | Library                                                                                             | Min. library version | Setup guide                                                       |
    | --------------------------------------------------------------------------------------------------- | -------------------- | ----------------------------------------------------------------- |
    | [github.com/openai/openai-go](https://pkg.go.dev/github.com/openai/openai-go)                       | 1.12.0               | [OpenAI](/integrations/ai-providers/openai)                       |
    | [github.com/anthropics/anthropic-sdk-go](https://pkg.go.dev/github.com/anthropics/anthropic-sdk-go) | 1.23.0               | [Anthropic](/integrations/ai-providers/anthropic)                 |
    | [google.golang.org/genai](https://pkg.go.dev/google.golang.org/genai)                               | 1.41.0               | [Gemini](/integrations/ai-providers/gemini)                       |
    | [google.golang.org/adk](https://pkg.go.dev/google.golang.org/adk)                                   | 0.4.0                | [Google ADK](/integrations/agent-frameworks/google)               |
    | [github.com/tmc/langchaingo](https://pkg.go.dev/github.com/tmc/langchaingo)                         | 0.1.13               | [LangChain](/integrations/sdk-integrations/langchain)             |
    | [github.com/sashabaranov/go-openai](https://pkg.go.dev/github.com/sashabaranov/go-openai)           | 1.41.2               | [OpenAI](/integrations/ai-providers/openai)                       |
    | [github.com/cloudwego/eino](https://pkg.go.dev/github.com/cloudwego/eino)                           | 0.8.4                | [CloudWeGo Eino](/integrations/sdk-integrations/cloudwego-eino)   |
    | [github.com/firebase/genkit/go](https://pkg.go.dev/github.com/firebase/genkit/go)                   | 1.5.0                | [Firebase Genkit](/integrations/sdk-integrations/firebase-genkit) |
  </Tab>

  <Tab title="Java">
    | Library                                                                                              | Min. library version | Setup guide                                           |
    | ---------------------------------------------------------------------------------------------------- | -------------------- | ----------------------------------------------------- |
    | [com.openai:openai-java](https://central.sonatype.com/artifact/com.openai/openai-java)               | 2.8.0                | [OpenAI](/integrations/ai-providers/openai)           |
    | [com.anthropic:anthropic-java](https://central.sonatype.com/artifact/com.anthropic/anthropic-java)   | 2.2.0                | [Anthropic](/integrations/ai-providers/anthropic)     |
    | [com.google.genai:google-genai](https://central.sonatype.com/artifact/com.google.genai/google-genai) | 1.18.0               | [Gemini](/integrations/ai-providers/gemini)           |
    | [dev.langchain4j:langchain4j](https://central.sonatype.com/artifact/dev.langchain4j/langchain4j)     | 1.8.0                | [LangChain](/integrations/sdk-integrations/langchain) |
  </Tab>

  <Tab title=".NET">
    | Library                                               | Min. library version | Setup guide                                       |
    | ----------------------------------------------------- | -------------------- | ------------------------------------------------- |
    | [OpenAI](https://www.nuget.org/packages/OpenAI)       | 2.6.0                | [OpenAI](/integrations/ai-providers/openai)       |
    | [Anthropic](https://www.nuget.org/packages/Anthropic) | 12.5.0               | [Anthropic](/integrations/ai-providers/anthropic) |
  </Tab>
</Tabs>

<Note>
  Braintrust also integrates with frameworks like [LangChain](/integrations/sdk-integrations/langchain), [LangGraph](/integrations/agent-frameworks/langgraph), [AgentScope](/integrations/agent-frameworks/agentscope), [CrewAI](/integrations/agent-frameworks/crew-ai), [LlamaIndex](/integrations/sdk-integrations/llamaindex), [Mastra](/integrations/agent-frameworks/mastra), and [OpenTelemetry](/integrations/sdk-integrations/opentelemetry). Many Python frameworks can be auto-instrumented directly with `braintrust.auto_instrument()`, while others still require framework-specific setup. See [Integrations](/integrations).
</Note>

## Next steps

* [Trace application logic](/instrument/trace-application-logic) to trace non-LLM code like data retrieval, tool calls, and business logic
* [Advanced tracing](/instrument/advanced-tracing) for distributed tracing, custom rendering, and more
* [Explore integrations](/integrations) with AI providers, SDKs, and developer tools
