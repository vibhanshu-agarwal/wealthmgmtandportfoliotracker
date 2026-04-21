> ## Documentation Index
> Fetch the complete documentation index at: https://braintrust.dev/docs/llms.txt
> Use this file to discover all available pages before exploring further.

# View your logs

> Browse traces, create custom columns, and organize with tags

export const traceDescription_0 = "a single request or interaction in your application"

export const nestingContext_0 = "your application's execution flow"

To view logs from your application in real-time, go to your project in the Braintrust UI and select <Icon icon="activity" /> **Logs**.

<Tip>
  To browse logs from your terminal, use [`bt view logs`](/reference/cli/view). It opens an interactive terminal UI with search, filtering, and trace navigation. Pass `--url` to jump directly to a specific view from the Braintrust app.
</Tip>

## Browse traces or spans

By default, logs display as a table of traces where each row represents a complete trace with its root span.

Select <Icon icon="settings-2" /> **Display** > <Icon icon="rows-3" /> **Row type** > <Icon icon="diamond" /> **Spans** view to see all logged spans individually.

View individual spans when you want to:

* Analyze specific operations within traces
* Find particular function calls or API requests
* Examine timing for individual operations

## Filter traces

Each project provides default table views with common filters, including:

* **Default view**: Shows all records
* **Non-errors**: Shows only records without errors
* **Errors**: Shows only records with errors
* **Unreviewed**: Hides items that have been human-reviewed
* **Assigned to me**: Shows only records assigned to the current user for human review

Use the <Icon icon="layers-2" /> menu to switch the table view.

You can also use the <Icon icon="list-filter" /> **Filter** menu to add custom filtering. See [Filter and search logs](/observe/filter) for more details.

