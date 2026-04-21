> ## Documentation Index
> Fetch the complete documentation index at: https://braintrust.dev/docs/llms.txt
> Use this file to discover all available pages before exploring further.

# Filter and search logs

> Find specific traces using filters, SQL, and API access

Braintrust provides multiple ways to filter and search your logs, from quick UI filters to programmatic API access.

## Filter from the UI

Select <Icon icon="list-filter" /> **Filter** to open the filter menu with quick filters for common fields like tags, time range, and comments.

Use the **Basic** tab for point-and-click filtering, or switch to **SQL** to write precise queries. The SQL editor includes a <Icon icon="blend" /> **Generate** button that creates queries from natural language descriptions.

Some examples of what you can express in the SQL filter:

```sql theme={"theme":{"light":"github-light","dark":"github-dark-dimmed"}}
-- Only traces with a high factuality score
WHERE scores.Factuality > 0.8

-- Errors from a specific user in the last day
WHERE tags INCLUDES "error" AND metadata.user_id = "user-123" AND created >= "2024-01-01"

-- Slow requests
WHERE duration > 5.0
```

SQL syntax supports standard operators (`=`, `!=`, `>`, `<`, `>=`, `<=`), logical operators (`AND`/`and`, `OR`/`or`, `NOT`/`not`), `IN (...)` for set membership, and functions like `INCLUDES`/`includes` for array membership.

<Note>
  SQL queries on project logs enforce your plan's data retention limit. Use a relative interval to stay within the window. See [Plans and limits](/plans-and-limits) for retention details.
</Note>

