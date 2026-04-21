> ## Documentation Index
> Fetch the complete documentation index at: https://braintrust.dev/docs/llms.txt
> Use this file to discover all available pages before exploring further.

# Observe your application

> View, analyze, and monitor production traces in real time

After instrumenting your application, Braintrust captures every request as traces that you can view, filter, and analyze in real time. This observability enables you to monitor production behavior, identify issues, and gather data for improving your application.

## Why observe in Braintrust

Observability in Braintrust creates a feedback loop between production and evaluation. Logs use the same data structure as experiments, which means:

* Instrumentation code works for both logging and evaluation
* Traces capture identical data in production and testing
* Scores and feedback apply to both logs and experiments
* Production data seamlessly becomes evaluation datasets

This unified structure lets you iterate faster and maintain consistency across your development workflow.

## View your logs

The <Icon icon="activity" /> **Logs** page displays all traces from your application in a searchable, filterable table. Each row represents a complete trace with its root span.

<img src="https://mintcdn.com/braintrust/FJKP8dcMkQrpeBHe/images/core/logs/logs.png?fit=max&auto=format&n=FJKP8dcMkQrpeBHe&q=85&s=917cb4c6548336700500ad28b6cb8494" alt="Logging Screenshot" width="2708" height="1416" data-path="images/core/logs/logs.png" />

You can:

* Browse traces and individual spans
* Group related traces by metadata or tags
* Create custom columns to surface important values
* Extract prompts to iterate in playgrounds
* Apply tags to organize traces

## Discover insights with Topics

<Icon icon="pentagon" /> **Topics** automatically analyze and classify your logs to surface patterns without manual review. Each trace is analyzed by **facets** that extract short labels, then similar labels are clustered into named **topics** like user intents, sentiment, and issues.

Topics help you:

* Surface user intents automatically
* Identify friction patterns across interactions
* Track sentiment trends over time
* Analyze recurring issues

Built-in facets include Task (user intents), Sentiment (emotional tone), and Issues (agent problems). You can also create custom facets for domain-specific analysis.

See [Discover insights](/observe/topics) for details.

## Filter and search

Find specific traces using multiple approaches:

* **Filter menu**: Quick filters and SQL queries for precise matching
* **Deep search**: Semantic search to find traces by meaning, not just keywords
* **Loop**: Ask natural language questions about your logs
* **API**: Programmatic access for automation
* **CLI**: Browse interactively or run SQL queries from the terminal with `bt view logs` and `bt sql`

## Monitor with dashboards

Custom dashboards aggregate metrics across your logs and experiments. Track request counts, latency, token usage, costs, scores, and custom metrics over time.

Dashboards help you:

* Visualize trends and anomalies
* Compare performance across time periods
* Drill into specific data points
* Share insights with your team

## Use Loop

[<Icon icon="blend" /> **Loop**](/loop) is Braintrust's AI agent that understands your data structure and helps you explore logs through natural language. Available on both the <Icon icon="activity" /> **Logs** page and individual trace pages, Loop lets you ask questions, identify patterns, and get insights without writing queries.

See [Analyze logs](/loop#analyze-logs) and [Analyze individual traces](/loop#analyze-individual-traces) for more details.

## Next steps

* [View your logs](/observe/view-logs) in the Braintrust dashboard
* [Discover insights with Topics](/observe/topics)
* [Use Loop](/loop) to analyze logs with natural language
* [Filter and search](/observe/filter) for specific traces
* [Use deep search](/observe/deep-search) for semantic queries
* [Score online](/evaluate/score-online) to evaluate production quality
