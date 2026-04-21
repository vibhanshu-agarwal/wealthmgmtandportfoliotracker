> ## Documentation Index
> Fetch the complete documentation index at: https://braintrust.dev/docs/llms.txt
> Use this file to discover all available pages before exploring further.

# Create parameters

> Create, version, and manage configurable settings for evaluations

Parameters let you create reusable, versioned configuration for your evaluations. When running [complex evals](/evaluate/remote-evals) from the playground, parameters appear as UI controls — letting anyone on your team adjust model choice, prompts, or other settings without touching code.

Define parameters once in Braintrust, then load them across multiple evaluations:

* **Reusability**: Share configurations across multiple evaluations
* **Centralized management**: Update parameters in one place for all evaluations
* **Version control**: Track parameter changes independently of evaluation code
* **Environment management**: Use different parameter values across dev, staging, and production

<Note>
  Saved parameters can be created and loaded from both the TypeScript and Python SDKs. The examples below use TypeScript SDK v2.2.1+ and Python SDK v0.10.0+.
</Note>

## Create parameters

Define parameters in code and push to Braintrust:

<CodeGroup dropdown>
  ```typescript title="eval-config.ts" theme={"theme":{"light":"github-light","dark":"github-dark-dimmed"}}
  import * as braintrust from "braintrust";
  import { z } from "zod";

  const project = braintrust.projects.create({
    name: "My Project",
  });

  export const evalConfig = project.parameters.create({
    name: "Evaluation config",
    slug: "eval-config",
    description: "Configuration for model evaluation",
    schema: {
      model: {
        type: "model",
        default: "gpt-5-mini",
        description: "Model to evaluate",
      },
      temperature: z.number().min(0).max(1).default(0.2).describe("Sampling temperature"),
      system_prompt: z.string().default("You are a helpful assistant.").describe("System prompt to use"),
      eval_prompt: {
        type: "prompt",
        description: "Prompt to use for the evaluation task",
        default: {
          messages: [
            {
              role: "user",
              content: "{{input}}",
            },
          ],
          model: "gpt-5-mini",
        },
      },
    },
    metadata: { version: "1.0" },
  });
  ```

  ```python title="eval-config.py" theme={"theme":{"light":"github-light","dark":"github-dark-dimmed"}}
  import braintrust
  from pydantic import BaseModel, Field

  project = braintrust.projects.create(name="My Project")

  class TemperatureParam(BaseModel):
      value: float = Field(default=0.2, description="Sampling temperature")

  class SystemPromptParam(BaseModel):
      value: str = Field(
          default="You are a helpful assistant.",
          description="System prompt to use",
      )

  project.parameters.create(
      name="Evaluation config",
      slug="eval-config",
      description="Configuration for model evaluation",
      schema={
          "model": {
              "type": "model",
              "default": "gpt-5-mini",
              "description": "Model to evaluate",
          },
          "temperature": TemperatureParam,
          "system_prompt": SystemPromptParam,
          "eval_prompt": {
              "type": "prompt",
              "description": "Prompt to use for the evaluation task",
              "default": {
                  "prompt": {
                      "type": "chat",
                      "messages": [{"role": "user", "content": "{{input}}"}],
                  },
                  "options": {"model": "gpt-5-mini"},
              },
          },
      },
      metadata={"version": "1.0"},
  )
  ```
</CodeGroup>

Push to Braintrust using the [`bt` CLI](/reference/cli/quickstart):

<CodeGroup>
  ```bash TypeScript theme={"theme":{"light":"github-light","dark":"github-dark-dimmed"}}
  bt functions push eval-config.ts
  ```

  ```bash Python theme={"theme":{"light":"github-light","dark":"github-dark-dimmed"}}
  bt functions push eval-config.py
  ```
</CodeGroup>

<Note>
  When you create parameters with default values, Braintrust automatically initializes them with those defaults. This means the first version of your parameter will already have the default values set, ready to use in evaluations or modify in the UI.
</Note>

## Specify parameter types

Define the structure and types of your parameters using Zod (TypeScript) or Pydantic plus Braintrust parameter descriptors (Python). You can combine these building blocks to create any structure your evaluation needs, whether simple flat configurations or complex nested objects.

Use Zod or Pydantic models for regular data parameters such as strings, numbers, booleans, arrays, and objects. Use Braintrust's built-in descriptors for special parameter types such as `model` and `prompt`, which power model pickers and prompt editors in the UI.

