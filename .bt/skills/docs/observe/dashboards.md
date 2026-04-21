> ## Documentation Index
> Fetch the complete documentation index at: https://braintrust.dev/docs/llms.txt
> Use this file to discover all available pages before exploring further.

# Monitor with dashboards

> Visualize metrics and trends across logs and experiments

export const feature_0 = "Custom charts"

export const verb_0 = "are"

The <Icon icon="chart-no-axes-column" /> **Monitor** page provides custom dashboards that aggregate metrics across logs and experiments in your project. Track request counts, latency, token usage, costs, scores, topics, and custom metrics over time.

<img src="https://mintcdn.com/braintrust/b11zJxKLgN0Qiq8B/images/guides/monitor/monitor-overview.png?fit=max&auto=format&n=b11zJxKLgN0Qiq8B&q=85&s=4c4b117a23bf49845bd74197c4429dce" alt="Monitor page" width="3138" height="1372" data-path="images/guides/monitor/monitor-overview.png" />

## Filter and group data

Apply filters and groupings at the top of the page to affect all charts. This lets you focus on specific subsets of your data or compare different segments side by side. Filters apply to entire traces with any span match, while groups are only per span.

<img src="https://mintcdn.com/braintrust/b11zJxKLgN0Qiq8B/images/guides/monitor/monitor-filter-group.png?fit=max&auto=format&n=b11zJxKLgN0Qiq8B&q=85&s=eb5fee0899516fb798f0ccd75a4381d2" alt="Monitor page" width="1442" height="154" data-path="images/guides/monitor/monitor-filter-group.png" />

## Create custom charts

Select **+ Chart** to open the chart editor, or select the <Icon icon="pencil" /> icon on any chart to customize it. The editor exposes the following options:

* **Chart type**: Determines the visualization and which other options are available:
  * [**<Icon icon="chart-column" /> Time series**](#time-series): Plot metrics over time as lines or stacked bars.
  * [**<Icon icon="arrow-down-wide-narrow" /> Top list**](#top-list): Rank groups by a metric over the selected timeframe.
  * [**<Icon icon="hash" /> Big number**](#big-number): Display a single aggregate value prominently.
  * [**<Icon icon="settings-2" /> Presets**](#presets): Start from a built-in chart covering common metrics like request count, latency, token usage, and scores.
* **Title**: A label displayed above the chart.
* **Measures**: What to plot. Select a [SQL expression](/reference/sql#group-by-for-aggregations) and aggregator (sum, avg, min, max, count, count distinct, or percentile), or click `</>` to enter a full SQL aggregate expression such as `avg(latency) / count_distinct(user_id)` or `100 * sum(errors) / count(id)`. Invalid expressions display a validation error on hover. Built-in measure types are also available for [aggregate scores](/admin/projects#create-aggregate-scores) and cost.
* **Trace filters**: Narrow results to traces where any span satisfies the [filter conditions](/reference/sql#where). Useful for filtering by root-span metadata (e.g. `metadata.email`).
* **Span filters**: Narrows results to individual spans that satisfy the filter conditions. Only matching spans contribute to the measure.
* **Group by**: Splits the chart into separate series by a SQL dimension (e.g. `metadata.model`). Available for time series and top list charts.
* **Options**: Controls visualization-specific settings:
  * **Unit type**: Choose how values appear in chart axes, tooltips, and legengs:
    * **Duration**: Seconds (e.g., "1.5s", "0.3s")
    * **Cost**: US dollars (e.g., "$0.05", "$1.23")
    * **Count**: Generic countable things (e.g., "1,234", "5.5")
    * **Percent**: Percentages (e.g., "75%", "100%")
    * **Bytes**: Binary byte units using base-1024 (e.g., "1 KB", "2 GB", "500 B")
  * **Visualization**: Visualize as lines or bars. Available for time series charts only.
  * **Sort**: Sort by value or name, ascending or descending. Available for top list charts only.

<Tip>
  Use [<Icon icon="blend" /> **Loop**](/loop) to create charts from natural language descriptions. Example queries:

  * "List the top 5 models by error rate over the last 7 days"
  * "Show error rate over time for claude models"
  * "Display average latency grouped by user"
  * "Chart token usage trends for the past month"
</Tip>

<Note>
  {feature_0} {verb_0} only available on [Pro and Enterprise plans](/plans-and-limits#plans).
</Note>

### Time series

Visualize data over time with lines or stacked bars. Time series charts help you spot trends, anomalies, and correlations.

<img src="https://mintcdn.com/braintrust/b11zJxKLgN0Qiq8B/images/guides/monitor/monitor-custom-chart-editor.png?fit=max&auto=format&n=b11zJxKLgN0Qiq8B&q=85&s=3501bf16d5c08ef0fd488a9f6a878304" alt="Monitor page" width="2458" height="1598" data-path="images/guides/monitor/monitor-custom-chart-editor.png" />

<img src="https://mintcdn.com/braintrust/b11zJxKLgN0Qiq8B/images/guides/monitor/monitor-custom-chart-editor-2.png?fit=max&auto=format&n=b11zJxKLgN0Qiq8B&q=85&s=271c946045d98ab9d8238da7a05d088e" alt="Monitor page" width="2386" height="1560" data-path="images/guides/monitor/monitor-custom-chart-editor-2.png" />

### Top list

Show values of multiple groups over the entire timeframe. Order by value or alphabetically (ascending or descending).

<div style={{maxWidth: '600px'}}>
  <img src="https://mintcdn.com/braintrust/b11zJxKLgN0Qiq8B/images/guides/monitor/monitor-custom-chart-top-list.jpg?fit=max&auto=format&n=b11zJxKLgN0Qiq8B&q=85&s=9d2676087b3667ddc71ee231415c6e38" alt="Monitor page" width="964" height="708" data-path="images/guides/monitor/monitor-custom-chart-top-list.jpg" />
</div>

### Big number

Display a single aggregate value as one large number. Useful for highlighting key metrics like total requests or average score.

<div style={{maxWidth: '600px'}}>
  <img src="https://mintcdn.com/braintrust/b11zJxKLgN0Qiq8B/images/guides/monitor/monitor-custom-chart-big-number.jpg?fit=max&auto=format&n=b11zJxKLgN0Qiq8B&q=85&s=9cde51ff57b4a6c5cf4a94ef9efb3e05" alt="Monitor page" width="820" height="698" data-path="images/guides/monitor/monitor-custom-chart-big-number.jpg" />
</div>

### Presets

Preset charts are included by default on the **Monitor** page, covering common metrics like request count, latency, token usage, and scores.

<div style={{maxWidth: '600px'}}>
  <img src="https://mintcdn.com/braintrust/b11zJxKLgN0Qiq8B/images/guides/monitor/monitor-custom-chart-editor-presets.jpg?fit=max&auto=format&n=b11zJxKLgN0Qiq8B&q=85&s=f7a2cd43f4ac4b0c44b97ec783acacc3" alt="Monitor page" width="966" height="586" data-path="images/guides/monitor/monitor-custom-chart-editor-presets.jpg" />
</div>

<Note>
  Some preset charts (Spans, Latency, Total LLM cost, Token count, and Time to first token) automatically exclude internal scorer spans generated by online scoring automations such as [Topics](/observe/topics) and [online scorers](/observe/score-online). This ensures the metrics reflect actual production traffic rather than scoring overhead. Custom charts include all spans by default — add a `span_attributes.purpose is null or span_attributes.purpose != 'scorer'` filter to replicate the same behavior.
</Note>

## Select timeframes

Choose from preset timeframes or click and drag horizontally on time series charts to zoom into a specific period. Double-click to zoom out.

<img src="https://mintcdn.com/braintrust/b11zJxKLgN0Qiq8B/images/guides/monitor/monitor-timeframe.png?fit=max&auto=format&n=b11zJxKLgN0Qiq8B&q=85&s=d11a7a6d7173000c85bba30c80ac6468" alt="Monitor page" width="604" height="728" data-path="images/guides/monitor/monitor-timeframe.png" />

## View traces

Select any data point on a chart to navigate to the logs or experiments page, filtered to the corresponding time range and series. This lets you quickly investigate specific data points.

## Create custom dashboards

The built-in "All data" view shows all data for your project. To create a custom dashboard, you can either:

* **Edit a chart on the built-in view**: When you add or edit a chart on the "All data" view, the editor will prompt you to name and save a new dashboard before your changes are applied.
* **Duplicate the current view**: Use the <Icon icon="layers-2" /> menu in the top left to duplicate the current view and save it as a new dashboard.

Once you're on a custom dashboard, adding, editing, and removing charts saves automatically.

<Note>
  Creating and editing custom dashboards requires the **Projects > Update** permission in your organization's [permission groups](/admin/access-control). Project-level permissions are not sufficient because dashboards are scoped to the organization, not individual projects.
</Note>

## Set default dashboards

You can set default dashboards at two levels:

* **Organization default**: Visible to all members when they open the Monitor page. To set an organization default, you need the **Manage settings** organization permission (included by default in the **Owner** role). See [Access control](/admin/access-control) for details.
* **Personal default**: Overrides the organization default for you only. Personal defaults are stored in your browser, so they do not carry over across devices or browsers.

To set a default dashboard:

1. Switch to the dashboard you want by selecting it from the <Icon icon="layers-2" /> menu in the top left.
2. Open the menu again and hover over the currently selected dashboard to reveal its submenu.
3. Choose <Icon icon="flag-triangle-right" /> **Set as personal default view** or <Icon icon="pin" /> **Set as organization default view**.

To clear a default dashboard:

1. Open the <Icon icon="layers-2" /> menu and hover over the currently selected dashboard to reveal its submenu.
2. Choose <Icon icon="flag-triangle-right" /> **Clear personal default view** or <Icon icon="pin" /> **Clear organization default view**.

When a user opens the Monitor page, Braintrust loads the first match in this order: personal default, organization default, then the built-in "All data" view.

## Next steps

* [Use the Loop](/loop) to ask questions about your data
* [Score online](/evaluate/score-online) to add quality metrics to dashboards
* [Build datasets](/annotate/datasets) from patterns you identify
* Read the [SQL reference](/reference/sql) for advanced queries