<Note>
  When filtering traces by both tags and scores, use `ANY_SPAN()` to match traces where different spans contain different attributes. For example, `ANY_SPAN(tags INCLUDES "production") AND ANY_SPAN(scores.Factuality IS NOT NULL)` finds traces where any span has the tag and any span has the score. See [Analyze based on tags and scores](/reference/sql#analyze-based-on-tags-and-scores) for examples.
</Note>

For complete filter syntax, see the [SQL reference](/reference/sql).

## Ask questions with Loop

Select <Icon icon="blend" /> **Loop** on the <Icon icon="activity" /> **Logs** page to ask natural language questions about your traces. Loop understands your data structure and can answer questions, identify patterns, and surface specific traces without writing any queries.

Example questions:

* "Show me traces where the user was confused"
* "Find requests that took longer than usual"
* "What are the most common error patterns?"

You can also select one or more rows and use <Icon icon="glasses" /> **Find similar traces**. Loop identifies common traits across the selected traces and returns semantically similar ones.

Loop is also available when viewing an individual trace. Ask questions like "Summarize this trace" or "Why did this request fail?" See [Analyze logs](/loop#analyze-logs) and [Analyze individual traces](/loop#analyze-individual-traces) for more details.

## Find traces with deep search

Deep search finds traces based on semantic similarity rather than keyword matching or exact filters. It's useful for discovering patterns, sentiment, and edge cases that SQL filters would miss. For example, you can find traces where users expressed frustration, even if the word "frustrated" never appears.

See [Use deep search](/observe/deep-search) for detailed examples and workflows.

## Query via the CLI

The [`bt` CLI](/reference/cli/quickstart) gives you two ways to work with logs from the terminal.

* [`bt view logs`](/reference/cli/view) is an interactive terminal UI, good for browsing and spot-checking during development or an on-call incident without opening a browser.
* [`bt sql`](/reference/cli/sql) is for when you need more power: run arbitrary SQL queries, pipe results into other tools, or integrate log queries into scripts and CI pipelines.

### Browse logs

Use [`bt view logs`](/reference/cli/view) to open a live, scrollable view of your project's logs. You can narrow the results with a search term, a filter expression, or a time window, all without leaving the terminal.

```bash theme={"theme":{"light":"github-light","dark":"github-dark-dimmed"}}
bt view logs --search "error"                        # Full-text search
bt view logs --filter "scores.Factuality < 0.5"      # Filter expression
bt view logs --window 30m                            # Last 30 minutes
```

### Run SQL queries

Use [`bt sql`](/reference/cli/sql) to run full SQL queries against your logs. Pass a query inline or pipe one in from a file.

```bash theme={"theme":{"light":"github-light","dark":"github-dark-dimmed"}}
bt sql "SELECT * FROM project_logs('my-project') WHERE scores.Factuality > 0.8 LIMIT 100"
```

```bash theme={"theme":{"light":"github-light","dark":"github-dark-dimmed"}}
cat analysis.sql | bt sql
```

For automated workflows, pass `--non-interactive` to skip the interactive interface and `--json` to get machine-readable output.

```bash theme={"theme":{"light":"github-light","dark":"github-dark-dimmed"}}
bt sql --non-interactive "SELECT count(*) FROM project_logs('my-project') WHERE tags INCLUDES 'error'" --json
```

### Download logs locally

Use [`bt sync pull`](/reference/cli/sync) to download logs to local NDJSON files for offline analysis, backup, or migration.

```bash theme={"theme":{"light":"github-light","dark":"github-dark-dimmed"}}
bt sync pull project_logs:my-project --window 24h                           # Last 24 hours
bt sync pull project_logs:my-project --filter "scores.Factuality < 0.5"     # Filtered subset
```

See [`bt sync`](/reference/cli/sync) for the full flag reference.

## Query via the API

Query logs programmatically using the Braintrust API for automation, integrations, and custom tooling.

### Basic filtering

Use the [project logs endpoint](/api-reference/logs/fetch-project-logs-get-form) for simple filters and programmatic access:

```bash theme={"theme":{"light":"github-light","dark":"github-dark-dimmed"}}
curl -X GET "https://api.braintrust.dev/v1/project/<PROJECT_ID>/logs" \
  -H "Authorization: Bearer <YOUR_API_KEY>" \
  -H "Content-Type: application/json"
```

### Advanced SQL queries

For complex queries, use the [Braintrust API](/reference/sql#api-access):

```bash theme={"theme":{"light":"github-light","dark":"github-dark-dimmed"}}
curl -X POST https://api.braintrust.dev/btql \
  -H "Authorization: Bearer <YOUR_API_KEY>" \
  -H "Content-Type: application/json" \
  -d '{
    "query": "select: * | from: project_logs('"'<PROJECT_ID>'"') | filter: scores.Factuality > 0.8 | limit: 100"
  }'
```

The API accepts these parameters:

* `query` (required): Your BTQL query string
* `fmt`: Response format (`json` or `parquet`, defaults to `json`)
* `tz_offset`: Timezone offset in minutes for correct day boundaries
* `audit_log`: Include audit log data

## Speed up log filtering

If you frequently filter on the same custom fields, you can index them to reduce query latency. Braintrust offers two options: A full-text index for broad search and subfield indexes for specific fields you filter on most.

1. Go to **<Icon icon="settings-2" /> Settings > Project > [<Icon icon="ellipsis" /> Advanced](https://www.braintrust.dev/app/~/configuration/advanced)**.
2. Under **Log search optimization**, enable the toggle to build a full-text index that speeds up text-based filter queries.
3. Under **Subfield indexing**, click **+ Add subfield index** for each field you filter on frequently.

   Braintrust auto-discovers candidate fields from your data (e.g., `metadata.session_id`). If a field doesn't appear, you can type it in directly. Subfield paths must start with `input`, `output`, `expected`, `metadata`, or `span_attributes`.
4. Click **Save and index**.
5. Enter how many days back to backfill (default: 3) and click **Save and backfill**.

The **Index status** section shows backfill progress as indexing runs in the background.

<Tip>
  Use [`search()`](/reference/sql#full-text-search) in SQL filters to query all text fields at once. It gets automatic bloom filter acceleration when log search optimization is on.
</Tip>

## Next steps

* [Use deep search](/observe/deep-search) for semantic queries
* [Score online](/evaluate/score-online) to evaluate filtered traces
* [Create dashboards](/observe/dashboards) with filtered metrics
* Read the [SQL reference](/reference/sql) for complete query syntax
