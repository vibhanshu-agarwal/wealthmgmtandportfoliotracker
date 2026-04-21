> ## Documentation Index
> Fetch the complete documentation index at: https://braintrust.dev/docs/llms.txt
> Use this file to discover all available pages before exploring further.

# Evaluate reasoning models

> Work with models that generate intermediate reasoning steps

Reasoning models like OpenAI's o4, Anthropic's Claude 3.5 Sonnet, and Google's Gemini 2.5 Flash generate intermediate thinking steps before producing final responses. Braintrust provides unified support for these models across providers.

<Note>
  Hybrid deployments require v0.0.74 or later for reasoning support.
</Note>

## Configure reasoning

Three parameters control reasoning behavior:

* **`reasoning_effort`**: Intensity of reasoning (**low**, **medium**, or **high**). Compatible with OpenAI's parameter.
* **`reasoning_enabled`**: Boolean to explicitly enable/disable reasoning output (no effect on OpenAI models, which default to "medium")
* **`reasoning_budget`**: Token budget for reasoning (use either `reasoning_effort` or `reasoning_budget`, not both)

## Use in code

Braintrust provides type augmentation for reasoning parameters:

* **TypeScript**: `@braintrust/proxy/types` extends OpenAI SDK types
* **Python**: `braintrust-proxy` provides casting utilities and type-safe helpers

### Basic usage

<CodeGroup dropdown>
  ```typescript theme={"theme":{"light":"github-light","dark":"github-dark-dimmed"}}
  import { OpenAI } from "openai";
  import "@braintrust/proxy/types";

  const openai = new OpenAI({
    baseURL: `${process.env.BRAINTRUST_API_URL || "https://api.braintrust.dev"}/v1/proxy`,
    apiKey: process.env.BRAINTRUST_API_KEY,
  });

  const response = await openai.chat.completions.create({
    model: "claude-sonnet-4-5-20250929",
    reasoning_effort: "medium",
    messages: [
      {
        role: "user",
        content: "What's 15% of 240?",
      },
    ],
  });

  // Access final response
  console.log(response.choices[0].message.content);
  // Output: "15% of 240 is 36."

  // Access reasoning steps
  console.log(response.choices[0].reasoning);
  // Output: Array of reasoning objects with step-by-step calculation
  ```

  ```python theme={"theme":{"light":"github-light","dark":"github-dark-dimmed"}}
  import os
  from openai import OpenAI

  client = OpenAI(
      base_url=f"{os.getenv('BRAINTRUST_API_URL') or 'https://api.braintrust.dev'}/v1/proxy",
      api_key=os.getenv("BRAINTRUST_API_KEY"),
  )

  response = client.chat.completions.create(
      model="claude-sonnet-4-5-20250929",
      reasoning_effort="medium",
      messages=[{"role": "user", "content": "What's 15% of 240?"}],
  )

  # Access final response
  print(response.choices[0].message.content)
  # Output: "15% of 240 is 36."

  # Access reasoning steps
  print(getattr(response.choices[0], "reasoning", None))
  ```
</CodeGroup>

### Reasoning structure

Reasoning steps include unique IDs and content:

```json theme={"theme":{"light":"github-light","dark":"github-dark-dimmed"}}
[
  {
    "id": "reasoning_step_1",
    "content": "I need to calculate 15% of 240..."
  },
  {
    "id": "reasoning_step_2",
    "content": "240 × 0.15 = 36..."
  }
]
```

<Note>
  The `id` field contains provider-specific signatures that must be preserved in multi-turn conversations. Always use exact IDs returned by the provider.
</Note>

## Stream reasoning

Reasoning streams through `delta.reasoning` in streaming responses:

<CodeGroup dropdown>
  ```typescript theme={"theme":{"light":"github-light","dark":"github-dark-dimmed"}}
  import { OpenAI } from "openai";
  import "@braintrust/proxy/types";

  const openai = new OpenAI({
    baseURL: `${process.env.BRAINTRUST_API_URL || "https://api.braintrust.dev"}/v1/proxy`,
    apiKey: process.env.BRAINTRUST_API_KEY,
  });

  const stream = await openai.chat.completions.create({
    model: "claude-sonnet-4-5-20250929",
    reasoning_effort: "high",
    stream: true,
    messages: [
      {
        role: "user",
        content: "Explain quantum entanglement in simple terms.",
      },
    ],
  });

  for await (const chunk of stream) {
    const delta = chunk.choices[0]?.delta;

    // Handle regular content
    if (delta?.content) {
      process.stdout.write(delta.content);
    }

    // Handle reasoning deltas
    if (delta?.reasoning) {
      console.log("\nReasoning step:", delta.reasoning);
    }
  }
  ```

  ```python theme={"theme":{"light":"github-light","dark":"github-dark-dimmed"}}
  import os
  from braintrust_proxy import from_openai_chat_completion_choice_delta
  from openai import OpenAI

  client = OpenAI(
      base_url=f"{os.getenv('BRAINTRUST_API_URL') or 'https://api.braintrust.dev'}/v1/proxy",
      api_key=os.getenv("BRAINTRUST_API_KEY"),
  )

  stream = client.chat.completions.create(
      model="claude-sonnet-4-5-20250929",
      reasoning_effort="high",
      stream=True,
      messages=[{"role": "user", "content": "Explain quantum entanglement in simple terms."}],
  )

  for chunk in stream:
      delta = from_openai_chat_completion_choice_delta(chunk.choices[0].delta)

      # Handle regular content
      if delta.content:
          print(delta.content, end="")

      # Handle reasoning deltas
      if delta.reasoning:
          print(f"\nReasoning step: {delta.reasoning.dict()}")
  ```
