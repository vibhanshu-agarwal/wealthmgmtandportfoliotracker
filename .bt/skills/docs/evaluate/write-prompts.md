> ## Documentation Index
> Fetch the complete documentation index at: https://braintrust.dev/docs/llms.txt
> Use this file to discover all available pages before exploring further.

# Create prompts

> Create, test, and version prompts for your AI application

Prompts are the instructions that guide model behavior. Braintrust lets you create prompts, test them in playgrounds, use them in code, and track their performance over time.

## Create prompts

<Tabs>
  <Tab title="UI" icon="mouse-pointer-2">
    Create prompts directly in the Braintrust UI:

    1. Go to **Prompts** and click **+ Prompt**

    2. Configure the prompt:
       * **Name**: Descriptive display name
       * **Slug**: Unique identifier for code references (remains constant across updates)
       * **Model and parameters**: Model selection, temperature, max tokens, etc.
       * **Messages**: System, user, assistant, or tool messages with text or images
       * **Templating syntax**: Mustache or Nunjucks for variable substitution
       * **Response format**: Freeform text, JSON object, or structured JSON schema
       * **Description**: Optional context about the prompt's purpose
       * **Metadata**: Optional additional information

    3. Click **Save as custom prompt**
  </Tab>

  <Tab title="SDK" icon="terminal">
    Define prompts in code and push to Braintrust:

    <CodeGroup dropdown>
      ```typescript title="summarizer.ts" theme={"theme":{"light":"github-light","dark":"github-dark-dimmed"}}
      import * as braintrust from "braintrust";

      const project = braintrust.projects.create({
        name: "Summarizer",
      });

      export const summarizer = project.prompts.create({
        name: "Summarizer",
        slug: "summarizer",
        description: "Summarize text",
        model: "claude-sonnet-4-5-20250929",
        params: {
          temperature: 0.7,
          max_tokens: 1000,
          response_format: { type: "json_object" },
        },
        messages: [
          {
            role: "system",
            content: "You are a helpful assistant that can summarize text.",
          },
          {
            role: "user",
            content: "{{{text}}}",
          },
        ],
        metadata: { version: "1.0" },
      });
      ```

      ```python title="summarizer.py" theme={"theme":{"light":"github-light","dark":"github-dark-dimmed"}}
      import braintrust

      project = braintrust.projects.create(name="Summarizer")

      summarizer = project.prompts.create(
          name="Summarizer",
          slug="summarizer",
          description="Summarize text",
          model="claude-sonnet-4-5-20250929",
          params={
              "temperature": 0.7,
              "max_tokens": 1000,
              "response_format": {"type": "json_object"},
          },
          messages=[
              {"role": "system", "content": "You are a helpful assistant that can summarize text."},
              {"role": "user", "content": "{{{text}}}"},
          ],
          metadata={"version": "1.0"},
      )
      ```
    </CodeGroup>

    To associate a prompt with a specific deployment environment at creation time in TypeScript, pass the `environments` field:

    <CodeGroup dropdown>
      ```typescript theme={"theme":{"light":"github-light","dark":"github-dark-dimmed"}}
      export const summarizer = project.prompts.create({
        name: "Summarizer",
        slug: "summarizer",
        environments: ["production"],
        messages: [{ role: "user", content: "{{{text}}}" }],
      });
      ```
    </CodeGroup>

    Python `project.prompts.create(...)` does not currently expose an environment-assignment parameter, so use the UI or API if you need to associate environments from Python.

    See [Manage environments](/deploy/environments) for details on creating and using environments.

    Push to Braintrust using the [`bt` CLI](/reference/cli/quickstart):

    <CodeGroup>
      ```bash TypeScript theme={"theme":{"light":"github-light","dark":"github-dark-dimmed"}}
      bt functions push summarizer.ts
      ```

      ```bash Python theme={"theme":{"light":"github-light","dark":"github-dark-dimmed"}}
      bt functions push summarizer.py
      ```
    </CodeGroup>
  </Tab>
</Tabs>

## Use templating