<CodeGroup dropdown>
  ```typescript theme={"theme":{"light":"github-light","dark":"github-dark-dimmed"}}
  import { z } from "zod";

  const schema = {
    model: {
      type: "model",
      default: "gpt-5-mini",
      description: "Model name",
    },
    system_prompt: z.string().default("You are a helpful assistant.").describe("System instructions"),
    temperature: z.number().min(0).max(1).default(0.2).describe("Sampling temperature"),
    enable_streaming: z.boolean().default(false).describe("Enable streaming"),
    eval_mode: z.enum(["baseline", "strict"]).default("baseline").describe("Evaluation mode"),
    tags: z.array(z.string()).default(["baseline"]).describe("Tags to attach to each run"),
    generation_config: z.object({
      max_tokens: z.number(),
      top_p: z.number(),
    }).default({
      max_tokens: 500,
      top_p: 1.0,
    }).describe("Additional generation settings"),
    eval_prompt: {
      type: "prompt",
      description: "Prompt to use",
      default: {
        messages: [
          {
            role: "user",
            content: "{{input}}",
          },
        ],
        model: "gpt-5-mini",
      },
    },
  };
  ```

  ```python theme={"theme":{"light":"github-light","dark":"github-dark-dimmed"}}
  from typing import Literal

  from pydantic import BaseModel, Field

  class TemperatureParam(BaseModel):
      value: float = Field(default=0.2, description="Sampling temperature")

  class SystemPromptParam(BaseModel):
      value: str = Field(
          default="You are a helpful assistant.",
          description="System instructions",
      )

  class StreamingParam(BaseModel):
      value: bool = Field(default=False, description="Enable streaming")

  class EvalModeParam(BaseModel):
      value: Literal["baseline", "strict"] = Field(
          default="baseline",
          description="Evaluation mode",
      )

  class TagsParam(BaseModel):
      value: list[str] = Field(
          default_factory=lambda: ["baseline"],
          description="Tags to attach to each run",
      )

  class GenerationConfigValue(BaseModel):
      max_tokens: int = 500
      top_p: float = 1.0

  class GenerationConfigParam(BaseModel):
      value: GenerationConfigValue = Field(
          default_factory=GenerationConfigValue,
          description="Additional generation settings",
      )

  schema = {
      "model": {
          "type": "model",
          "default": "gpt-5-mini",
          "description": "Model name",
      },
      "system_prompt": SystemPromptParam,
      "temperature": TemperatureParam,
      "enable_streaming": StreamingParam,
      "eval_mode": EvalModeParam,
      "tags": TagsParam,
      "generation_config": GenerationConfigParam,
      "eval_prompt": {
          "type": "prompt",
          "description": "Prompt to use",
          "default": {
              "prompt": {
                  "type": "chat",
                  "messages": [{"role": "user", "content": "{{input}}"}],
              },
              "options": {"model": "gpt-5-mini"},
          },
      },
  }
  ```
</CodeGroup>

## Edit parameters

Update parameter values or schemas in the UI or in code. Every edit creates a new version automatically, preserving the history of changes.

<Tabs>
  <Tab title="UI" icon="mouse-pointer-2">
    1. Go to **<Icon icon="square-dot" /> Parameters**.
    2. Select the parameters to edit.
    3. Modify values in the editor.
    4. Click **Save version**.
  </Tab>

  <Tab title="SDK" icon="terminal">
    Update your parameter definition and push the changes:

    <CodeGroup dropdown>
      ```typescript title="eval-config.ts" theme={"theme":{"light":"github-light","dark":"github-dark-dimmed"}}
      import * as braintrust from "braintrust";
      import { z } from "zod";

      const project = braintrust.projects.create({
        name: "My Project",
      });

      // Update the schema or default values
      export const evalConfig = project.parameters.create({
        name: "Evaluation config",
        slug: "eval-config",
        description: "Configuration for model evaluation",
        schema: {
          model: {
            type: "model",
            default: "gpt-5-mini",
            description: "Model to evaluate",
          },
          temperature: z.number().min(0).max(2).default(0.5).describe("Sampling temperature"),
          max_tokens: z.number().default(2000).describe("Maximum tokens to generate"),
          system_prompt: z.string().default("You are a helpful assistant.").describe("System prompt to use"),
        },
        metadata: { version: "1.1" },
      });
      ```

      ```python title="eval-config.py" theme={"theme":{"light":"github-light","dark":"github-dark-dimmed"}}
      import braintrust
      from pydantic import BaseModel

      project = braintrust.projects.create(name="My Project")

      class TemperatureParam(BaseModel):
          value: float = 0.5

      class MaxTokensParam(BaseModel):
          value: int = 2000

      class SystemPromptParam(BaseModel):
          value: str = "You are a helpful assistant."

      project.parameters.create(
          name="Evaluation config",
          slug="eval-config",
          description="Configuration for model evaluation",
          schema={
              "model": {
                  "type": "model",
                  "default": "gpt-5-mini",
                  "description": "Model to evaluate",
              },
              "temperature": TemperatureParam,
              "max_tokens": MaxTokensParam,
              "system_prompt": SystemPromptParam,
          },
          metadata={"version": "1.1"},
      )
      ```
    </CodeGroup>

    <CodeGroup>
      ```bash TypeScript theme={"theme":{"light":"github-light","dark":"github-dark-dimmed"}}
      bt functions push eval-config.ts
      ```

      ```bash Python theme={"theme":{"light":"github-light","dark":"github-dark-dimmed"}}
      bt functions push eval-config.py
      ```
    </CodeGroup>
  </Tab>
</Tabs>

Every edit creates a new version automatically, preserving the history of changes.

## Use in evaluations