</CodeGroup>

## Multi-turn conversations

Include reasoning from previous turns to let models build on earlier thinking:

<CodeGroup dropdown>
  ```typescript theme={"theme":{"light":"github-light","dark":"github-dark-dimmed"}}
  import { OpenAI } from "openai";
  import "@braintrust/proxy/types";

  const openai = new OpenAI({
    baseURL: `${process.env.BRAINTRUST_API_URL || "https://api.braintrust.dev"}/v1/proxy`,
    apiKey: process.env.BRAINTRUST_API_KEY,
  });

  const firstResponse = await openai.chat.completions.create({
    model: "claude-sonnet-4-5-20250929",
    reasoning_effort: "medium",
    messages: [
      {
        role: "user",
        content: "What's the best approach to solve a complex math problem?",
      },
    ],
  });

  // Include previous reasoning in next turn
  const secondResponse = await openai.chat.completions.create({
    model: "claude-sonnet-4-5-20250929",
    reasoning_effort: "medium",
    messages: [
      {
        role: "user",
        content: "What's the best approach to solve a complex math problem?",
      },
      {
        role: "assistant",
        content: firstResponse.choices[0].message.content,
        reasoning: firstResponse.choices[0].reasoning,
      },
      {
        role: "user",
        content: "Now apply that approach to solve: 2x² + 5x - 3 = 0",
      },
    ],
  });
  ```

  ```python theme={"theme":{"light":"github-light","dark":"github-dark-dimmed"}}
  import os
  from braintrust_proxy import as_openai_chat_message_param
  from openai import OpenAI

  client = OpenAI(
      base_url=f"{os.getenv('BRAINTRUST_API_URL') or 'https://api.braintrust.dev'}/v1/proxy",
      api_key=os.getenv("BRAINTRUST_API_KEY"),
  )

  first_response = client.chat.completions.create(
      model="claude-sonnet-4-5-20250929",
      reasoning_effort="medium",
      messages=[{"role": "user", "content": "What's the best approach to solve a complex math problem?"}],
  )

  # Include previous reasoning in next turn
  second_response = client.chat.completions.create(
      model="claude-sonnet-4-5-20250929",
      reasoning_effort="medium",
      messages=[
          {"role": "user", "content": "What's the best approach to solve a complex math problem?"},
          as_openai_chat_message_param(
              {
                  "role": "assistant",
                  "content": first_response.choices[0].message.content,
                  "reasoning": getattr(first_response.choices[0].message, "reasoning", None),
              }
          ),
          {"role": "user", "content": "Now apply that approach to solve: 2x² + 5x - 3 = 0"},
      ],
  )
  ```
</CodeGroup>

## Test in playgrounds

Use playgrounds to test reasoning models interactively:

1. Select a reasoning-capable model
2. Set `reasoning_effort` in parameters
3. Run evaluations
4. View reasoning steps in trace view

Reasoning steps appear as separate spans in the trace, making it easy to understand the model's thinking process.

## Evaluate reasoning quality

Create scorers that evaluate both final outputs and reasoning steps:

```typescript theme={"theme":{"light":"github-light","dark":"github-dark-dimmed"}}
project.scorers.create({
  name: "Reasoning quality",
  slug: "reasoning-quality",
  messages: [
    {
      role: "user",
      content:
        'Evaluate the reasoning steps: {{reasoning}}\n\nAre they logical and complete? Return "A" for excellent, "B" for adequate, "C" for poor.',
    },
  ],
  model: "gpt-4o",
  choiceScores: {
    A: 1,
    B: 0.5,
    C: 0,
  },
});
```

This helps you understand whether models are using sound reasoning paths to reach conclusions.

## Next steps

* [Run evaluations](/evaluate/run-evaluations) with reasoning models
* [Write scorers](/evaluate/write-scorers) to evaluate reasoning quality
* [Use playgrounds](/evaluate/playgrounds) to test reasoning interactively
* [Compare experiments](/evaluate/compare-experiments) across reasoning efforts
