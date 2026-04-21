> ## Documentation Index
> Fetch the complete documentation index at: https://braintrust.dev/docs/llms.txt
> Use this file to discover all available pages before exploring further.

# Interpret evaluation results

> Understand scores, identify issues, and find improvement opportunities

export const traceDescription_0 = "a single test case run — one input from your dataset processed through your task function and scored"

export const nestingContext_0 = "the structure of an evaluation run"

Each [offline evaluation](/evaluate/run-evaluations) creates an experiment, a permanent record of how the evaluated task performed on a dataset.

## View results

To view the results of an experiment, go to <Icon icon="beaker" /> **Experiments** in your project and select the experiment from the list.

* **Traces vs. spans** - By default, experiments display as a table of traces where each row represents a complete trace with its root span. To view the individual spans in traces instead, select <Icon icon="settings-2" /> **Display** > <Icon icon="rows-3" /> **Row type** > <Icon icon="diamond" /> **Spans**.

  View individual spans when you want to:

  * Analyze specific operations within traces
  * Find particular function calls or API requests
  * Examine timing and token usage for individual operations

  <Note>
    Spans view is optimized for analyzing individual operations. Experiment comparisons and diff mode are only available when viewing traces.
  </Note>

* **Metrics** - Along with the scores you track, Braintrust tracks a number of metrics about your LLM calls that help you assess and understand performance. For example, if you're trying to figure out why the average duration increased substantially when you change a model, it's useful to look at both duration and token metrics to diagnose the underlying issue.

  To compute LLM metrics like token counts, make sure you [wrap your LLM calls](/instrument/advanced-tracing#wrap-llm-clients).

* **Experiment summary** - Select <Icon icon="arrow-right-to-line" /> **Details** to view:

  * Comparisons to other experiments
  * Scorers used in the evaluation
  * Datasets tested
  * Saved parameters linked to the evaluation
  * Metadata like model and parameters

  Copy the experiment ID from the bottom of the summary pane for referencing in code or sharing with teammates.

### Filter results

Each project provides default table views with common filters for experiments, including:

* **Default view**: Shows all traces in the experiment
* **Non-errors**: Shows only traces without errors
* **Errors**: Shows only traces with errors
* **Scorer errors**: Show only traces with scorer errors
* **Unreviewed**: Hides traces that have been human-reviewed
* **Assigned to me**: Shows only traces assigned to the current user for human review

Use the <Icon icon="layers-2" /> menu to switch the table view.

<Tip>
  Built-in views (such as "All experiments view") cannot be modified, but you can create [custom table views](#create-custom-table-views) based on custom filters and display settings.
</Tip>

You can also use the <Icon icon="list-filter" /> **Filter** menu to add custom filtering. Use the **Basic** tab for point-and-click filtering, or switch to **SQL** to write precise [SQL queries](/reference/sql). To filter experiments by metadata programmatically, use the `metadata` query parameter on `GET /v1/experiment`. See [Filter experiments by metadata](/api-reference#filter-experiments-by-metadata) for details.

### Group results

Select <Icon icon="settings-2" /> **Display** > <Icon icon="corner-down-right" /> **Group by** to group the table by metadata fields to see patterns.

By default, group rows show one experiment’s summary data. To view summary data for all experiments, select **Include comparisons in group**.

### Order by regressions

Score and metric columns show summary statistics in their headers. To order columns by regressions, select <Icon icon="settings-2" /> **Display** > **Columns** > **Order by regressions**.

Within grouped tables, this sorts rows by regressions of a specific score relative to a comparison experiment.

## Examine a trace

Select any row to open the trace view and see complete details:

* Input, output, and expected values
* Metadata and parameters
* All spans in the trace hierarchy
* Scores and their explanations
* Timing and token usage

Ask yourself: Do good scores correspond to good outputs? If not, update your scorers or test cases.

Use the <Icon icon="fullscreen" /> button to expand the trace to fullscreen or the <Icon icon="arrow-up-right" /> button to open it in a separate page.

<Note>
  When [comparing experiments](/evaluate/compare-experiments) with diff mode enabled, only the default trace view is available. [Timeline](/observe/view-logs#view-as-a-timeline), [Thread](/observe/view-logs#view-as-a-conversation), and [custom views](/annotate/custom-views) are disabled during comparison.
</Note>

### Anatomy of a trace

A **trace** represents {traceDescription_0}.

Every trace contains one or more **spans**, each representing a unit of work with a start and end time. Spans nest inside each other to reflect {nestingContext_0}.

Braintrust assigns a type to each span:

| Span type                              | What it represents                                                                                                                                                                                                        |
| -------------------------------------- | ------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------- |
| <Icon icon="circle-dot" /> `eval`      | The root span for an evaluation run, wrapping a `task` span for your application code. One per test case — contains the input, expected output, and all child spans.                                                      |
| <Icon icon="box" /> `task`             | A unit of application logic — a workflow, pipeline step, or named operation. In logs, the root span is always a `task` span. Multiple `task` spans can appear in a single trace.                                          |
| <Icon icon="message-circle" /> `llm`   | A single call to an LLM. Shows the model, messages, parameters, token usage, and cost.                                                                                                                                    |
| <Icon icon="parentheses" /> `function` | A named block of application logic — retrieval, formatting, routing, etc.                                                                                                                                                 |
| <Icon icon="bolt" /> `tool`            | A tool call made by the model — an external API, code execution, database query, etc.                                                                                                                                     |
| <Icon icon="percent" /> `score`        | The result of a scorer — [online](/evaluate/score-online) (in logs) or [offline](/evaluate/write-scorers) (in evaluations). Contains the score value, scorer name, and for LLM-as-a-judge scorers, the judge's reasoning. |

### View as a hierarchy

While viewing a trace, select <Icon icon="list-tree" /> **Trace** to view the trace as a nested hierarchy. Each span is indented under its parent, making it useful for understanding the logical structure of your application: which function called which, how tool calls nest under LLM calls, and how sub-tasks relate to the root task. Expand and collapse branches to navigate the call graph.

Each span row shows inline metrics. By default, these are duration, total tokens, and estimated LLM cost. Cost is propagated from child spans to parent spans, making it easy to see which parts of a multi-step workflow are consuming the most of your cost budget. Use <Icon icon="ellipsis-vertical" /> > **Display metric types** to toggle which metrics appear:

* **Duration** (on by default)
* **Total tokens** (on by default)
* **Time to first token**
* **Estimated LLM cost** (on by default)

### View as a timeline

While viewing a trace, select <Icon icon="square-chart-gantt" /> **Timeline** to understand execution flow and token efficiency:

* **Timeline bars** — Each bar represents a span scaled by a metric of your choice and color-coded by span type.
* **Token distribution overview** — Breaks down LLM span token usage by type (uncached input, cached read, cache write, and output) and shows cache hit rate per span, making it easy to spot where caching is and isn't working.

To change the metric used to scale the bars and token distribution overview, select <Icon icon="ellipsis-vertical" /> and then **<Icon icon="chart-no-axes-gantt" /> Scale by**. Available options:

* **Duration** (default) — Bar width represents wall-clock time
* **Total tokens** — Bar width represents total token usage, useful for identifying spans that consume the most context
* **Prompt tokens** — Bar width represents input token usage
* **Completion tokens** — Bar width represents output token usage
* **Estimated cost** — Bar width represents the estimated cost of each span

To show only spans of specific types in the timeline and token distribution overview, select <Icon icon="ellipsis-vertical" /> and then **<Icon icon="list-filter" /> Filter by span type**.

* Enable **Maintain hierarchy** to preserve parent-child relationships: Parent spans are kept even if they don't match the filter, as long as they have matching descendants.

### View as a conversation

While viewing a trace, select <Icon icon="messages-square" /> **Thread** to view the trace as a conversation thread. This view displays messages, tool calls, and scores in chronological order, stripping away the hierarchy to show what was said and in what order. Use it for reading agent conversations and understanding the narrative flow of multi-turn interactions.

* By default, the thread view renders raw span data. Select <Icon icon="settings-2" /> to apply a preprocessor — choose the built-in **Thread** preprocessor to format the trace as a readable conversation, or select a [custom preprocessor](/observe/topics#create-custom-topic-maps) to control exactly how messages are rendered. When [topics](/observe/topics) are enabled, topic tags and facet outputs appear at the top of the thread view as well.
* Use <Icon icon="search" /> **Find** or press `Cmd/Ctrl+F` to search within the thread view and quickly locate specific content such as message text and score rationale. Matches are highlighted in-place using your browser's native highlighting. This search is scoped to the thread view content — use the trace view's [search feature](#search-within-a-trace) to search across spans.

### Test and apply signals

While viewing a trace, select <Icon icon="triangle-dashed" /> **Signals** to test topic facets and scorers on the current trace.

* **Topic facets**: Test how preprocessors transform the trace data, test what summaries prompts extract, or apply the complete facet (preprocessor + prompt) to see the end-to-end result.

* **Scorers**: Test scorers, apply them to the trace, or configure an automation rule for online scoring.

The **Signals** tab is particularly useful when developing custom topic maps or scorers, allowing you to iterate quickly by testing against real trace data. From the [<Icon icon="pentagon" /> **Topics**](/observe/topics) page, you can create and edit facets, and generate topic maps that classify traces into topics. See [Write scorers](/evaluate/write-scorers) for more details on creating custom scorers.

### Create custom trace views

While viewing a trace, select <Icon icon="layers-2" /> **Views** to create custom visualizations using natural language. Describe how you want to view your trace data and [<Icon icon="blend" /> **Loop**](/loop) will generate the code.

For example:

* "Create a view that renders a list of all tools available in this trace and their outputs"
* "Render the video url from the trace's metadata field and show simple thumbs up/down buttons"

By default, a custom trace view is only visible and editable by the user who created it. To share your view with all users in the project, select **Save** > **Save as new view version** > **Update**.

See [Create custom trace views](/annotate/custom-views) for detailed examples, API reference, and how to embed views in your own applications.

<Note>
  Self-hosted deployments: If you restrict outbound access, allowlist `https://www.braintrustsandbox.dev` to enable custom views. This domain hosts the sandboxed iframe that securely renders custom view code.
</Note>

### Search within a trace

While viewing a trace, use <Icon icon="search" /> **Find** or press `Cmd/Ctrl+F` to search for content within the trace. A scope dropdown lets you choose where to search:

* **<Icon icon="diamond" /> This span** — Search only within the currently selected span.
* **<Icon icon="list-tree" /> Full trace** — Search across all spans in the trace.

Matching spans are highlighted in the trace tree so you can quickly navigate to relevant content.

<Note>
  Trace search finds content within the currently open trace. To search across all traces in your project, use [filters](/observe/filter) or [deep search](/observe/deep-search).
</Note>

### Change span data format

When viewing a trace, each span field (input, output, metadata, etc.) displays data in a specific format. Change how a field displays by selecting the view mode dropdown in the field's header.

Available views:

* **Pretty** - Parses objects deeply and renders values as Markdown (optimized for readability)
* **JSON** - JSON highlighting and folding
* **YAML** - YAML highlighting and folding
* **Tree** - Hierarchical tree view for nested data structures

Additional format-specific views appear automatically for certain data types:

* **LLM** - Formatted AI messages and tool calls with Markdown
* **LLM Raw** - Unformatted AI messages and tool calls
* **HTML** - Rendered HTML content

Your view mode selection is remembered per field type. To set a default view mode for all fields, go to <Icon icon="settings-2" /> **Settings** > **Personal** > <Icon icon="square-user-round" /> **Profile** and select your preferred data view. See [Personal settings](/admin/personal-settings#default-data-display-format) for more details.

### View raw trace data

When viewing a trace, select a span and then select the <Icon icon="braces" /> button in the span's header to view the complete JSON representation. The raw data view shows all fields including metadata, inputs, outputs, and internal properties that may not be visible in other views.

The raw data view has two tabs:

* **This span** - Shows the complete JSON for the selected span only
* **Full trace** - Shows the complete JSON for the entire trace

Use the search bar at the top of the dialog to find specific content within the data.

Raw span data is useful when you need to:

* Inspect the complete span structure for debugging
* Find specific fields in large or deeply nested spans
* Verify exact values and data types
* Export or copy the full span for reproduction

## Assign for review

You can assign experiment rows to team members for review, analysis, or follow-up action. Assignments are particularly useful for human review workflows, where you can assign specific rows that need human evaluation and distribute review work across multiple team members.

See [Assign rows for review](/annotate/human-review#assign-rows-for-review) for details.

## Score retrospectively

Apply scorers to existing experiments:

* **Multiple cases**: Select rows and use <Icon icon="percent" /> **Score** to apply chosen scorers
* **Single case**: Open a trace and use <Icon icon="percent" /> **Score** in the trace view

Scores appear as additional spans within the trace.

## Analyze with Loop

Use [<Icon icon="blend" /> **Loop**](/loop) to analyze experiment results, identify patterns, and get improvement suggestions. Loop can help you understand why certain test cases succeeded or failed and generate actionable recommendations.

Select one or more experiments and open Loop to:

* **Summarize results**: Get high-level insights about experiment performance, score trends, and key differences between experiments.
* **Drill into specific rows**: Ask Loop to analyze test cases that performed poorly or identify patterns across failures.
* **Generate improvements**: Loop can suggest changes to prompts, scorers, or datasets based on experiment results.
* **Create datasets**: Extract problematic or interesting test cases into new datasets for targeted evaluation.
* **Generate code**: Get sample code for implementing improvements to test in your next experiment.

Example queries:

* "What improved from the last experiment?"
* "Categorize the errors in this experiment"
* "Pick the best scorers for this task"
* "Why did the factuality score drop?"
* "Create a dataset from the rows where the model failed"
* "What patterns do you see in the low-scoring cases?"

## Use aggregate scores

Aggregate scores are formulas that combine multiple scores into a single metric. They are useful when you track many scores but need a single metric to represent overall experiment quality.

See [Create aggregate scores](/admin/projects#create-aggregate-scores) for more details.

## Download results

To download an experiment's results, select <Icon icon="download" /> and then **Download as CSV** or **Download as JSON**.

## Customize the experiments table

### Adjust table layout

To switch between different layouts, select <Icon icon="settings-2" /> **Display** > <Icon icon="layout-grid" /> **Layout** and one of the following:

* **List**: Default table view
* **Grid**: Compare outputs side-by-side
* **Summary**: Large-type summary of scores and metrics across all experiments
* **Summary table**: Scores and metrics as rows with experiments as columns, with a PDF download option

Layouts respect view filters and are automatically saved when you save a view.

### Show and hide columns

Select <Icon icon="settings-2" /> **Display** > **Columns** and then:

* Show or hide columns to focus on relevant data
* Reorder columns by dragging them
* Pin important columns to the left

All column settings are automatically saved when you save a view.

When [topics](/observe/topics) are enabled, facet outputs appear as columns in the experiments table, similar to scores. You can filter and sort by facet columns to analyze patterns in your evaluation results. This helps identify which types of inputs (e.g., specific user tasks or sentiment categories) perform better or worse in your experiments.

### Create custom columns

Extract specific values from traces using custom columns:

1. Select <Icon icon="settings-2" /> **Display** > **Columns** > **+ Add custom column**.
2. Name your column.
3. Choose from inferred fields or write a SQL expression.

Once created, filter and sort using your custom columns.

### Create custom table views

To create or update a custom table view:

1. Apply the filters and display settings you want.
2. Open the <Icon icon="layers-2" /> menu and select **Save view\...** or **Save view as...**.

<Note>
  Custom table views are visible to all project members. Creating or editing a table view requires the **Update** project permission.
</Note>

### Set default table views

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

### Change the table density

To change the table density to see more or less detail per row, select <Icon icon="settings-2" /> **Display** > <Icon icon="list-chevrons-up-down" /> **Row height** > **Compact** or **Tall**.

## Export experiments

<Tabs>
  <Tab title="UI" icon="mouse-pointer-2">
    To export an experiment's results, open the menu next to the experiment name. You can export as CSV or JSON, and choose whether to download all fields.

    <img src="https://mintcdn.com/braintrust/286-LRz_qGMfyggP/images/core/experiments/exporting-experiments.png?fit=max&auto=format&n=286-LRz_qGMfyggP&q=85&s=c9d392fe5158555fa7547107c478ee4e" alt="Export experiments" width="2198" height="1496" data-path="images/core/experiments/exporting-experiments.png" />
  </Tab>

  <Tab title="SDK" icon="terminal">
    Access data from previous experiments by passing the `open` flag to `init()`:

    <CodeGroup dropdown>
      ```typescript theme={"theme":{"light":"github-light","dark":"github-dark-dimmed"}}
      import { init } from "braintrust";

      async function openExperiment() {
        const experiment = init("My Project", {
          experiment: "my-experiment",
          open: true,
        });

        for await (const testCase of experiment) {
          console.log(testCase);
        }
      }
      ```

      ```python theme={"theme":{"light":"github-light","dark":"github-dark-dimmed"}}
      import braintrust

      def open_experiment():
          experiment = braintrust.init(
              project="My Project",
              experiment="my-experiment",
              open=True,
          )
          for test_case in experiment:
              print(test_case)
      ```
    </CodeGroup>

    Convert experiments to dataset format using `asDataset()`/`as_dataset()`:

    <CodeGroup dropdown>
      ```typescript theme={"theme":{"light":"github-light","dark":"github-dark-dimmed"}}
      import { init } from "braintrust";

      async function openExperiment() {
        const experiment = init("My Project", {
          experiment: "my-experiment",
          open: true,
        });

        for await (const testCase of experiment.asDataset()) {
          console.log(testCase);
        }
      }
      ```

      ```python theme={"theme":{"light":"github-light","dark":"github-dark-dimmed"}}
      import braintrust

      def open_experiment():
          experiment = braintrust.init(
              project="My Project",
              experiment="my-experiment",
              open=True,
          )
          for test_case in experiment.as_dataset():
              print(test_case)
      ```
    </CodeGroup>
  </Tab>

  <Tab title="API" icon="code">
    Fetch experiment events via the API using [Fetch experiment (POST form)](https://www.braintrust.dev/docs/api-reference#fetch-experiment-post-form) or [Fetch experiment (GET form)](https://www.braintrust.dev/docs/api-reference#fetch-experiment-get-form).

    You can also query experiments with SQL for custom analysis. For example, to check review status:

    <CodeGroup dropdown>
      ```python theme={"theme":{"light":"github-light","dark":"github-dark-dimmed"}}
      import os
      import requests

      API_URL = "https://api.braintrust.dev/"
      headers = {"Authorization": "Bearer " + os.environ["BRAINTRUST_API_KEY"]}

      def fetch_experiment_review_status(experiment_id: str) -> dict:
          # Replace "response quality" with your review score column name
          query = f"""
          SELECT
            sum(CASE WHEN scores."response quality" IS NOT NULL THEN 1 ELSE 0 END) AS reviewed,
            sum(CASE WHEN is_root THEN 1 ELSE 0 END) AS total
          FROM experiment('{experiment_id}')
          """

          return requests.post(
              f"{API_URL}/btql",
              headers=headers,
              json={"query": query, "fmt": "json"},
          ).json()

      # Usage
      result = fetch_experiment_review_status("your-experiment-id")
      print(f"Reviewed: {result['data'][0]['reviewed']}/{result['data'][0]['total']}")
      ```

      ```typescript theme={"theme":{"light":"github-light","dark":"github-dark-dimmed"}}
      const API_URL = "https://api.braintrust.dev/";
      const headers = {
        Authorization: `Bearer ${process.env.BRAINTRUST_API_KEY}`,
      };

      async function fetchExperimentReviewStatus(experimentId: string) {
        // Replace "response quality" with your review score column name
        const query = `
          SELECT
            sum(CASE WHEN scores."response quality" IS NOT NULL THEN 1 ELSE 0 END) AS reviewed,
            sum(CASE WHEN is_root THEN 1 ELSE 0 END) AS total
          FROM experiment('${experimentId}')
        `;

        const response = await fetch(`${API_URL}/btql`, {
          method: "POST",
          headers,
          body: JSON.stringify({ query, fmt: "json" }),
        });

        return await response.json();
      }

      // Usage
      const result = await fetchExperimentReviewStatus("your-experiment-id");
      console.log(`Reviewed: ${result.data[0].reviewed}/${result.data[0].total}`);
      ```
    </CodeGroup>
  </Tab>

  <Tab title="CLI" icon="terminal">
    Download experiment data to a local NDJSON file with [`bt sync pull`](/reference/cli/sync):

    ```bash theme={"theme":{"light":"github-light","dark":"github-dark-dimmed"}}
    bt sync pull experiment:my-experiment
    ```

    Query experiment data with SQL using [`bt sql`](/reference/cli/sql):

    ```bash theme={"theme":{"light":"github-light","dark":"github-dark-dimmed"}}
    bt sql "SELECT id, input, output, scores FROM experiment('my-experiment')"
    ```
  </Tab>
</Tabs>

## Next steps

* [Compare experiments](/evaluate/compare-experiments) systematically
* [Write scorers](/evaluate/write-scorers) to measure what matters
* [Use playgrounds](/evaluate/playgrounds) for rapid iteration
* [Run evaluations](/evaluate/run-evaluations) in CI/CD
