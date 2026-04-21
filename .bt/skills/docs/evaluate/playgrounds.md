> ## Documentation Index
> Fetch the complete documentation index at: https://braintrust.dev/docs/llms.txt
> Use this file to discover all available pages before exploring further.

# Iterate in playgrounds

> Rapidly prototype and iterate on prompts, models, and scorers

export const feature_0 = "Playground annotations"

export const verb_0 = "are"

Playgrounds provide a no-code workspace for rapidly iterating on prompts, models, scorers, and datasets. Run full evaluations in real-time, compare results side-by-side, and share configurations with teammates.

<img src="https://mintcdn.com/braintrust/tQWbJcq__mTOHSpB/images/guides/playground/simple-playground.png?fit=max&auto=format&n=tQWbJcq__mTOHSpB&q=85&s=9ef12a540b5c84893c45716f9c10c88b" alt="Empty Playground" width="2436" height="1674" data-path="images/guides/playground/simple-playground.png" />

## Create a playground

Go to <Icon icon="shapes" /> **Playgrounds** and select **Create empty playground** or **+ Playground**.

A playground includes:

* Tasks: One or more prompts, workflows, or scorers to evaluate
* Scorers: Functions that measure output quality
* Dataset: Optional test cases with inputs and expected outputs

## Add tasks

Tasks define what you’re testing. Select **+ Task** and choose a type:

<AccordionGroup>
  <Accordion title="Prompts">
    Configure AI model, prompt messages, parameters, tools, and MCP servers. This is the most common task type for testing model responses. See [Write prompts](/evaluate/write-prompts) for details.
  </Accordion>

  <Accordion title="Workflows">
    Chain multiple prompts together to test complex workflows. Workflows allow you to create multi-step processes where the output of one prompt becomes the input for the next.

    <Warning>
      Workflows are in beta. They currently only work in playgrounds and are limited to prompt chaining functionality. If you are on a hybrid deployment, workflows are available starting with `v0.0.66`.
    </Warning>

    To create a workflow, select **+ Workflow** and create or select prompts to chain together. The prompts run consecutively, with each prompt receiving the previous prompt's output as input.

    **Variables in workflows:**

    Workflows use templating to reference variables from datasets and previous prompts:

    * **First prompt node**: Access dataset variables directly using `{{input}}`, `{{expected}}`, and `{{metadata}}`. For consistency, you can also use `{{dataset.input}}`, `{{dataset.expected}}`, and `{{dataset.metadata}}`.
    * **Later prompts**: Access the previous node's output using `{{input}}`. If the previous node outputs structured data, use dot notation like `{{input.bar}}`.
    * **Global dataset access**: The `{{dataset}}` variable is available in any prompt node to access the original dataset values (available in hybrid deployments starting with `v1.1.1`).

    <video className="border rounded-md" loop autoPlay muted poster="/images/core/functions/agents-poster.png">
      <source src="https://mintcdn.com/braintrust/FJKP8dcMkQrpeBHe/images/core/functions/agents.mp4?fit=max&auto=format&n=FJKP8dcMkQrpeBHe&q=85&s=8374829b853fa3873d121a7b1f2f728b" type="video/mp4" data-path="images/core/functions/agents.mp4" />
    </video>
  </Accordion>

  <Accordion title="Scorers as tasks">
    Run scorers as tasks to validate and iterate on them before using them to evaluate other tasks. See [Write scorers](/evaluate/write-scorers) for details.

    <Note>
      Scorers-as-tasks are different from scorers used to evaluate tasks. You can even score your scorers-as-tasks.
    </Note>

    An empty playground prompts you to create a base task and optional comparison tasks. The base task is the source for diffing outputs.

    <img src="https://mintcdn.com/braintrust/b11zJxKLgN0Qiq8B/images/guides/playground/base-task.png?fit=max&auto=format&n=b11zJxKLgN0Qiq8B&q=85&s=dc74b3cc874fa428bd71e427ef8ade03" alt="Base task empty playground" width="2428" height="822" data-path="images/guides/playground/base-task.png" />

    <Note>
      Configure [AI providers](/admin/organizations#configure-ai-providers) in organization settings, or configure them inline directly from the playground when you first run it.
    </Note>
  </Accordion>

  <Accordion title="Remote evals and sandboxes">
    Remote evals and sandboxes are complex tasks that you can't express as a prompt. They are evaluated on your own infrastructure or in a sandbox, and the results are streamed back to the playground.

    See [Test complex agents](/evaluate/remote-evals) for details.
  </Accordion>
</AccordionGroup>

## Add scorers

Scorers quantify output quality using LLM judges or code. Use built-in [autoevals](/reference/autoevals) or create [custom scorers](/evaluate/write-scorers).

To add a scorer, select **+ Scorer** and choose from the list or create a new one:

## Add datasets

Link a dataset to test multiple inputs at once. Without a dataset, the playground runs a single evaluation. With a dataset, it runs a matrix of evaluations across all test cases.

You can select an existing dataset or create a new one inline without leaving the playground. When creating a dataset, you have two options:

* **Upload CSV/JSON**: Import test cases from a file
* **Empty dataset**: Create a blank dataset to populate manually later

Once linked, you'll see a row for each dataset record.

Reference dataset fields in prompts using template variables:

```
Analyze this input: {{input}}
Expected output: {{expected}}
User category: {{metadata.category}}
```

<img src="https://mintcdn.com/braintrust/tQWbJcq__mTOHSpB/images/guides/playground/prompt-with-dataset.png?fit=max&auto=format&n=tQWbJcq__mTOHSpB&q=85&s=39ae72da8c0fac4ea67959baa25356da" alt="Prompt with dataset" width="2428" height="1556" data-path="images/guides/playground/prompt-with-dataset.png" />

The playground supports [Mustache and Nunjucks templating](/evaluate/write-prompts#use-templating). Access nested fields like `{{input.formula}}`.

### Image attachments

When a dataset variable contains an array of attachments, the playground expands it into one image part per item. Add an **image** content part to your prompt and enter the variable name (e.g. `{{images}}`).

Array items can be Braintrust attachments, inline attachments, or plain URL strings.

### Scorers-as-tasks

When evaluating scorers, dataset inputs should match scorer convention: `{ input, expected, metadata, output }`. These fields are hoisted into global scope for easy reference.

Example scorer prompt:

```
Is {{output}} funny and concerning the same topic as {{expected}}?
```

Example dataset row:

```json theme={"theme":{"light":"github-light","dark":"github-dark-dimmed"}}
{
  "input": {
    "output": "Why did the chicken cross the road? To get to the other side!",
    "expected": "Why's six afraid of seven? Because seven ate nine!"
  },
  "expected": {
    "choice": 0,
    "rationale": "Output is a clichéd joke about a different topic."
  }
}
```

## Run evaluations

Select <Icon icon="play" /> **Run** (or Cmd/Ctrl+Enter) to run all tasks and dataset rows in parallel. Results stream into the grid below.

<video className="border rounded-md" loop autoPlay muted poster="/images/guides/playground/running-playground-poster.png">
  <source src="https://mintcdn.com/braintrust/tQWbJcq__mTOHSpB/images/guides/playground/running-playground.mp4?fit=max&auto=format&n=tQWbJcq__mTOHSpB&q=85&s=583be95e02824efde3b0d61ebe43a524" type="video/mp4" data-path="images/guides/playground/running-playground.mp4" />
</video>

You can also:

* Run a single task
* Run a single dataset row
* View results in grid, list, or summary layout

For multimodal workflows, supported attachments will have a preview shown in the inline embedded view.

<Note>
  UI experiments run without a time limit on cloud and on self-hosted deployments running data plane v2.0 or later.
</Note>

## View traces

Select a row to compare traces side-by-side and identify differences in outputs, scores, metrics, and inputs:

<img src="https://mintcdn.com/braintrust/tQWbJcq__mTOHSpB/images/guides/playground/trace-viewer.png?fit=max&auto=format&n=tQWbJcq__mTOHSpB&q=85&s=a4220e1134dcce7d6f2d6fcb26f851f2" alt="Trace viewer" width="2428" height="718" data-path="images/guides/playground/trace-viewer.png" />

From this view, select <Icon icon="play" /> **Run row** to re-run a single test case.

## Annotate outputs

<Note>
  {feature_0} {verb_0} only available on [Pro and Enterprise plans](/plans-and-limits#plans).
</Note>

Annotate multiple outputs across many prompts using quick thumbs up/down reactions and free text comments, and then let Loop suggest prompt improvements based on your feedback.

To annotate and optimize:

1. Select <Icon icon="thumbs-up" /> or <Icon icon="thumbs-down" /> in a result cell.
2. Add a free text comment and click **Save**.
3. Repeat across any outputs you want to flag.
4. Select **Optimize** in the toolbar to open Loop with your annotations as context.
5. Review Loop's prompt suggestions and apply changes to iterate.

To remove all annotations from the current run, select **Clear feedback** in the toolbar.

<img src="https://mintcdn.com/braintrust/d-u9adkFZEQ1aPZD/images/playground-annotate-outputs.png?fit=max&auto=format&n=d-u9adkFZEQ1aPZD&q=85&s=ab9a358a2b84a9e4df9261396929040a" alt="Annotate outputs" width="2502" height="1464" data-path="images/playground-annotate-outputs.png" />

## Compare with diff mode

Enable the diff toggle to visually compare variations across models, prompts, or workflows:

<video className="border rounded-md" loop autoPlay muted poster="/images/guides/playground/diffing-poster.png">
  <source src="https://mintcdn.com/braintrust/b11zJxKLgN0Qiq8B/images/guides/playground/diffing.mp4?fit=max&auto=format&n=b11zJxKLgN0Qiq8B&q=85&s=fd3c52fc17317a244fa6ca5442f4ab5b" type="video/mp4" data-path="images/guides/playground/diffing.mp4" />
</video>

Diff mode highlights:

* Output differences between tasks
* Score changes
* Timing and token usage variations

## Save as experiment

Playground runs overwrite previous results on each run. When you've found a configuration worth keeping, select **+ Experiment** to promote it to an immutable snapshot. See [Run experiments](/evaluate/run-evaluations#promote-from-a-playground) for details.

## Share playgrounds

Collaborate by sharing playground URLs with teammates. They'll see the same configuration and can run their own evaluations or make changes. Playgrounds automatically synchronize in real-time.

Your collaborators must be members of your organization to view the playground. You can invite users from the settings page.

## Best practices

**Start simple**: Test one prompt or model first. Add comparisons once the base works.

**Use representative data**: Build datasets from production logs or known edge cases.

**Compare systematically**: Change one variable at a time (model, temperature, prompt wording) to isolate effects.

**Look for patterns**: Group by metadata fields to see which input types cause issues.

**Iterate quickly**: Playgrounds excel at rapid experimentation. Save experiments only when you need permanent records.

## Advanced options

### Append dataset messages

You may have additional messages in a dataset that you want to append to a prompt. This option lets you specify a path to a messages array in the dataset. For example, if `input` is specified as the appended messages path and a dataset row has the following input, all prompts in the playground will run with additional messages:

```json theme={"theme":{"light":"github-light","dark":"github-dark-dimmed"}}
[
  {
    "role": "assistant",
    "content": "Is there anything else I can help you with?"
  },
  {
    "role": "user",
    "content": "Yes, I have another question."
  }
]
```

To append messages from a dataset to your prompts, open the advanced settings menu next to your dataset selection and enter the path to the messages you want to append.

<img src="https://mintcdn.com/braintrust/b11zJxKLgN0Qiq8B/images/guides/playground/append-dataset-messages.png?fit=max&auto=format&n=b11zJxKLgN0Qiq8B&q=85&s=b9bfed529035a2d8560b2713405dcd43" alt="Screenshot of advanced settings menu" width="614" height="792" data-path="images/guides/playground/append-dataset-messages.png" />

### Max concurrency

The maximum number of tasks/scorers that will be run concurrently in the playground. This is useful for avoiding rate limits (429 - Too many requests) from AI/MCP providers.

### Strict variables

When this option is enabled, evaluations will fail if the dataset row does not include all of the variables referenced in prompts.

## Reasoning models

<Note>
  If you are on a hybrid deployment, reasoning support is available starting with `v0.0.74`.
</Note>

Reasoning models like OpenAI's o4, Anthropic's Claude 3.5 Sonnet, and Google's Gemini 2.5 Flash generate intermediate reasoning steps before producing a final response. Braintrust provides unified support for these models, so you can work with reasoning outputs no matter which provider you choose.

When you enable reasoning, models generate "thinking tokens" that show their step-by-step reasoning process. This is useful for complex tasks like math problems, logical reasoning, coding, and multi-step analysis.

In playgrounds, you can configure reasoning parameters directly in the model settings.

<img src="https://mintcdn.com/braintrust/tQWbJcq__mTOHSpB/images/guides/playground/reasoning-params.gif?s=2cb393dc424678c82a3a6ac5cd86496a" alt="Screenshot showing reasoning parameters" width="640" height="500" data-path="images/guides/playground/reasoning-params.gif" />

To enable reasoning in a playground:

1. Select a reasoning-capable model (like `claude-3-7-sonnet-latest`, `o4-mini`, or `publishers/google/models/gemini-2.5-flash-preview-04-17` for Gemini via Vertex AI).
2. In the model parameters section, configure your reasoning settings:
   * Set `reasoning_effort` to **low**, **medium**, or **high**.
   * Or enable `reasoning_enabled` and specify a `reasoning_budget`.
3. Run your prompt to see reasoning in action.

<img src="https://mintcdn.com/braintrust/tQWbJcq__mTOHSpB/images/guides/playground/reasoning-stream-response.gif?s=357659392bc26d54d676ae398a329489" alt="Screenshot showing reasoning in action" width="640" height="896" data-path="images/guides/playground/reasoning-stream-response.gif" />

## Create custom table views

The **Playgrounds** page supports custom table views to save your preferred filters, column order, and display settings.

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

* [Add human feedback](/annotate/human-review) for structured review workflows
* [Test complex agents](/evaluate/remote-evals) in the playground via remote evals or sandboxes
* [Run experiments](/evaluate/run-evaluations) with the SDK or in the UI
* [Run in CI/CD](/evaluate/run-evaluations#run-in-cicd) to catch regressions automatically
* [Score production traces](/evaluate/score-online) with online scoring rules