Load parameters in your `Eval()` function using `loadParameters()` in TypeScript or `load_parameters()` in Python. Parameters are accessed in your task function via the `parameters` argument.

<Note>
  For [complex evals](/evaluate/remote-evals), parameters automatically become editable controls in the playground UI, letting you modify values without changing code.
</Note>

<CodeGroup dropdown>
  ```typescript theme={"theme":{"light":"github-light","dark":"github-dark-dimmed"}}
  import { Eval, loadParameters } from "braintrust";
  import { evalConfig } from "./eval-config";

  Eval("My Project", {
    experimentName: "Parameter test",
    data: async () => [
      { input: "What is 2+2?", expected: "4" },
    ],
    task: async (input, { parameters }) => {
      return await callModel(input, {
        model: parameters.model,
        temperature: parameters.temperature,
      });
    },
    parameters: loadParameters<typeof evalConfig>({
      projectName: "My Project",
      slug: "eval-config",
    }),
  });
  ```

  ```python theme={"theme":{"light":"github-light","dark":"github-dark-dimmed"}}
  from braintrust import Eval, load_parameters, wrap_openai
  from openai import OpenAI

  client = wrap_openai(OpenAI())

  saved_parameters = load_parameters(
      project="My Project",
      slug="eval-config",
  )

  def task(input, hooks):
      response = client.responses.create(
          model=hooks.parameters["model"],
          instructions=hooks.parameters["system_prompt"],
          input=input,
      )
      return response.output_text

  Eval(
      "My Project",
      data=lambda: [{"input": "What is 2+2?", "expected": "4"}],
      task=task,
      parameters=saved_parameters,
  )
  ```
</CodeGroup>

<Tip>
  When using `loadParameters()` or `load_parameters()` in [complex evals](/evaluate/remote-evals), the playground displays a version selector, letting you experiment with different parameter versions without editing code.
</Tip>

When you pass a loaded parameter set into `Eval(...)`, Braintrust links the experiment to that saved parameter version so the experiment metadata includes the corresponding `parameters_id` and `parameters_version`. In the UI, the experiment's **Details** sidebar shows a **Parameters** section with a clickable link back to the saved parameters object, and the experiments table includes a **Parameters** column so you can see which parameter version each experiment used at a glance.

### Pin to a version

Load a specific parameter version by ID:

<CodeGroup dropdown>
  ```typescript theme={"theme":{"light":"github-light","dark":"github-dark-dimmed"}}
  parameters: loadParameters({
    projectName: "My Project",
    slug: "eval-config",
    version: "5878bd218351fb8e",
  })
  ```

  ```python theme={"theme":{"light":"github-light","dark":"github-dark-dimmed"}}
  parameters = load_parameters(
      project="My Project",
      slug="eval-config",
      version="5878bd218351fb8e",
  )
  ```
</CodeGroup>

### Assign to an environment

To assign a specific parameter version to an environment:

1. Go to **<Icon icon="square-dot" /> Parameters**.
2. Open the parameter.
3. Click the <Icon icon="layers" /> icon.
4. Select an environment.

Once assigned, load parameters for that environment in your code:

<CodeGroup dropdown>
  ```typescript theme={"theme":{"light":"github-light","dark":"github-dark-dimmed"}}
  import { loadParameters } from "braintrust";

  const params = await loadParameters({
    projectName: "My Project",
    slug: "eval-config",
    environment: "production",
  });
  ```
</CodeGroup>

## Create custom table views

The **Parameters** page supports custom table views to save your preferred filters, column order, and display settings.

To create or update a custom table view:

1. Apply the filters and display settings you want.
2. Open the <Icon icon="layers-2" /> menu and select **Save view\...** or **Save view as...**.

<Note>
  Custom table views are visible to all project members. Creating or editing a table view requires the **Update** project permission.
</Note>

## Set default table views

You can set default views at two levels:

* **Organization default**: Visible to all members when they open the page. This applies per page — for example, you can set separate organization defaults for Logs, Experiments, and Review. To set an organization default, you need the **Manage settings** organization permission (included by default in the **Owner** role). See [Access control](/admin/access-control) for details.
* **Personal default**: Overrides the organization default for you only. Personal defaults are stored in your browser, so they do not carry over across devices or browsers.

To set a default view:

1. Switch to the view you want by selecting it from the <Icon icon="layers-2" /> menu.
2. Open the menu again and hover over the currently selected view to reveal its submenu.
3. Choose <Icon icon="flag-triangle-right" /> **Set as personal default view** or <Icon icon="pin" /> **Set as organization default view**.

To clear a default view:

1. Open the <Icon icon="layers-2" /> menu and hover over the currently selected view to reveal its submenu.
2. Choose <Icon icon="flag-triangle-right" /> **Clear personal default view** or <Icon icon="pin" /> **Clear organization default view**.

When a user opens a page, Braintrust loads the first match in this order: personal default, organization default, then the standard "All ..." view (e.g., "All logs view").

## Next steps

* [Test complex agents](/evaluate/remote-evals) to use parameters as UI controls in the playground
* [Manage environments](/deploy/environments) to version parameters across environments
