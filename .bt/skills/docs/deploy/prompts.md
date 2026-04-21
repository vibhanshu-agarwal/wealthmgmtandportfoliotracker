> ## Documentation Index
> Fetch the complete documentation index at: https://braintrust.dev/docs/llms.txt
> Use this file to discover all available pages before exploring further.

# Deploy prompts

> Ship and version prompts in production

Prompts created in Braintrust can be called directly from your application code. Changes made in the UI immediately affect production behavior, enabling rapid iteration without redeployment.

## Invoke a prompt

Use `invoke()` to call a deployed prompt by its slug:

<CodeGroup dropdown>
  ```typescript theme={"theme":{"light":"github-light","dark":"github-dark-dimmed"}}
  import { invoke } from "braintrust";

  const result = await invoke({
    projectName: "My Project",
    slug: "summarizer",
    input: {
      text: "Long text to summarize...",
    },
  });

  console.log(result);
  ```

  ```python theme={"theme":{"light":"github-light","dark":"github-dark-dimmed"}}
  from braintrust import invoke

  result = invoke(
      project_name="My Project",
      slug="summarizer",
      input={"text": "Long text to summarize..."},
  )

  print(result)
  ```

  ```ruby theme={"theme":{"light":"github-light","dark":"github-dark-dimmed"}}
  require 'braintrust'

  result = Braintrust.invoke(
    project_name: 'My Project',
    slug: 'summarizer',
    input: { text: 'Long text to summarize...' }
  )

  puts result['output']
  ```
</CodeGroup>

The `input` parameter values map to template variables in your prompt. For example, `{{text}}` in your prompt gets replaced with the `text` value from input.

## Use within a trace

When calling prompts from instrumented code, they automatically nest within your parent trace:

<CodeGroup dropdown>
  ```typescript theme={"theme":{"light":"github-light","dark":"github-dark-dimmed"}}
  import { initLogger, traced } from "braintrust";

  const logger = initLogger({ projectName: "My Project" });

  const summarize = traced(async (text: string) => {
    return await logger.invoke("summarizer", { input: { text } });
  });

  // This creates a trace with "summarize" as parent
  const result = await summarize("Long text to summarize...");
  ```

  ```python theme={"theme":{"light":"github-light","dark":"github-dark-dimmed"}}
  from braintrust import init_logger, traced

  logger = init_logger(project_name="My Project")

  @traced
  async def summarize(text: str):
      return await logger.invoke("summarizer", input={"text": text})

  # This creates a trace with "summarize" as parent
  result = await summarize("Long text to summarize...")
  ```
</CodeGroup>

This creates a hierarchical trace where the prompt execution appears as a child span of your function.

## Handle tool calls

When a prompt includes tools, the response contains tool calls that your code must handle:

<CodeGroup dropdown>
  ```typescript theme={"theme":{"light":"github-light","dark":"github-dark-dimmed"}}
  import { invoke } from "braintrust";

  const result = await invoke({
    projectName: "RAG App",
    slug: "document-search",
    input: { question: "What is Braintrust?" },
  });

  // Handle tool calls
  if (result.toolCalls) {
    for (const toolCall of result.toolCalls) {
      console.log(`Tool: ${toolCall.function.name}`);
      console.log(`Arguments: ${toolCall.function.arguments}`);
      // Execute tool and return results...
    }
  }
  ```

  ```python theme={"theme":{"light":"github-light","dark":"github-dark-dimmed"}}
  from braintrust import invoke

  result = invoke(
      project_name="RAG App",
      slug="document-search",
      input={"question": "What is Braintrust?"},
  )

  # Handle tool calls
  if hasattr(result, "tool_calls"):
      for tool_call in result.tool_calls:
          print(f"Tool: {tool_call.function.name}")
          print(f"Arguments: {tool_call.function.arguments}")
          # Execute tool and return results...
  ```
</CodeGroup>

See [Deploy functions](/deploy/functions) for details on deploying tools alongside prompts.

## Version prompts

Every prompt save creates a new version with a unique ID. Pin specific versions in production code:

<CodeGroup dropdown>
  ```typescript theme={"theme":{"light":"github-light","dark":"github-dark-dimmed"}}
  import { invoke } from "braintrust";

  const result = await invoke({
    projectName: "My Project",
    slug: "summarizer",
    version: "5878bd218351fb8e", // Pin to specific version
    input: { text: "Long text to summarize..." },
  });
  ```

  ```python theme={"theme":{"light":"github-light","dark":"github-dark-dimmed"}}
  from braintrust import invoke

  result = invoke(
      project_name="My Project",
      slug="summarizer",
      version="5878bd218351fb8e",  # Pin to specific version
      input={"text": "Long text to summarize..."},
  )
  ```