<Tip>
  Built-in views (such as "All logs view") cannot be modified, but you can create [custom table views](#create-custom-table-views) based on custom filters and display settings.
</Tip>

## Group related traces

Group related traces by shared metadata or tags to understand multi-step operations.

1. Select <Icon icon="settings-2" /> **Display** > **Group trace by** and choose a tag or metadata path.
2. Select a trace with the grouped attribute to see it alongside all related traces
3. Switch to <Icon icon="square-chart-gantt" /> **Timeline** view to see operation timing or <Icon icon="messages-square" /> **Thread** view for the entire session.

<img src="https://mintcdn.com/braintrust/UMUFxoAjk7qVjzj5/images/core/logs/group-logs.png?fit=max&auto=format&n=UMUFxoAjk7qVjzj5&q=85&s=c7e24aafec4ae062eacd8ae5e7e7d3fa" alt="Group related traces" width="1219" height="793" data-path="images/core/logs/group-logs.png" />

## Examine a trace

Select any trace from the logs table to open it in a panel on the right side of your screen. The trace shows all spans that make up the request, with detailed information about inputs, outputs, timing, and metadata.

Use the <Icon icon="fullscreen" /> button to expand the trace to fullscreen or the <Icon icon="arrow-up-right" /> button to open it in a separate page.

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

### Navigate to trace origins

To trace the origin of a trace that comes from a prompt or dataset:

1. Click <Icon icon="ellipsis-vertical" /> in the trace header.
2. Choose <Icon icon="message-circle" /> **Go to origin prompt** or <Icon icon="database" /> **Go to origin dataset**.

This helps you:

* Trace issues back to the original prompt or dataset
* See which dataset example led to a result
* Move efficiently between trace analysis and refining prompts or datasets

### Share traces

When viewing a trace:

1. Select <Icon icon="lock" /> **Share**.
2. Choose whether to make the trace <Icon icon="lock" /> **Private** or <Icon icon="globe" /> **Public**. Making a trace public grants access only to that trace.
3. Click **Copy link** and share it with others.

### Re-run a prompt

When viewing a prompt span in a trace:

1. Select <Icon icon="play" /> **Run**.
2. In the **Run prompt** dialog, make changes as necessary.
3. Select <Icon icon="play" /> **Test** to see the output.

You can also give this prompt a name and select **Save as custom prompt** to save it to your project's prompt library.

## Delete traces

<Tabs>
  <Tab title="UI" icon="mouse-pointer-2">
    Delete traces from the log table using the Braintrust UI:

    1. Select the traces you want to delete.
    2. Click <Icon icon="trash" /> **Delete selected rows from current project logs**.
    3. Confirm the deletion.
  </Tab>

  <Tab title="API" icon="code">
    Mark logs for deletion by setting `_object_delete`:

    <CodeGroup dropdown>
      ```python theme={"theme":{"light":"github-light","dark":"github-dark-dimmed"}}
      import os
      import requests

      API_URL = "https://api.braintrust.dev/"
      headers = {"Authorization": "Bearer " + os.environ["BRAINTRUST_API_KEY"]}

      # Find logs to delete
      query = """
      SELECT id
      FROM project_logs('project-id', shape => 'traces')
      WHERE metadata.user_id = 'test-user'
      """

      response = requests.post(
          f"{API_URL}/btql",
          headers=headers,
          json={"query": query}
      ).json()

      ids = [row["id"] for row in response["data"]]

      # Delete logs
      delete_events = [{"id": id, "_object_delete": True} for id in ids]
      requests.post(
          f"{API_URL}/v1/project_logs/project-id/insert",
          headers=headers,
          json={"events": delete_events}
      )
      ```
    </CodeGroup>
  </Tab>
</Tabs>

## Analyze with Loop

Use <Icon icon="blend" /> **Loop** to query and analyze your logs through natural language. Loop is available on both the main <Icon icon="activity" /> **Logs** page and when viewing individual traces.

See [Analyze logs](/loop#analyze-logs) and [Analyze individual traces](/loop#analyze-individual-traces) for more details.

## Iterate in playgrounds

Extract prompts and inputs from logs to quickly test variations in playgrounds.

1. Select the rows you want to extract.
2. Select <Icon icon="shapes" /> **Iterate in playground**.
3. Customize settings and optionally append to existing resources.
4. Select **Create playground**.

## Assign for review

You can assign traces to team members for review, analysis, or follow-up action. Assignments are particularly useful for human review workflows, where you can assign specific rows that need human evaluation and distribute review work across multiple team members.

See [Assign rows for review](/annotate/human-review#assign-rows-for-review) for details.

## Organize with tags

Tags help you categorize and track specific types of data across logs, datasets, and experiments. See [Apply tags](/annotate/labels#apply-tags) for how to configure tags and apply them via the UI or SDK.

## Customize the logs table

### Show and hide columns

Select <Icon icon="settings-2" /> **Display** > **Columns** and then:

* Show or hide columns to focus on relevant data
* Reorder columns by dragging them
* Pin important columns to the left

All column settings are automatically saved when you save a view.

When [topics](/observe/topics) are enabled, facet summaries and classifications appear as columns in the logs table, similar to scores. You can filter and sort by facet columns to analyze patterns across your logs. For example, filter by `Task` to see all logs related to a specific user goal, or sort by `Sentiment` to identify negative interactions.

### Create custom columns

Surface important metadata, scores, or nested values directly in the logs table by creating custom columns:

1. Select <Icon icon="settings-2" /> **Display** > **+ Add custom column**.
2. Name your column.
3. Choose from inferred fields or write a SQL expression.

For example, create a column named `User ID` with the expression `metadata.user_id` to display the user ID for each trace.

Custom columns work the same way in both logs and experiments. For more details, see [Create custom columns](/evaluate/interpret-results#create-custom-columns).

### Change the table density

To change the table density to see more or less detail per row, select <Icon icon="settings-2" /> **Display** > <Icon icon="list-chevrons-up-down" /> **Row height** > **Compact** or **Tall**.

### Visualize topic distributions

Select **<Icon icon="settings-2" /> Display > <Icon icon="rows-3" /> Row type > [<Icon icon="pentagon" /> Topics](https://www.braintrust.dev/app/~/logs?qs=topics)** to see topic distributions across all your facets. Each topic appears as a card showing its percentage of the total and trace count.

<img src="https://mintcdn.com/braintrust/7nbTi_oqpw1F8C8t/images/topics/visualize-topic-distributions.png?fit=max&auto=format&n=7nbTi_oqpw1F8C8t&q=85&s=caecb76093b85e0ace0d1621e53b75e0" alt="Visualize topic distributions" width="2712" height="1434" data-path="images/topics/visualize-topic-distributions.png" />

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

## Next steps

* [Analyze with Loop](/loop) using natural language queries
* [Filter and search](/observe/filter) to find specific traces
* [Use deep search](/observe/deep-search) for semantic queries
* [Score online](/evaluate/score-online) to evaluate production data
* [Create dashboards](/observe/dashboards) to monitor metrics
