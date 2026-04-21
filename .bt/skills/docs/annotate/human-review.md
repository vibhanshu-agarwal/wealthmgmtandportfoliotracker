> ## Documentation Index
> Fetch the complete documentation index at: https://braintrust.dev/docs/llms.txt
> Use this file to discover all available pages before exploring further.

# Add human feedback

> Review traces with structured scores

export const feature_0 = "Unlimited human review scorers"

export const verb_0 = "are"

Human review is a critical part of evaluating AI applications. While Braintrust helps you automatically evaluate AI software with scorers, human feedback provides essential ground truth and quality assessment.

Braintrust integrates human feedback from end users, subject matter experts, and product teams in one place. Use human review to:

* Evaluate and compare experiments
* Assess the efficacy of automated scoring methods
* Curate production logs into evaluation datasets
* Label categorical data and provide corrections
* Track quality trends over time

## Configure review scores

Review scores let you collect structured feedback on spans and label dataset rows.

<Note>
  {feature_0} {verb_0} only available on [Pro and Enterprise plans](/plans-and-limits#plans).
</Note>

Configure scores in <Icon icon="settings-2" /> **Settings** > **Project** > <Icon icon="list-checks" /> **Human review**. See [Configure human review](/admin/projects#configure-human-review) for details on score types and options.

## Assign rows for review

You can assign rows in logs, experiments, and datasets to team members for review, analysis, or follow-up action. Assignments are particularly useful for human review workflows, where you can assign specific rows that need human evaluation and distribute review work across multiple team members.

To assign a row to a team member from any table view (logs, experiments, or datasets):

1. Select the row.
2. Select **<Icon icon="flag" /> Assign**.
3. Choose a member to assign.

<Tip>
  Team members receive email notifications when rows are assigned to them.
</Tip>

## Score traces and datasets

Go to the <Icon icon="list-checks" /> **Review** page and select the type of data to review:

* **Log spans**: production traces and debugging sessions
* **Experiment spans**: Evaluation results and test runs
* **Dataset rows**: Test cases and examples

Then select a row and set scores. You can also [add comments and tags](/annotate/labels) while reviewing.

When finished reviewing, click **Complete review and continue** to move to the next item in the queue, or use the **Next row** and **Previous row** buttons.

<Note>
  Not all score types appear on dataset rows. Only categorical/slider scores configured to "write to expected" and free-form scores are available for dataset reviews, since datasets store test data (input/expected pairs) rather than subjective quality assessments.
</Note>

## Filter review data

The <Icon icon="list-checks" /> **Review** page shows any spans that have been flagged for review within a given time range. Each project provides default table views with common filters, including:

* **Default view**: Shows all records
* **Awaiting review**: Shows only records flagged for review but not yet started
* **Assigned to me**: Shows only records assigned to you for review
* **Completed**: Shows only records that have finished review

Use the <Icon icon="layers-2" /> **View** menu to switch between views.

You can also use the [<Icon icon="list-filter" /> **Filter**](/observe/filter) menu to focus on specific subsets for review. Use the **Basic** tab for point-and-click filtering, or switch to **SQL** to write precise queries. For example, filter by scores (e.g., `scores.Preference > 0.75`) to find highly-rated examples.

<Tip>
  Built-in views (such as "All logs view") cannot be modified, but you can create [custom table views](#create-custom-table-views) based on custom filters and display settings.
</Tip>

<Tip>
  Use [tags](/annotate/labels#apply-tags) to mark items for "Triage", then review them all at once.
</Tip>

## Change the trace layout

While reviewing log and experiment traces, you see detailed information about the flagged span by default.

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

## Create and edit scores inline

While reviewing, create new score types or edit existing configurations without navigating to settings:

* To create a new score, click **+ Human review score**.
* To edit an existing score, select the <Icon icon="pencil" /> edit icon next to the score name.

Changes apply immediately across your project.

<Note>
  Editing a score configuration affects how that score works going forward. Existing score values on traces remain unchanged.
</Note>

## Annotate in playgrounds

For a lighter-weight alternative to the full review workflow, you can [annotate outputs directly in playgrounds](/evaluate/playgrounds#annotate-outputs) and then get prompt improvement suggestions based on your annotations.

Playground annotations help with rapid iteration during prompt development, while the **<Icon icon="list-checks" /> Review** page is better for systematic evaluation of production logs and experiments.

## Capture production feedback

In addition to internal reviews, capture feedback directly from production users. Production feedback helps you understand real-world performance and build datasets from actual user interactions.

See [Capture user feedback](/instrument/user-feedback) for implementation details and [Build datasets from user feedback](/annotate/datasets#from-user-feedback) to learn how to turn feedback into evaluation datasets. You can also use [dashboards](/observe/dashboards) to monitor user satisfaction trends and correlate automated scores with user feedback.

## Customize the review table

### Show and hide columns

Select <Icon icon="settings-2" /> **Display** > **Columns** and then:

* Show or hide columns to focus on relevant data
* Reorder columns by dragging them
* Pin important columns to the left

All column settings are automatically saved when you save a view.

### Use kanban layout

The kanban layout organizes flagged spans into three columns based on their review status:

* **Backlog**: Spans flagged for review but not yet started
* **Pending**: Spans currently being reviewed
* **Complete**: Spans that have finished review

To use the kanban layout:

1. On the <Icon icon="list-checks" /> **Review** page, select <Icon icon="settings-2" /> **Display** > **Layout** > **Kanban**.
2. Drag cards between columns to update review status. Changes save automatically.
3. Click any card to open the full trace for detailed review.

Each card displays the span name, creation date, assignees, and a preview of the input and output.

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

* [Add labels and corrections](/annotate/labels) to categorize and tag traces
* [Build datasets](/annotate/datasets) from reviewed logs
* [Capture user feedback](/instrument/user-feedback) from production
* [Run evaluations](/evaluate/run-evaluations) with human-reviewed datasets