Use templates to inject variables into prompts at runtime. Braintrust supports [Mustache](https://mustache.github.io/) and [Nunjucks](https://mozilla.github.io/nunjucks/templating.html) templating:

* **Mustache** (default): Simple variable substitution and basic logic
* **Nunjucks**: Advanced templating with loops, conditionals, and filters

### Mustache

[Mustache](https://mustache.github.io/) is the default templating language.

<Accordion title="Basic variable substitution">
  Use `{{variable}}` to insert values:

  ```
  Hello {{name}}! Your account balance is ${{balance}}.
  ```
</Accordion>

<Accordion title="Nested properties">
  Access nested object properties with dot notation:

  ```
  User: {{user.name}}
  Email: {{user.profile.email}}
  City: {{user.profile.address.city}}
  ```
</Accordion>

<Accordion title="Sections and iteration">
  Use sections to iterate over arrays or conditionally show content:

  ```
  {{#items}}
  - {{name}}: ${{price}}
  {{/items}}

  {{#user}}
  Welcome back, {{name}}!
  {{/user}}
  ```
</Accordion>

<Accordion title="Inverted sections">
  Use `^` to show content when a value is falsy or empty:

  ```
  {{^items}}
  No items found.
  {{/items}}
  ```
</Accordion>

<Accordion title="Comments">
  Use `{{! comment }}` for comments that won't appear in output:

  ```
  {{! This is a comment explaining the template }}
  Hello {{name}}!
  ```
</Accordion>

<Accordion title="Preserve special characters">
  If you want to preserve double curly brackets `{{` and `}}` as plain text when using Mustache, change the delimiter tags:

  ```
  {{=<% %>=}}
  Return the number in the following format: {{ number }}

  <% input.formula %>
  ```
</Accordion>

<Accordion title="Strict mode">
  Mustache supports strict mode, which throws an error when required template variables are missing:

  <CodeGroup dropdown>
    ```typescript theme={"theme":{"light":"github-light","dark":"github-dark-dimmed"}}
    const result = prompt.build(
      { name: "Alice" },
      {
        strict: true, // Throws if any required variables are missing
      },
    );
    ```

    ```python theme={"theme":{"light":"github-light","dark":"github-dark-dimmed"}}
    result = prompt.build(
        {"name": "Alice"},
        strict=True,  # Throws if any required variables are missing
    )
    ```
  </CodeGroup>
</Accordion>

### Nunjucks

For more complex templating needs, use [Nunjucks](https://mozilla.github.io/nunjucks/templating.html), which implements Jinja2 syntax in JavaScript.

<Accordion title="Loops">
  Process arrays and iterate over data:

  ```
  {% for item in items %}
  - {{ item.name }}: {{ item.description }}
  {% endfor %}
  ```

  Loop variables provide useful metadata:

  ```
  {% for product in products %}
  {{ loop.index }}. {{ product.name }}{% if not loop.last %}, {% endif %}
  {% endfor %}
  ```

  Available loop variables: `loop.index` (1-indexed), `loop.index0` (0-indexed), `loop.first`, `loop.last`, `loop.length`
</Accordion>

<Accordion title="Conditionals">
  Add logic to your prompts:

  ```
  {% if user.age >= 18 %}
  You are eligible to vote.
  {% elif user.age >= 16 %}
  You can get a driver's license.
  {% else %}
  You are a minor.
  {% endif %}
  ```

  Combine conditionals with loops:

  ```
  {% for product in products %}
    {% if product.inStock %}
  Available: {{ product.name }} - ${{ product.price }}
    {% endif %}
  {% endfor %}
  ```
</Accordion>

<Accordion title="Filters">
  Transform data with built-in filters:

  ```
  Hello {{ name | upper }}!
  Your email is {{ email | lower }}.
  Items: {{ items | join(", ") }}
  ```

  Common filters:

  * `upper`, `lower`: Change case
  * `title`, `capitalize`: Capitalize text
  * `join(separator)`: Join array elements
  * `length`: Get array or string length
  * `default(value)`: Provide default value
  * `replace(old, new)`: Replace text
</Accordion>

<Accordion title="String operations">
  Concatenate strings with `~`:

  ```
  {{ greeting ~ " " ~ name }}!
  Full name: {{ firstName ~ " " ~ lastName }}
  ```
</Accordion>

<Accordion title="Nested data access">
  Access nested properties and array elements:

  ```
  {{ user.profile.address.city }}
  {{ items[0].name }}
  {{ data.results[2].score }}
  ```
</Accordion>

<Tip>
  For complete Mustache syntax, see the [Mustache documentation](https://mustache.github.io/mustache.5.html). For Nunjucks syntax and features, see the [Nunjucks templating documentation](https://mozilla.github.io/nunjucks/templating.html).
</Tip>

## Add tools

[Tools](/deploy/functions#deploy-tools) extend your prompt's capabilities by allowing the LLM to call functions during execution:

* Query external APIs or databases
* Perform calculations or data transformations
* Retrieve information from vector stores or search engines
* Execute custom business logic

<Tabs>
  <Tab title="UI" icon="mouse-pointer-2">
    To add tools to a prompt in the UI:

    1. When creating or editing a prompt, click **Tools**.
    2. Select tool functions from your library or add raw tools as JSON.
    3. Click **Save tools**.
  </Tab>

  <Tab title="SDK" icon="terminal">
    To add tools to a prompt in code, use the `tools` parameter:

    <CodeGroup dropdown>
      ```typescript {21} theme={"theme":{"light":"github-light","dark":"github-dark-dimmed"}}
      import * as braintrust from "braintrust";

      const project = braintrust.projects.create({
        name: "RAG app",
      });

      export const docSearch = project.prompts.create({
        name: "Doc Search",
        slug: "document-search",
        model: "gpt-4o-mini",
        messages: [
          {
            role: "system",
            content: "You are a helpful assistant that can answer questions about the Braintrust documentation.",
          },
          {
            role: "user",
            content: "{{{question}}}",
          },
        ],
        tools: [toolRAG],
      });
      ```

      ```python {19} theme={"theme":{"light":"github-light","dark":"github-dark-dimmed"}}
      import braintrust

      project = braintrust.projects.create(name="RAG app")

      doc_search = project.prompts.create(
          name="Doc Search",
          slug="document-search",
          model="gpt-4o-mini",
          messages=[
              {
                  "role": "system",
                  "content": "You are a helpful assistant that can answer questions about the Braintrust documentation.",
              },
              {
                  "role": "user",
                  "content": "{{{question}}}",
              },
          ],
          tools=[tool_rag],
      )
      ```
    </CodeGroup>
  </Tab>
</Tabs>

## Add MCP servers

Use public [MCP (Model Context Protocol)](https://modelcontextprotocol.io/introduction) servers to give your prompts access to external tools and data:

* Evaluate complex tool calling workflows
* Experiment with external APIs and services
* Tune public MCP servers

MCP servers must be public and support OAuth authentication.

<Note>
  MCP servers are a UI-only feature. They work in playgrounds and experiments but not when invoked via SDK.
</Note>

### Add to a prompt

To add an MCP server to a prompt:

1. When creating or editing a prompt, click **MCP**.
2. Enable any available project-wide servers.
3. To add a prompt-specific MCP server, click **+ MCP server**:
   * Provide a name, the public URL of the server, and an optional description.
   * Click **Add server**.
   * Authenticate the MCP server in your browser.

For each MCP server, you'll see a list of available tools. Tools are enabled by default, but you can disable individual tools or click **Disable all**.

After testing a prompt-specific MCP server, you can promote it to a project-wide server by clicking **...** > **Save to project MCP servers**.

### Add to a project

Project-wide MCP servers are accessible across all projects in your organization:

1. Go to **Configuration** > **MCP**.
2. Click **+ MCP server** and provide a name, the public URL of the server, and an optional description.
3. Click **Authenticate** to authenticate the MCP server in your browser.
4. Click **Save**.

## Use in code

Reference prompts by slug to use them in your application:

<CodeGroup dropdown>
  ```typescript theme={"theme":{"light":"github-light","dark":"github-dark-dimmed"}}
  import { initLogger } from "braintrust";

  const logger = initLogger({ projectName: "My Project" });

  const result = await logger.invoke("summarizer", {
    input: { text: "Long article text here..." },
  });

  console.log(result.output);
  ```

  ```python theme={"theme":{"light":"github-light","dark":"github-dark-dimmed"}}
  from braintrust import init_logger

  logger = init_logger(project="My Project")

  result = logger.invoke("summarizer", input={"text": "Long article text here..."})

  print(result.output)
  ```
</CodeGroup>

Using prompts this way:

* Automatically logs inputs and outputs
* Tracks which prompt version was used
* Enables A/B testing different prompt versions
* Lets you update prompts without code changes

### Load a prompt

The `loadPrompt()`/`load_prompt()` function loads a prompt with caching support:

<CodeGroup dropdown>
  ```typescript theme={"theme":{"light":"github-light","dark":"github-dark-dimmed"}}
  import { OpenAI } from "openai";
  import { initLogger, loadPrompt, wrapOpenAI } from "braintrust";

  const logger = initLogger({ projectName: "My Project" });
  const client = wrapOpenAI(new OpenAI());

  async function runPrompt() {
    const prompt = await loadPrompt({
      projectName: "My Project",
      slug: "summarizer",
      defaults: {
        model: "gpt-4o",
        temperature: 0.5,
      },
    });

    return client.chat.completions.create(
      prompt.build({ text: "Article to summarize..." }),
    );
  }
  ```

  ```python theme={"theme":{"light":"github-light","dark":"github-dark-dimmed"}}
  from braintrust import init_logger, load_prompt, wrap_openai
  from openai import OpenAI

  logger = init_logger(project="My Project")
  client = wrap_openai(OpenAI())

  def run_prompt():
      prompt = load_prompt(
          "My Project",
          "summarizer",
          defaults=dict(model="gpt-4o", temperature=0.5)
      )

      return client.chat.completions.create(
          **prompt.build(text="Article to summarize...")
      )
  ```
</CodeGroup>

### Pin a specific version

Reference a specific version when loading prompts:

<CodeGroup dropdown>
  ```typescript theme={"theme":{"light":"github-light","dark":"github-dark-dimmed"}}
  const prompt = await loadPrompt({
    projectName: "My Project",
    slug: "summarizer",
    version: "5878bd218351fb8e",
  });
  ```

  ```python theme={"theme":{"light":"github-light","dark":"github-dark-dimmed"}}
  prompt = load_prompt("My Project", "summarizer", version="5878bd218351fb8e")
  ```
</CodeGroup>

### Assign to an environment

To assign a prompt to an environment:

<Tabs>
  <Tab title="UI" icon="mouse-pointer-2">
    1. Go to **<Icon icon="message-circle" /> Prompts**.
    2. Open the prompt.
    3. Click the <Icon icon="layers" /> icon.
    4. Select an environment.
  </Tab>

  <Tab title="API" icon="code">
    Use [`POST /v1/prompt`](/api-reference/prompts/create-prompt) or [`PUT /v1/prompt`](/api-reference/prompts/create-or-replace-prompt) and pass `environment_slugs` to assign the prompt to one or more environments in a single atomic request. If any slug doesn't exist, the entire request fails and no prompt is created.

    ```bash theme={"theme":{"light":"github-light","dark":"github-dark-dimmed"}}
    curl -X POST https://api.braintrust.dev/v1/prompt \
      -H "Authorization: Bearer $BRAINTRUST_API_KEY" \
      -H "Content-Type: application/json" \
      -d '{
        "project_id": "your-project-id",
        "name": "My prompt",
        "slug": "my-prompt-slug",
        "environment_slugs": ["dev", "staging"],
        "prompt_data": {
          "prompt": {
            "type": "chat",
            "messages": [{"role": "system", "content": "You are a helpful assistant"}]
          },
          "options": {
            "model": "gpt-5-mini"
          }
        }
      }'
    ```
  </Tab>
</Tabs>

Once assigned, load prompts for that environment in your code:

<Tabs>
  <Tab title="SDK" icon="terminal">
    <CodeGroup dropdown>
      ```typescript theme={"theme":{"light":"github-light","dark":"github-dark-dimmed"}}
      import { loadPrompt } from "braintrust";

      // Load from specific environment
      const prompt = await loadPrompt({
        projectName: "My Project",
        slug: "my-prompt",
        environment: "production",
      });

      // Use conditional versioning
      const prompt = await loadPrompt({
        projectName: "My Project",
        slug: "my-prompt",
        version: process.env.NODE_ENV === "production" ? "5878bd218351fb8e" : undefined,
      });
      ```

      ```python theme={"theme":{"light":"github-light","dark":"github-dark-dimmed"}}
      from braintrust import load_prompt
      import os

      # Load from specific environment
      prompt = load_prompt(
          project="My Project",
          slug="my-prompt",
          environment="production"
      )

      # Use conditional versioning
      prompt = load_prompt(
          "My Project",
          "my-prompt",
          version="5878bd218351fb8e" if os.environ.get("NODE_ENV") == "production" else None,
      )
      ```
    </CodeGroup>
  </Tab>

  <Tab title="API" icon="code">
    ```bash theme={"theme":{"light":"github-light","dark":"github-dark-dimmed"}}
    # Load by project ID and slug
    curl "https://api.braintrust.dev/v1/prompt?slug=my-prompt-slug&project_id=PROJECT_ID&environment=production" \
      -H "Authorization: Bearer $BRAINTRUST_API_KEY"

    # Load by prompt ID
    curl "https://api.braintrust.dev/v1/prompt/PROMPT_ID?environment=production" \
      -H "Authorization: Bearer $BRAINTRUST_API_KEY"
    ```
  </Tab>
</Tabs>

### Stream results

Stream prompt responses for real-time output:

<CodeGroup dropdown>
  ```typescript theme={"theme":{"light":"github-light","dark":"github-dark-dimmed"}}
  import { invoke } from "braintrust";

  async function main() {
    const result = await invoke({
      projectName: "My Project",
      slug: "summarizer",
      input: { text: "Article text..." },
      stream: true,
    });

    for await (const chunk of result) {
      console.log(chunk);
      // { type: "text_delta", data: "The summary "}
      // { type: "text_delta", data: "is..."}
    }
  }
  ```

  ```python theme={"theme":{"light":"github-light","dark":"github-dark-dimmed"}}
  from braintrust import invoke

  result = invoke("My Project", "summarizer", input={"text": "Article text..."}, stream=True)
  for chunk in result:
      print(chunk)
  ```
</CodeGroup>

### Add extra messages

Append additional messages to prompts for multi-turn conversations:

<CodeGroup dropdown>
  ```typescript theme={"theme":{"light":"github-light","dark":"github-dark-dimmed"}}
  import { invoke } from "braintrust";

  async function reflection(question: string) {
    const result = await invoke({
      projectName: "My Project",
      slug: "assistant",
      input: { question },
    });

    const reflectionResult = await invoke({
      projectName: "My Project",
      slug: "assistant",
      input: { question },
      messages: [
        { role: "assistant", content: result },
        { role: "user", content: "Are you sure about that?" },
      ],
    });

    return reflectionResult;
  }
  ```

  ```python theme={"theme":{"light":"github-light","dark":"github-dark-dimmed"}}
  from braintrust import invoke

  def reflection(question: str):
      result = invoke("My Project", "assistant", input={"question": question})

      reflection_result = invoke(
          "My Project",
          "assistant",
          input={"question": question},
          messages=[
              {"role": "assistant", "content": result},
              {"role": "user", "content": "Are you sure about that?"},
          ],
      )

      return reflection_result
  ```
</CodeGroup>

## Test prompts

Playgrounds provide a no-code environment for rapid prompt iteration:

1. Create or select a prompt
2. Add a dataset or enter test inputs
3. Run the prompt and view results
4. Adjust parameters or messages
5. Compare different versions side-by-side

See [Use playgrounds](/evaluate/playgrounds) for details.

You can also test prompts by chatting directly with them from the prompt details page. Each chat interaction is automatically logged as a trace in your project's logs. To navigate back to the prompt from these traces, see [Navigate to trace origins](/observe/view-logs#navigate-to-trace-origins).

## Optimize with Loop

Use Loop to generate and improve prompts:

Example queries:

* "Generate a prompt for a chatbot that can answer questions about the product"
* "Add few-shot examples based on project logs"
* "Optimize this prompt to be friendlier and more engaging"
* "Improve this prompt based on the experiment results"

Loop analyzes your data and suggests improvements automatically.

## Version prompts

Every prompt change creates a new version automatically. This lets you:

* Compare performance across versions
* Roll back to previous versions
* Pin experiments to specific versions
* Track which version is used in production

View version history in the prompt editor and select any version to restore or compare.

<Tip>
  You can manage different versions of prompts across your development lifecycle by assigning them to environments. See [Assign to an environment](#assign-to-an-environment) above or [Manage environments](/deploy/environments) for details.
</Tip>

## Best practices

**Start simple**: Begin with clear, direct instructions. Add complexity only when needed.

**Use few-shot examples**: Include 2-3 examples in your prompt to guide model behavior.

**Be specific**: Define exactly what you want, including format, tone, and constraints.

**Test with real data**: Use production logs to build test datasets that reflect actual usage.

**Iterate systematically**: Change one thing at a time and measure impact with experiments.

**Version everything**: Save prompt changes so you can track what works and roll back if needed.

## Create custom table views

The **Prompts** page supports custom table views to save your preferred filters, column order, and display settings.

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

* [Write scorers](/evaluate/write-scorers) to evaluate prompt quality
* [Run evaluations](/evaluate/run-evaluations) to compare prompt versions
* [Use playgrounds](/evaluate/playgrounds) for rapid iteration
* [Use the Loop](/loop) to optimize prompts