</CodeGroup>

Without a version parameter, `invoke()` uses the latest version.

## Use environments

Environments separate dev, staging, and production configurations. Set the environment when calling prompts:

<CodeGroup dropdown>
  ```typescript theme={"theme":{"light":"github-light","dark":"github-dark-dimmed"}}
  import { invoke } from "braintrust";

  const result = await invoke({
    projectName: "My Project",
    slug: "summarizer",
    environment: "production",
    input: { text: "Long text to summarize..." },
  });
  ```

  ```python theme={"theme":{"light":"github-light","dark":"github-dark-dimmed"}}
  from braintrust import invoke

  result = invoke(
      project_name="My Project",
      slug="summarizer",
      environment="production",
      input={"text": "Long text to summarize..."},
  )
  ```
</CodeGroup>

This uses the prompt version assigned to the production environment. See [Manage environments](/deploy/environments) for details.

## Build prompts locally

Use `build()` to compile a prompt's template without making an API call. This is useful for testing or generating messages to pass to your own LLM client:

<CodeGroup dropdown>
  ```typescript theme={"theme":{"light":"github-light","dark":"github-dark-dimmed"}}
  import { loadPrompt } from "braintrust";

  const prompt = await loadPrompt({
    projectName: "My Project",
    slug: "summarizer",
  });

  const { messages, model, temperature } = prompt.build({
    text: "Long text to summarize...",
  });

  console.log(messages);
  // Use messages with your own LLM client
  ```

  ```python theme={"theme":{"light":"github-light","dark":"github-dark-dimmed"}}
  from braintrust import load_prompt

  prompt = await load_prompt(
      project_name="My Project",
      slug="summarizer",
  )

  result = prompt.build({"text": "Long text to summarize..."})

  print(result["messages"])
  # Use messages with your own LLM client
  ```
</CodeGroup>

The `build()` method returns the compiled messages, model, and parameters without executing the prompt.

## Stream responses

Enable streaming to receive responses incrementally:

<CodeGroup dropdown>
  ```typescript theme={"theme":{"light":"github-light","dark":"github-dark-dimmed"}}
  import { invoke } from "braintrust";

  const stream = await invoke({
    projectName: "My Project",
    slug: "summarizer",
    input: { text: "Long text to summarize..." },
    stream: true,
  });

  for await (const chunk of stream) {
    process.stdout.write(chunk);
  }
  ```

  ```python theme={"theme":{"light":"github-light","dark":"github-dark-dimmed"}}
  from braintrust import invoke

  stream = invoke(
      project_name="My Project",
      slug="summarizer",
      input={"text": "Long text to summarize..."},
      stream=True,
  )

  for chunk in stream:
      print(chunk, end="")
  ```
</CodeGroup>

Streaming works through the gateway and automatically logs the complete response to Braintrust.

## Manage from the CLI

Use the [`bt` CLI](/reference/cli/quickstart) to browse and test deployed prompts without opening the UI.

**Browse prompts:**

```bash theme={"theme":{"light":"github-light","dark":"github-dark-dimmed"}}
bt prompts list                   # List all prompts in the active project
bt prompts view summarizer        # View a specific prompt's definition
```

**Test a deployed prompt:**

Use [`bt functions invoke`](/reference/cli/functions) to call a prompt and see its output directly from the terminal:

```bash theme={"theme":{"light":"github-light","dark":"github-dark-dimmed"}}
bt functions invoke --slug summarizer --input '{"text": "Long text to summarize..."}'
```

## Use the REST API

Call prompts directly via HTTP.

<Note>
  In the examples below, organizations on the EU [data plane](/admin/organizations#data-plane-region) should replace `api.braintrust.dev` with `api-eu.braintrust.dev`.
</Note>

```bash theme={"theme":{"light":"github-light","dark":"github-dark-dimmed"}}
curl https://api.braintrust.dev/v1/function \
  -H "Content-Type: application/json" \
  -H "Authorization: Bearer $BRAINTRUST_API_KEY" \
  -d '{
    "project_name": "My Project",
    "slug": "summarizer",
    "input": {
      "text": "Long text to summarize..."
    }
  }'
```

The REST API supports all the same parameters as the SDK, including versioning, environments, and streaming.

## Next steps

* [Deploy functions](/deploy/functions) to deploy tools and workflows alongside prompts
* [Manage environments](/deploy/environments) to separate dev and production prompts
* [Monitor deployments](/deploy/monitor) to track prompt performance in production
* [Write prompts](/evaluate/write-prompts) to create and test prompts before deployment
