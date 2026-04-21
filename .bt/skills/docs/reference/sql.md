> ## Documentation Index
> Fetch the complete documentation index at: https://braintrust.dev/docs/llms.txt
> Use this file to discover all available pages before exploring further.

# SQL queries

SQL queries in Braintrust provide a precise, standard syntax for querying Braintrust logs, experiments, and datasets. Use SQL to:

* Filter and search for relevant logs and experiments. Use [`WHERE` clauses](#where) to filter individual records and [`HAVING` clauses](#having-for-filtering-aggregations) to filter aggregated results after grouping.
* Create consistent, reusable queries for monitoring.
* Build automated reporting and analysis pipelines.
* Write complex queries to analyze model performance.

Braintrust supports two syntax styles: standard **SQL syntax**, and the legacy **BTQL syntax** with pipe-delimited clauses. The parser automatically detects which style you're using. SQL syntax is recommended for all new queries.

<Note>
  **[Self-hosted deployments](/admin/self-hosting)**: SQL syntax support requires data plane version v1.1.29 or later.
</Note>

## Run SQL queries

### SQL sandbox

To test SQL with autocomplete, validation, and a table of results, use the <Icon icon="asterisk" /> **SQL sandbox** in your project.

In the sandbox, you can use [<Icon icon="blend" /> **Loop**](/loop) to generate and optimize queries from natural language:

Example queries:

* "Find the most common errors in logs over the last week"
* "What are the highest scoring rows in my experiment"
* "Show me error distribution over time"
* "List all traces where latency exceeded 60 seconds"

Loop automatically populates the sandbox with the generated query, runs it, and provides a text summary of the results along with suggestions for additional queries.

Once you have a query in the sandbox, ask Loop to refine it:

* "Update the query to show error distribution over time"
* "Add a filter to only show errors from specific models"
* "Group by user instead"

When your query has errors, Loop can help fix them. Select **Fix with Loop** next to the error message in the sandbox. Loop analyzes the issue type and context to provide targeted fixes for:

* Syntax errors
* Schema validation issues
* Field name corrections

<Note>
  If a `project_logs()` query is missing a range filter on `created`, `_xact_id`, `_pagination_key`, or a specific `root_span_id`/`id`, the sandbox proactively warns you so you don't have to wait for a timeout to discover the issue.
</Note>

### API access

Access SQL programmatically with the Braintrust API:

<CodeGroup>
  ```bash cURL theme={"theme":{"light":"github-light","dark":"github-dark-dimmed"}}
  curl -X POST https://api.braintrust.dev/btql \
    -H "Authorization: Bearer <YOUR_API_KEY>" \
    -H "Content-Type: application/json" \
    -d '{"query": "select: * | from: project_logs('"'<YOUR_PROJECT_ID>'"') | filter: tags INCLUDES '"'triage'"' AND created > now() - interval 7 day"}'
  ```
</CodeGroup>

The API accepts these parameters:

* `query` (required): your SQL query string.
* `fmt`: response format (`json` or `parquet`, defaults to `json`).
* `tz_offset`: timezone offset in minutes for time-based operations.
* `audit_log`: include audit log data.
* `version`: an `_xact_id` string to query data as it existed at a specific point in time (useful for [recovering deleted rows](/kb/recovering-deleted-experiment-rows)). Supported for `experiment` and `dataset` sources; not supported for `project_logs`.

<Note>
  For correct day boundaries, set `tz_offset` to match your timezone. For example, use `480` for US Pacific Standard Time.
</Note>

## Query structure

SQL queries follow a familiar structure that lets you define what data you want, how you want it returned, and how to analyze it.

This example returns logs from the last 7 days from a project where Factuality is greater than 0.8, sorts by created date descending, and limits the results to 100.

```sql theme={"theme":{"light":"github-light","dark":"github-dark-dimmed"}}
SELECT *
FROM project_logs('<PROJECT_ID>', shape => 'spans')
WHERE scores.Factuality > 0.8
  AND created > now() - interval 7 day
ORDER BY created DESC
LIMIT 100
```

* `SELECT` / `select:`: choose which fields to retrieve
* `FROM` / `from:`: specify the data source. Has an optional designator for the shape of the data: `spans`, `traces`, `summary`. If not specified, defaults to `spans`
* `WHERE` / `filter:`: define conditions to filter the data
* `GROUP BY` / `dimensions:`: group rows for aggregation
* `HAVING` / `final_filter:`: filter aggregated results
* `sample:`: (BTQL-only) randomly sample a subset of the filtered data (rate or count-based)
* `ORDER BY` / `sort:`: set the order of results (`ASC`/`DESC` or `asc`/`desc`)
* `LIMIT` / `limit:`: control result size
* `OFFSET '<CURSOR_TOKEN>'` / `cursor:`: pagination token from the previous query response (SQL `OFFSET` must be a string literal cursor token; use with cursor-compatible sorts such as `_pagination_key` or `_xact_id`)

<Warning>
  Always include a range filter (`created`, `_xact_id`, or `_pagination_key`) or scope to a specific `root_span_id`/`id` in every `project_logs()` query. Without one, queries scan your entire project history and will be slow or time out on large datasets.
</Warning>

### BTQL syntax

Braintrust also supports BTQL, an alternative pipe-delimited clause syntax. The parser automatically detects whether your query is SQL or BTQL:

* **SQL queries** start with `SELECT`, `WITH`, etc. followed by whitespace
* **BTQL queries** use clause syntax like `select:`, `filter:`, etc.

| SQL Clause                            | BTQL Clause                |
| ------------------------------------- | -------------------------- |
| `SELECT ...`                          | `select: ...`              |
| `FROM table('id', shape => 'traces')` | `from: table('id') traces` |
| `WHERE ...`                           | `filter: ...`              |
| `GROUP BY ...`                        | `dimensions: ...`          |
| `HAVING ...`                          | `final_filter: ...`        |
| `ORDER BY ...`                        | `sort: ...`                |
| `LIMIT n`                             | `limit: n`                 |
| `OFFSET '<CURSOR_TOKEN>'`             | `cursor: '<CURSOR_TOKEN>'` |

<Note>
  SQL syntax specifies the shape with a named parameter (e.g., `FROM experiment('id', shape => 'traces')`), while BTQL uses a trailing token (e.g., `from: experiment('id') traces`). Table aliases (e.g., `AS t`) are reserved for future use.
</Note>

<Note>
  **Full-text search:** Use the `MATCH` infix operator for full-text search:

  * `WHERE input MATCH 'search term'` → `filter: input MATCH 'search term'`
  * Multiple columns require OR: `WHERE input MATCH 'x' OR output MATCH 'x'` → `filter: input MATCH 'x' OR output MATCH 'x'`
</Note>

<Warning>
  **Unsupported SQL features:** The SQL parser does not support `JOIN`, subqueries, `UNION`/`INTERSECT`/`EXCEPT`, or window functions. For `PIVOT`, only `IN (ANY)` is supported (explicit value lists, subqueries, and `ORDER BY` are not supported). The `INCLUDES` and `CONTAINS` operators are also not supported in SQL mode — use BTQL's `filter: tags INCLUDES 'value'` syntax instead, or `MATCH` as a fuzzy approximation. Use BTQL's native syntax for queries that would require these features.
</Warning>

### `FROM` data source options

The `FROM` clause in SQL specifies the data source for your query.

* `experiment('<experiment_id1>', <experiment_id2>)`: a specific experiment or list of experiments
* `dataset('<dataset_id1>', <dataset_id2>)`: a specific dataset or list of datasets
* `prompt('<prompt_id1>', <prompt_id2>)`: a specific prompt or list of prompts
* `function('<function_id1>', <function_id2>)`: a specific function or list of functions
* `view('<view_id1>', <view_id2>)`: a specific saved view or list of saved views
* `project_logs('<project_id1>', <project_id2>)`: all logs for a specific project or list of projects
* `project_prompts('<project_id1>', <project_id2>)`: all prompts for a specific project or list of projects
* `project_functions('<project_id1>', <project_id2>)`: all functions for a specific project or list of projects
* `org_prompts('<org_id1>', <org_id2>)`: all prompts for a specific organization or list of organizations
* `org_functions('<org_id1>', <org_id2>)`: all functions for a specific organization or list of organizations

## Retrieve records

When retrieving records with SQL, you can either use `SELECT` or `SELECT ... GROUP BY`. You can use most tools when using either method, but you must use `GROUP BY` if you want to aggregate functions to retrieve results.

Both retrieval methods work with all data shapes (`spans`, `traces`, and `summary`). Using `GROUP BY` with the `summary` shape enables trace-level aggregations.

### `SELECT`

`SELECT` in SQL lets you choose specific fields, compute values, or use `*` to retrieve every field.

```sql theme={"theme":{"light":"github-light","dark":"github-dark-dimmed"}}
-- Get specific fields
SELECT
  metadata.model AS model,
  scores.Factuality AS score,
  created AS timestamp
FROM project_logs('my-project-id')
WHERE created > now() - interval 7 day
```

<Note>
  **Implicit aliasing**: Multi-part identifiers like `metadata.model` automatically create implicit aliases using their last component (e.g., `model`), which you can use in `WHERE`, `ORDER BY`, and `GROUP BY` clauses when unambiguous. See [Field access](#implicit-aliasing) for details.
</Note>

SQL allows you to transform data directly in the `SELECT` clause. This query returns `metadata.model`, whether `metrics.tokens` is greater than 1000, and a quality indicator of either "high" or "low" depending on whether or not the Factuality score is greater than 0.8.

```sql theme={"theme":{"light":"github-light","dark":"github-dark-dimmed"}}
SELECT
  -- Simple field access
  metadata.model,

  -- Computed values
  metrics.tokens > 1000 AS is_long_response,

  -- Conditional logic
  (scores.Factuality > 0.8 ? "high" : "low") AS quality
FROM project_logs('my-project-id')
WHERE created > now() - interval 7 day
```

You can also use functions in the `SELECT` clause to transform values and create meaningful aliases for your results. This query extracts the day the log was created, the hour, and a Factuality score rounded to 2 decimal places.

```sql theme={"theme":{"light":"github-light","dark":"github-dark-dimmed"}}
SELECT
  -- Date/time functions
  day(created) AS date,
  hour(created) AS hour,

  -- Numeric calculations
  round(scores.Factuality, 2) AS rounded_score
FROM project_logs('my-project-id')
WHERE created > now() - interval 7 day
```

### `GROUP BY` for aggregations

Instead of a simple `SELECT`, you can use `SELECT ... GROUP BY` to group and aggregate data. This query returns a row for each distinct model with the day it was created, the total number of calls, the average Factuality score, and the latency percentile.

```sql theme={"theme":{"light":"github-light","dark":"github-dark-dimmed"}}
-- Analyze model performance over time
SELECT
  metadata.model AS model,
  day(created) AS date,
  count(1) AS total_calls,
  avg(scores.Factuality) AS avg_score,
  percentile(latency, 0.95) AS p95_latency
FROM project_logs('my-project-id')
WHERE created > now() - interval 7 day
GROUP BY metadata.model, day(created)
```

The available aggregate functions are:

* `count(expr)`: number of rows
* `count_distinct(expr)`: number of distinct values
* `sum(expr)`: sum of numeric values
* `avg(expr)`: mean (average) of numeric values
* `min(expr)`: minimum value
* `max(expr)`: maximum value
* `any_value(expr)`: an arbitrary non-null value from the group for the given expression
* `percentile(expr, p)`: a percentile where `p` is between 0 and 1

<Note>
  `LIMIT` works with `GROUP BY` queries to restrict the number of grouped results returned. When combined with `ORDER BY`, rows are sorted before limiting. See [LIMIT](#limit) for examples.
</Note>

### `HAVING` for filtering aggregations

`HAVING` filters the results after aggregation, letting you narrow down grouped data based on aggregate values. Use `HAVING` with `GROUP BY` when you need to filter by aggregated metrics like counts, averages, or sums.

This query returns models with high average scores:

```sql theme={"theme":{"light":"github-light","dark":"github-dark-dimmed"}}
-- Find models with strong performance
SELECT
  metadata.model AS model,
  avg(scores.Factuality) AS avg_score,
  count(1) AS total_calls
FROM project_logs('my-project-id')
WHERE created > now() - interval 7 day
GROUP BY metadata.model
HAVING avg(scores.Factuality) > 0.8
  AND count(1) > 100
ORDER BY avg_score DESC
```

You can combine `WHERE` and `HAVING` to filter both before and after aggregation. This query filters individual logs before grouping, then filters the aggregated results:

```sql theme={"theme":{"light":"github-light","dark":"github-dark-dimmed"}}
-- Track high-performing production models
SELECT
  metadata.model AS model,
  day(created) AS date,
  avg(scores.Factuality) AS avg_score,
  count(1) AS call_count
FROM project_logs('my-project-id')
WHERE metadata.is_production = true
  AND created > now() - interval 7 day
GROUP BY metadata.model, day(created)
HAVING avg(scores.Factuality) > 0.7
  AND count(1) >= 10
ORDER BY date DESC, avg_score DESC
```

`HAVING` supports the same [operators](#sql-operators) and [aggregate functions](#group-by-for-aggregations) as other clauses. You can reference aggregated values by their alias or by repeating the aggregate expression.

## `FROM`

The `FROM` clause identifies where the records are coming from. This can be an identifier like `project_logs` or a function call like `experiment("id")`.

You can add an optional parameter to the `FROM` clause that defines how the data is returned. The options are `spans` (default), `traces`, and `summary`.

### `spans`

`spans` returns individual spans that match the filter criteria. This example returns 10 LLM call spans that took more than 0.2 seconds to use the first token.

```sql theme={"theme":{"light":"github-light","dark":"github-dark-dimmed"}}
SELECT *
FROM project_logs('my-project-id', shape => 'spans')
WHERE span_attributes.type = 'llm' AND metrics.time_to_first_token > 0.1
  AND created > now() - interval 7 day
LIMIT 10
```

The response is an array of spans. Check out the [Extend traces](/instrument/advanced-tracing#underlying-format) page for more details on span structure.

### `traces`

`traces` returns all spans from traces that contain at least one matching span. This is useful when you want to see the full context of a specific event or behavior, for example if you want to see all spans in traces where an error occurred.

This example returns all spans for a specific trace where one span in the trace had an error.

```sql theme={"theme":{"light":"github-light","dark":"github-dark-dimmed"}}
SELECT *
FROM project_logs('my-project-id', shape => 'traces')
WHERE root_span_id = 'trace-id' AND error IS NOT NULL
```

The response is an array of spans. Check out the [Extend traces](/instrument/advanced-tracing#underlying-format) page for more details on span structure.

### `summary`

`summary` provides trace-level views of your data by aggregating metrics across all spans in a trace. This shape is useful for analyzing overall performance and comparing results across experiments.

The `summary` shape can be used in two ways:

* **Individual trace summaries** (using `SELECT`): Returns one row per trace with aggregated span metrics. Use this to see trace-level details. Example: "What are the details of traces with errors?"
* **Aggregated trace analytics** (using `GROUP BY`): Groups multiple traces and computes statistics. Use this to analyze patterns across many traces. Example: "What's the average cost per model per day?"

#### Individual trace summaries

Use `SELECT` with the `summary` shape to retrieve individual traces with aggregated metrics. This is useful for inspecting specific trace details, debugging issues, or exporting trace-level data.

This example returns 10 summary rows from the project logs for 'my-project-id':

```sql theme={"theme":{"light":"github-light","dark":"github-dark-dimmed"}}
SELECT *
FROM project_logs('my-project-id', shape => 'summary')
WHERE created > now() - interval 7 day
LIMIT 10
```

Summary rows include some aggregated metrics and some preview fields that show data from the root span of the trace.

The following fields are aggregated metrics across all spans in the trace.

* `scores`: an object with all scores averaged across all spans
* `metrics`: an object with aggregated metrics across all spans
  * `prompt_tokens`: total number of prompt tokens used
  * `completion_tokens`: total number of completion tokens used
  * `prompt_cached_tokens`: total number of cached prompt tokens used
  * `prompt_cache_creation_tokens`: total number of tokens used to create cache entries
  * `total_tokens`: total number of tokens used (prompt + completion)
  * `estimated_cost`: total estimated cost of the trace in US dollars (prompt + completion costs)
  * `llm_calls`: total number of LLM calls
  * `tool_calls`: total number of tool calls
  * `errors`: total number of errors (LLM + tool errors)
  * `llm_errors`: total number of LLM errors
  * `tool_errors`: total number of tool errors
  * `start`: Unix timestamp of the first span start time
  * `end`: Unix timestamp of the last span end time
  * `duration`: maximum duration of any span in seconds. Note: this is not the total trace duration.
  * `llm_duration`: sum of all durations across LLM spans in seconds
  * `time_to_first_token`: the average time to first token across LLM spans in seconds
* `span_type_info`: an object with span type info. Some fields in this object are aggregated across all spans and some reflect attributes from the root span.
  * `cached`: true only if all LLM spans were cached
  * `has_error`: true if any span had an error

Root span preview fields include `input`, `output`, `expected`, `error`, and `metadata`.

#### Aggregated trace analytics

Use `GROUP BY` with the `summary` shape to group and aggregate traces. This is useful for analyzing patterns, monitoring performance trends, and comparing metrics across models or time periods.

This example shows how to group traces by model to track performance over time:

```sql theme={"theme":{"light":"github-light","dark":"github-dark-dimmed"}}
SELECT
  metadata.model AS model,
  day(created) AS date,
  count(1) AS trace_count,
  avg(scores.Factuality) AS avg_score,
  avg(estimated_cost()) AS avg_cost
FROM project_logs('my-project-id', shape => 'summary')
WHERE created > now() - interval 7 day
GROUP BY 1, 2
ORDER BY date DESC
```

## `WHERE`

The `WHERE` clause lets you specify conditions to narrow down results. It supports a wide range of [operators](#sql-operators) and [functions](#sql-functions), including complex conditions.

This example `WHERE` clause only retrieves data where:

* Factuality score is greater than 0.8
* model is "gpt-4"
* tag list contains "triage" (using `MATCH` for SQL mode; use BTQL `INCLUDES` for exact matching)
* input contains the word "question" (case-insensitive)
* created date is later than January 1, 2024
* more than 1000 tokens were used or the data being traced was made in production

```sql theme={"theme":{"light":"github-light","dark":"github-dark-dimmed"}}
SELECT *
FROM project_logs('my-project-id')
WHERE
  -- Simple comparisons
  scores.Factuality > 0.8 AND
  metadata.model = 'gpt-4' AND

  -- Array operations (MATCH approximates inclusion; use BTQL INCLUDES for exact)
  tags MATCH 'triage' AND

  -- Text search (case-insensitive)
  input ILIKE '%question%' AND

  -- Date ranges
  created > '2024-01-01' AND

  -- Complex conditions
  (
    metrics.tokens > 1000 OR
    metadata.is_production = true
  )
```

### Single span filters

By default, each returned trace includes at least one span that matches all filter conditions. Use `ANY_SPAN()` to wrap any filter expression and find traces where at least one span matches the specified condition.

Single span filters work with the `traces` and `summary` data shapes.

```sql theme={"theme":{"light":"github-light","dark":"github-dark-dimmed"}}
-- Traces where one span has an error and another is an LLM call
WHERE
  ANY_SPAN(error IS NOT NULL) AND
  ANY_SPAN(span_attributes.type = 'llm')
```

`ANY_SPAN()` can be combined with `GROUP BY` to aggregate traces based on span-level conditions. This is useful for analyzing patterns across traces that contain specific types of spans. See [Analyze based on tags and scores](#analyze-based-on-tags-and-scores), [Analyze based on tags](#analyze-based-on-tags), and [Analyze traces with span filters](#analyze-traces-with-span-filters) for examples.

<Note>
  By default, `ANY_SPAN()` matches against all spans in a trace. To restrict matching to only root spans, add `is_root` to the condition: `ANY_SPAN(is_root AND error IS NOT NULL)`.
</Note>

`ANY_SPAN()` supports one level of nesting. Nested calls are flattened, which allows query builders and the Braintrust UI to compose filters by wrapping conditions in `ANY_SPAN()`. For example:

```sql theme={"theme":{"light":"github-light","dark":"github-dark-dimmed"}}
-- Exclude traces that contain a score span named "null_score"
WHERE NOT ANY_SPAN(span_attributes.type = 'score' AND ANY_SPAN(span_attributes.name = 'null_score'))
```

Triple or deeper nesting (`ANY_SPAN(ANY_SPAN(ANY_SPAN(...)))`) is not supported. `NOT ANY_SPAN()` does not support multiple span filter clauses — for example, `NOT ANY_SPAN(ANY_SPAN(a) AND ANY_SPAN(b))` is not supported.

### Matching spans filters

While `ANY_SPAN()` helps you find traces you care about, matching spans filters let you filter spans within the traces you've already found. Use `FILTER_SPANS()` to return only the matching spans from those traces, rather than entire traces. This is analogous to `trace_filter` in BTQL.

Matching spans filters work with the `traces` and `summary` data shapes. On the `spans` shape, `FILTER_SPANS()` acts as a no-op wrapper.

```sql theme={"theme":{"light":"github-light","dark":"github-dark-dimmed"}}
-- Return only score spans from traces
WHERE FILTER_SPANS(span_attributes.type = 'score')

-- Return only spans where Factuality equals 1
WHERE FILTER_SPANS(scores.Factuality = 1)
```

You can combine `FILTER_SPANS()` with other filter conditions:

```sql theme={"theme":{"light":"github-light","dark":"github-dark-dimmed"}}
-- Return score spans from traces with duration over 1 second
WHERE
  FILTER_SPANS(span_attributes.type = 'score') AND
  metrics.duration > 1
```

### Full-text search

Use `MATCH` to search a specific field for exact word matches, or `search()` to search across all text fields at once.

```sql theme={"theme":{"light":"github-light","dark":"github-dark-dimmed"}}
-- Search a specific field for exact word matches
WHERE output MATCH 'timeout'

-- Search across all text fields (input, output, expected, metadata, span_attributes)
WHERE search('timeout')

-- Combine with other filters
WHERE search('error') AND metadata.environment = 'production'
```

`search()` is equivalent to writing `input MATCH query OR output MATCH query OR ...` for each text field. When [log search optimization](/admin/projects#speed-up-log-filtering) is enabled, `search()` also benefits from bloom filter acceleration to skip irrelevant segments.

### Pattern matching

SQL supports the `%` wildcard for pattern matching with `LIKE` (case-sensitive) and `ILIKE` (case-insensitive).

The `%` wildcard matches any sequence of zero or more characters.

```sql theme={"theme":{"light":"github-light","dark":"github-dark-dimmed"}}
-- Match any input containing "question"
WHERE input ILIKE '%question%'

-- Match inputs starting with "How"
WHERE input LIKE 'How%'

-- Match emails ending with specific domains
WHERE metadata.email ILIKE '%@braintrust.com'

-- Escape literal wildcards with backslash
WHERE metadata.description LIKE '%50\% off%'  -- Matches "50% off"
```

### Time intervals

SQL supports intervals for time-based operations.

This query returns all project logs from 'my-project-id' that were created in the last day.

```sql theme={"theme":{"light":"github-light","dark":"github-dark-dimmed"}}
SELECT *
FROM project_logs('my-project-id')
WHERE created > now() - interval 1 day
```

This query returns all project logs from 'my-project-id' that were created up to an hour ago.

```sql theme={"theme":{"light":"github-light","dark":"github-dark-dimmed"}}
SELECT *
FROM project_logs('my-project-id')
WHERE created > now() - interval 1 hour
  AND created < now()
```

This query returns all project logs from 'my-project-id' that were created within the last month but not within the last week

```sql theme={"theme":{"light":"github-light","dark":"github-dark-dimmed"}}
-- Examples with different units
SELECT *
FROM project_logs('my-project-id')
WHERE created < now() - interval 7 day    -- Last week
  AND created > now() - interval 1 month  -- Last month
```

## `ORDER BY`

The `ORDER BY` clause determines the order of results. The options are `DESC` (descending) and `ASC` (ascending) on a numerical field. You can sort by a single field, multiple fields, or computed values.

```sql theme={"theme":{"light":"github-light","dark":"github-dark-dimmed"}}
-- Sort by single field
ORDER BY created DESC

-- Sort by multiple fields
ORDER BY scores.Factuality DESC, created ASC

-- Sort by computed values
ORDER BY len(tags) DESC
```

## `PIVOT` and `UNPIVOT`

`PIVOT` and `UNPIVOT` are advanced operations that transform your results for easier analysis and comparison. Both SQL and BTQL syntax support these operations.

### `PIVOT`

`PIVOT` transforms rows into columns, which makes comparisons easier by creating a column for each distinct value. This is useful when comparing metrics across different categories, models, or time periods.

**Structure:**

```sql theme={"theme":{"light":"github-light","dark":"github-dark-dimmed"}}
SELECT <non-pivoted columns>, <pivoted columns>
FROM <table>
PIVOT(
  <AggregateFunction>(<ColumnToBeAggregated>)
  FOR <PivotColumn>
  IN (ANY)
)
```

**Requirements:**

* The pivot column must be a single identifier (e.g., `metadata.model`)
* Must include at least one aggregate measure (e.g., `SUM(value)`, `AVG(score)`)
* Only `IN (ANY)` is supported (explicit value lists, subqueries, `ORDER BY`, and `DEFAULT ON NULL` are not supported)
* `SELECT` list must include the pivot column, all measures, and all `GROUP BY` columns (or use `SELECT *`)

Pivot columns are automatically named by combining the pivot value and measure name. For example, if you pivot `metadata.model` with a model named "gpt-4" for measure `avg_score`, the column becomes `gpt-4_avg_score`. When using aliases, the alias replaces the measure name in the output column.

**Single aggregate** - pivot one metric across categories:

```sql theme={"theme":{"light":"github-light","dark":"github-dark-dimmed"}}
-- Compare total score values across all scores
SELECT score, SUM(value)
FROM project_logs('my-project-id')
UNPIVOT (value FOR score IN (scores))
PIVOT(SUM(value) FOR score IN (ANY))
WHERE created > now() - interval 7 day
```

**Multiple aggregates** - pivot multiple metrics at once:

```sql theme={"theme":{"light":"github-light","dark":"github-dark-dimmed"}}
-- Compare model performance metrics across models
SELECT day(created) AS date, metadata.model, AVG(scores.Factuality), COUNT(1)
FROM project_logs('my-project-id')
WHERE metadata.model IN ('gpt-4', 'gpt-3.5-turbo')
  AND created > now() - interval 7 day
GROUP BY day(created), metadata.model
PIVOT(
  AVG(scores.Factuality),
  COUNT(1)
  FOR metadata.model IN (ANY)
)
```

**With aliases** - name your pivoted columns:

```sql theme={"theme":{"light":"github-light","dark":"github-dark-dimmed"}}
-- Use custom column names
SELECT score, total
FROM project_logs('my-project-id')
UNPIVOT (value FOR score IN (scores))
PIVOT(SUM(value) AS total FOR score IN (ANY))
WHERE created > now() - interval 7 day
```

**With grouping** - combine `PIVOT` with `GROUP BY` for multi-dimensional analysis:

```sql theme={"theme":{"light":"github-light","dark":"github-dark-dimmed"}}
-- Analyze scores by both model and date
SELECT day(created) AS date, metadata.model, AVG(scores.Factuality)
FROM project_logs('my-project-id')
WHERE created > now() - interval 7 day
GROUP BY day(created), metadata.model
PIVOT(AVG(scores.Factuality) FOR metadata.model IN (ANY))
```

**Using `SELECT *`** - automatically includes all required columns:

```sql theme={"theme":{"light":"github-light","dark":"github-dark-dimmed"}}
-- SELECT * includes pivot column, measures, and GROUP BY columns automatically
SELECT *
FROM project_logs('my-project-id')
UNPIVOT (value FOR score IN (scores))
PIVOT(SUM(value) FOR score IN (ANY))
WHERE created > now() - interval 7 day
```

### `UNPIVOT`

`UNPIVOT` transforms columns into rows, which is useful when you need to analyze arbitrary scores and metrics without specifying each field name in advance. This is helpful when working with dynamic sets of metrics or when you want to normalize data for aggregation.

**Key-value unpivot** - transforms an object into rows with key-value pairs:

```sql theme={"theme":{"light":"github-light","dark":"github-dark-dimmed"}}
-- Convert scores object into rows with score names and values
SELECT id, score, value
FROM project_logs('my-project-id')
UNPIVOT (value FOR score IN (scores))
WHERE created > now() - interval 7 day
```

<Note>
  When using key-value unpivot, the source column must be an object (e.g., `scores`). When using array unpivot with `_`, the source column must be an array (e.g., `tags`).
</Note>

**Array unpivot** - expands arrays by using `_` as the name column:

```sql theme={"theme":{"light":"github-light","dark":"github-dark-dimmed"}}
-- Expand tags array into individual rows
SELECT id, tag
FROM project_logs('my-project-id')
UNPIVOT (tag FOR _ IN (tags))
WHERE created > now() - interval 7 day
```

**Array of objects unpivot** - expands arrays of objects and allows accessing nested fields:

```sql theme={"theme":{"light":"github-light","dark":"github-dark-dimmed"}}
-- Expand classifications array and group by nested field
SELECT classification.id, COUNT(1) AS count
FROM project_logs('my-project-id')
UNPIVOT (classification FOR _ IN (classifications.Issues))
WHERE created >= NOW() - INTERVAL 3 DAY
GROUP BY classification.id
```

This pattern is useful for analyzing classifications where each log may have multiple topic classifications, and you want to aggregate by specific properties of those classifications.

**Multiple unpivots** - chain multiple `UNPIVOT` operations to expand multiple columns:

```sql theme={"theme":{"light":"github-light","dark":"github-dark-dimmed"}}
-- Expand both scores and tags
SELECT score, tag
FROM project_logs('my-project-id')
UNPIVOT (value FOR score IN (scores))
UNPIVOT (tag FOR _ IN (tags))
WHERE created > now() - interval 7 day
```

**With aggregations** - use `UNPIVOT` with `GROUP BY` to aggregate across unpivoted rows:

```sql theme={"theme":{"light":"github-light","dark":"github-dark-dimmed"}}
-- Calculate average value for each score across all logs
SELECT score, AVG(value) AS avg_value, COUNT(1) AS count
FROM project_logs('my-project-id')
UNPIVOT (value FOR score IN (scores))
WHERE created > now() - interval 7 day
GROUP BY score
ORDER BY avg_value DESC
```

## `LIMIT` and cursors

### `LIMIT`

The `LIMIT` clause controls the size of the result in number of records.

```sql theme={"theme":{"light":"github-light","dark":"github-dark-dimmed"}}
-- Basic limit
LIMIT 100
```

When using `LIMIT` with `GROUP BY`, it restricts the number of grouped results returned. This is useful for getting top-N results after aggregation. When combined with `ORDER BY`, rows are sorted before limiting.

```sql theme={"theme":{"light":"github-light","dark":"github-dark-dimmed"}}
-- Get top 5 models by usage
SELECT
  metadata.model AS model,
  count(1) AS total_calls
FROM project_logs('my-project-id')
WHERE created > now() - interval 7 day
GROUP BY metadata.model
ORDER BY total_calls DESC
LIMIT 5
```

```sql theme={"theme":{"light":"github-light","dark":"github-dark-dimmed"}}
-- Limit aggregated results without sorting
SELECT
  metadata.category AS category,
  avg(metrics.duration) AS avg_duration
FROM project_logs('my-project-id')
WHERE created > now() - interval 7 day
GROUP BY metadata.category
LIMIT 10
```

### Cursors for pagination

Cursors are supported in both SQL and BTQL queries. Cursors are automatically returned in query responses. After an initial query, pass the returned cursor token in the follow-on query. When a cursor has reached the end of the result set, the `data` array will be empty, and no cursor token will be returned.

In SQL syntax, pass cursor tokens using `OFFSET '<CURSOR_TOKEN>'`. Numeric offsets are not supported. For cursor pagination, use cursor-compatible sorts such as `_pagination_key` (recommended) or `_xact_id`.

```sql theme={"theme":{"light":"github-light","dark":"github-dark-dimmed"}}
-- Pagination with cursor token
-- Page 1 (first 100 results)
SELECT *
FROM project_logs('<PROJECT_ID>')
WHERE created > now() - interval 7 day
ORDER BY _pagination_key DESC
LIMIT 100

-- Page 2 (next 100 results)
SELECT *
FROM project_logs('<PROJECT_ID>')
WHERE created > now() - interval 7 day
ORDER BY _pagination_key DESC
LIMIT 100
OFFSET '<CURSOR_TOKEN>'  -- From previous query response
```

## Expressions

### SQL operators

You can use the following operators in your SQL queries.

```sql theme={"theme":{"light":"github-light","dark":"github-dark-dimmed"}}
-- Comparison operators
=           -- Equal to (alias for 'eq')
!=          -- Not equal to (alias for 'ne', can also use '<>')
>           -- Greater than (alias for 'gt')
<           -- Less than (alias for 'lt')
>=          -- Greater than or equal (alias for 'ge')
<=          -- Less than or equal (alias for 'le')
<=>         -- Null-safe equal: true if both sides are equal OR both are null
<!=>        -- Null-safe not-equal: true if values differ, treating null as a distinct value
IN          -- Check if value exists in a list of values
NOT IN      -- Check if value does not exist in a list of values

-- Null operators
IS NULL     -- Check if value is null
IS NOT NULL -- Check if value is not null
ISNULL      -- Unary operator to check if null
ISNOTNULL   -- Unary operator to check if not null

-- Text matching
LIKE        -- Case-sensitive pattern matching (supports % wildcard)
NOT LIKE    -- Negated case-sensitive pattern matching
ILIKE       -- Case-insensitive pattern matching (supports % wildcard)
NOT ILIKE   -- Negated case-insensitive pattern matching
MATCH       -- Full-word semantic search (faster but requires exact word matches, e.g. 'apple' won't match 'app')
NOT MATCH   -- Negated full-word semantic search

-- Array operators
INCLUDES    -- Check if array/object contains value (alias: CONTAINS)
NOT INCLUDES -- Check if array/object does not contain value

-- Logical operators
AND         -- Both conditions must be true
OR          -- Either condition must be true
NOT         -- Unary operator to negate condition

-- Arithmetic operators
+           -- Addition (alias: add)
-           -- Subtraction (alias: sub)
*           -- Multiplication (alias: mul)
/           -- Division (alias: div)
%           -- Modulo (alias: mod)
-x          -- Unary negation (alias: neg)
```

### SQL functions

You can use the following functions in `SELECT`, `WHERE`, `GROUP BY` clauses, and aggregate measures.

```sql theme={"theme":{"light":"github-light","dark":"github-dark-dimmed"}}
-- Date/time functions
second(timestamp)          -- Extract second from timestamp
minute(timestamp)         -- Extract minute from timestamp
hour(timestamp)          -- Extract hour from timestamp
day(timestamp)           -- Extract day from timestamp
week(timestamp)          -- Extract week from timestamp
month(timestamp)         -- Extract month from timestamp
year(timestamp)          -- Extract year from timestamp
date_trunc(interval, timestamp)  -- Truncate timestamp to specified interval
                                 -- Intervals: 'second', 'minute', 'hour', 'day', 'week', 'month', 'year'
current_timestamp()      -- Get current timestamp (alias: now())
current_date()          -- Get current date

-- String functions
lower(text)                       -- Convert text to lowercase
upper(text)                       -- Convert text to uppercase
concat(text1, text2, ...)         -- Concatenate strings

-- Array functions
len(array)                        -- Get length of array
contains(array, value)            -- Check if array contains value (alias: includes)

-- JSON functions
json_extract(json_str, path)      -- Extract value from JSON string using a path expression

-- Null handling functions
coalesce(val1, val2, ...)        -- Return first non-null value
                                 -- Note: coalesce(field, 'x') != 'y' is automatically rewritten
                                 -- to field <!=> 'y' for better index performance
nullif(val1, val2)               -- Return null if val1 equals val2
least(val1, val2, ...)           -- Return smallest non-null value
greatest(val1, val2, ...)        -- Return largest non-null value

-- Type conversion
round(number, precision)          -- Round to specified precision

-- Cast functions
to_string(value)                 -- Cast value to string
to_boolean(value)                -- Cast value to boolean
to_integer(value)                -- Cast value to integer
to_number(value)                 -- Cast value to number
to_date(value)                   -- Cast value to date
to_datetime(value)               -- Cast value to datetime
to_interval(value)               -- Cast value to interval

-- Search functions
search(query)                     -- Full-text search across all text fields (input, output, expected,
                                  --   metadata, span_attributes). Equivalent to writing
                                  --   input MATCH query OR output MATCH query OR ... for each field.
                                  --   When log search optimization is enabled on the project, search()
                                  --   uses bloom filters to skip irrelevant segments automatically.

-- Cost functions
estimated_cost()                  -- Estimated cost of a span in USD. Uses metrics.estimated_cost
                                  --   when available; otherwise computes from token metrics and
                                  --   model registry pricing. Works in spans, traces, and summary
                                  --   shapes. Can be used inside aggregates: sum(estimated_cost())

-- Aggregate functions (only in measures/with GROUP BY)
count(expr)                       -- Count number of rows
count_distinct(expr)              -- Count number of distinct values
sum(expr)                        -- Sum numeric values
avg(expr)                        -- Calculate mean of numeric values
min(expr)                        -- Find minimum value
max(expr)                        -- Find maximum value
percentile(expr, p)              -- Calculate percentile (p between 0 and 1)
```

### Field access

SQL provides flexible ways to access nested data in arrays and objects:

```sql theme={"theme":{"light":"github-light","dark":"github-dark-dimmed"}}
-- Object field access
metadata.model             -- Access nested object field  e.g. {"metadata": {"model": "value"}}
metadata."field name"      -- Access field with spaces	  e.g. {"metadata": {"field name": "value"}}
metadata."field-name"      -- Access field with hyphens   e.g. {"metadata": {"field-name": "value"}}
metadata."field.name"      -- Access field with dots	  e.g. {"metadata": {"field.name": "value"}}

-- Array access (0-based indexing)
tags[0]                    -- First element
tags[-1]                   -- Last element

-- Combined array and object access
metadata.models[0].name    -- Field in first array element
responses[-1].tokens       -- Field in last array element
spans[0].children[-1].id   -- Nested array traversal

-- Facets access
facets.task                -- Task facet value
facets.sentiment           -- Sentiment facet value
facets."custom-facet"      -- Custom facet (quotes for hyphens)

-- Classifications access (array of objects)
classifications.Task[0].label              -- Classification label
classifications.Task[0].metadata.distance  -- Distance metric
classifications.Sentiment[0].label         -- Sentiment classification
```

<Note>
  Array indices are 0-based, and negative indices count from the end (-1 is the last element).
</Note>

When you have JSON data stored as a string field (rather than as native SQL objects), use the [`json_extract` function](#extract-data-from-json-strings) to access values within it. The path parameter is treated as a literal string key name:

```sql theme={"theme":{"light":"github-light","dark":"github-dark-dimmed"}}
-- Extract from JSON string fields
json_extract(metadata.config, 'api_key')           -- Extract the "api_key" field
json_extract(metadata.config, 'user_id')           -- Extract the "user_id" field
json_extract(output, 'result')                     -- Extract the "result" field
```

#### Implicit aliasing

When you reference multi-part identifiers (e.g., `metadata.category`), SQL automatically creates an implicit alias using the last component of the path (e.g., `category`). This allows you to use the short form in your queries when unambiguous.

```sql theme={"theme":{"light":"github-light","dark":"github-dark-dimmed"}}
-- Use short form in WHERE clause
SELECT metadata.category, metadata.model
FROM project_logs('my-project-id')
WHERE category = 'support' AND model = 'gpt-4'
  AND created > now() - interval 7 day

-- Use short form in ORDER BY
SELECT metadata.user.name
FROM project_logs('my-project-id')
WHERE created > now() - interval 7 day
ORDER BY name

-- Use short form in GROUP BY
SELECT metadata.model, COUNT(*) as cnt
FROM project_logs('my-project-id')
WHERE created > now() - interval 7 day
GROUP BY model
```

**Important notes about implicit aliasing:**

* **Ambiguity prevention**: If multiple fields share the same last component (e.g., `metadata.name` and `user.name`), the short form `name` becomes ambiguous and cannot be used. You must use the full path instead.

* **Top-level field priority**: Top-level fields take precedence over nested fields. If you have both `id` and `metadata.id`, the short form `id` refers to the top-level field.

* **Explicit aliases override**: When you provide an explicit alias (e.g., `metadata.category AS cat`), the implicit alias is disabled and you must use either the explicit alias or the full path.

* **Duplicate alias detection**: SQL will detect and reject queries with duplicate aliases in the SELECT list, whether explicit or implicit. For example, `SELECT id, user.number AS id` will raise an error.

**Examples of ambiguous references:**

```sql theme={"theme":{"light":"github-light","dark":"github-dark-dimmed"}}
-- ERROR: Cannot use 'name' - ambiguous between two fields
SELECT metadata.name, user.name
FROM project_logs('my-project-id')
WHERE created > now() - interval 7 day
ORDER BY name  -- Error: ambiguous

-- CORRECT: Use full path when ambiguous
SELECT metadata.name, user.name
FROM project_logs('my-project-id')
WHERE created > now() - interval 7 day
ORDER BY metadata.name
```

**Freeing up short forms with explicit aliases:**

When one field uses an explicit alias, its short form becomes available for other fields:

```sql theme={"theme":{"light":"github-light","dark":"github-dark-dimmed"}}
-- 'user_name' is the explicit alias, 'name' now refers to metadata.name
SELECT user.name AS user_name, metadata.name
FROM project_logs('my-project-id')
WHERE name = 'configuration'  -- Refers to metadata.name
  AND created > now() - interval 7 day
```

### Conditional expressions

SQL supports conditional logic using the ternary operator (`? :`):

```sql theme={"theme":{"light":"github-light","dark":"github-dark-dimmed"}}
-- Basic conditions
SELECT
  (scores.Factuality > 0.8 ? "high" : "low") AS quality,
  (error IS NOT NULL ? -1 : metrics.tokens) AS valid_tokens
FROM project_logs('my-project-id')
WHERE created > now() - interval 7 day
```

```sql theme={"theme":{"light":"github-light","dark":"github-dark-dimmed"}}
-- Nested conditions
SELECT
  (scores.Factuality > 0.9 ? "excellent" :
   scores.Factuality > 0.7 ? "good" :
   scores.Factuality > 0.5 ? "fair" : "poor") AS rating
FROM project_logs('my-project-id')
WHERE created > now() - interval 7 day
```

```sql theme={"theme":{"light":"github-light","dark":"github-dark-dimmed"}}
-- Use in calculations
SELECT
  (metadata.model = "gpt-4" ? metrics.tokens * 2 : metrics.tokens) AS adjusted_tokens,
  (error IS NULL ? metrics.latency : 0) AS valid_latency
FROM project_logs('my-project-id')
WHERE created > now() - interval 7 day
```

## Examples

### Track token usage

This query helps you monitor token consumption across your application.

<CodeGroup>
  ```sql SQL syntax theme={"theme":{"light":"github-light","dark":"github-dark-dimmed"}}
  SELECT
    day(created) AS time,
    sum(metrics.total_tokens) AS total_tokens,
    sum(metrics.prompt_tokens) AS input_tokens,
    sum(metrics.completion_tokens) AS output_tokens
  FROM project_logs('<YOUR_PROJECT_ID>')
  WHERE created > '<ISO_8601_TIME>'
  GROUP BY 1
  ORDER BY time ASC
  ```

  ```sql SQL syntax (using date_trunc) theme={"theme":{"light":"github-light","dark":"github-dark-dimmed"}}
  -- Alternative using date_trunc function
  SELECT
    date_trunc('day', created) AS time,
    sum(metrics.total_tokens) AS total_tokens,
    sum(metrics.prompt_tokens) AS input_tokens,
    sum(metrics.completion_tokens) AS output_tokens
  FROM project_logs('<YOUR_PROJECT_ID>')
  WHERE created > '<ISO_8601_TIME>'
  GROUP BY date_trunc('day', created)
  ORDER BY time ASC
  ```
</CodeGroup>

The response shows daily token usage:

```json theme={"theme":{"light":"github-light","dark":"github-dark-dimmed"}}
{
  "time": "2024-11-09T00:00:00Z",
  "total_tokens": 100000,
  "input_tokens": 50000,
  "output_tokens": 50000
}
```

### Monitor model quality

Track model performance across different versions and configurations.

```sql theme={"theme":{"light":"github-light","dark":"github-dark-dimmed"}}
-- Compare factuality scores across models
SELECT
  metadata.model AS model,
  day(created) AS date,
  avg(scores.Factuality) AS avg_factuality,
  percentile(scores.Factuality, 0.05) AS p05_factuality,
  percentile(scores.Factuality, 0.95) AS p95_factuality,
  count(1) AS total_calls
FROM project_logs('<PROJECT_ID>')
WHERE created > '2024-01-01'
GROUP BY 1, 2
ORDER BY date DESC, model ASC
```

```sql theme={"theme":{"light":"github-light","dark":"github-dark-dimmed"}}
-- Find potentially problematic responses
SELECT *
FROM project_logs('<PROJECT_ID>')
WHERE scores.Factuality < 0.5
  AND metadata.is_production = true
  AND created > now() - interval 1 day
ORDER BY scores.Factuality ASC
LIMIT 100
```

```sql theme={"theme":{"light":"github-light","dark":"github-dark-dimmed"}}
-- Compare performance across specific models
SELECT *
FROM project_logs('<PROJECT_ID>')
WHERE metadata.model IN ('gpt-4', 'gpt-4-turbo', 'claude-3-opus')
  AND scores.Factuality IS NOT NULL
  AND created > now() - interval 7 day
ORDER BY scores.Factuality DESC
LIMIT 500
```

### Analyze errors

Identify and investigate errors in your application.

```sql theme={"theme":{"light":"github-light","dark":"github-dark-dimmed"}}
-- Error rate by model
SELECT
  metadata.model AS model,
  hour(created) AS hour,
  count(1) AS total,
  count(error) AS errors,
  count(error) / count(1) AS error_rate
FROM project_logs('<PROJECT_ID>')
WHERE created > now() - interval 1 day
GROUP BY 1, 2
ORDER BY error_rate DESC
```

```sql theme={"theme":{"light":"github-light","dark":"github-dark-dimmed"}}
-- Find common error patterns
SELECT
  error.type AS error_type,
  metadata.model AS model,
  count(1) AS error_count,
  avg(metrics.latency) AS avg_latency
FROM project_logs('<PROJECT_ID>')
WHERE error IS NOT NULL
  AND created > now() - interval 7 day
GROUP BY 1, 2
ORDER BY error_count DESC
```

```sql theme={"theme":{"light":"github-light","dark":"github-dark-dimmed"}}
-- Exclude known error types from analysis
SELECT *
FROM project_logs('<PROJECT_ID>')
WHERE error IS NOT NULL
  AND error.type NOT IN ('rate_limit', 'timeout', 'network_error')
  AND metadata.is_production = true
  AND created > now() - interval 1 day
ORDER BY created DESC
LIMIT 100
```

### Analyze latency

Monitor and optimize response times.

```sql theme={"theme":{"light":"github-light","dark":"github-dark-dimmed"}}
-- Track p95 latency by endpoint
SELECT
  metadata.endpoint AS endpoint,
  hour(created) AS hour,
  percentile(metrics.latency, 0.95) AS p95_latency,
  percentile(metrics.latency, 0.50) AS median_latency,
  count(1) AS requests
FROM project_logs('<PROJECT_ID>')
WHERE created > now() - interval 1 day
GROUP BY 1, 2
ORDER BY hour DESC, p95_latency DESC
```

```sql theme={"theme":{"light":"github-light","dark":"github-dark-dimmed"}}
-- Find slow requests
SELECT
  metadata.endpoint,
  metrics.latency,
  metrics.tokens,
  input,
  created
FROM project_logs('<PROJECT_ID>')
WHERE metrics.latency > 5000  -- Requests over 5 seconds
  AND created > now() - interval 1 hour
ORDER BY metrics.latency DESC
LIMIT 20
```

### Analyze prompts

Analyze prompt effectiveness and patterns.

```sql theme={"theme":{"light":"github-light","dark":"github-dark-dimmed"}}
-- Track prompt token efficiency
SELECT
  metadata.prompt_template AS template,
  day(created) AS date,
  avg(metrics.prompt_tokens) AS avg_prompt_tokens,
  avg(metrics.completion_tokens) AS avg_completion_tokens,
  avg(metrics.completion_tokens) / avg(metrics.prompt_tokens) AS token_efficiency,
  avg(scores.Factuality) AS avg_factuality
FROM project_logs('<PROJECT_ID>')
WHERE created > now() - interval 7 day
GROUP BY 1, 2
ORDER BY date DESC, token_efficiency DESC
```

```sql theme={"theme":{"light":"github-light","dark":"github-dark-dimmed"}}
-- Find similar prompts
SELECT *
FROM project_logs('<PROJECT_ID>')
WHERE input MATCH 'explain the concept of recursion'
  AND scores.Factuality > 0.8
  AND created > now() - interval 7 day
ORDER BY created DESC
LIMIT 10
```

### Analyze based on tags

Use tags to track and analyze specific behaviors.

<Note>
  `INCLUDES` is not supported in SQL mode. The examples below use BTQL syntax, which supports `INCLUDES` for exact array membership. In SQL mode, `MATCH` is a fuzzy approximation (`tags MATCH 'feedback'`), but it performs full-text tokenization and may return false positives.
</Note>

```sql theme={"theme":{"light":"github-light","dark":"github-dark-dimmed"}}
-- Monitor feedback patterns
select: tags[0] AS primary_tag, metadata.model AS model, count(1) AS feedback_count, avg(scores.Factuality > 0.8 ? 1 : 0) AS high_quality_rate
| from: project_logs('<PROJECT_ID>')
| filter: tags INCLUDES 'feedback' AND created > now() - interval 30 day
| dimensions: 1, 2
| sort: feedback_count DESC
```

```sql theme={"theme":{"light":"github-light","dark":"github-dark-dimmed"}}
-- Track issue resolution
select: created, tags, metadata.model, scores.Factuality, response
| from: project_logs('<PROJECT_ID>')
| filter: tags INCLUDES 'needs-review' AND NOT tags INCLUDES 'resolved' AND created > now() - interval 1 day
| sort: scores.Factuality ASC
```

### Analyze based on tags and scores

A common pattern is filtering traces by both tags and scores when automated scorers apply scores at the span level while tags exist on root spans. Use separate `ANY_SPAN()` clauses to match traces where any span contains the tag AND any span contains the score.

Each `ANY_SPAN()` clause evaluates independently across all spans in a trace. The conditions don't need to match on the same span - the first `ANY_SPAN()` finds traces where at least one span has the tag, while the second finds traces where at least one span has the score.

```sql theme={"theme":{"light":"github-light","dark":"github-dark-dimmed"}}
-- Find traces with a specific tag and score
select: *
| from: project_logs('<PROJECT_ID>') traces
| filter: ANY_SPAN(tags INCLUDES 'content-change') AND ANY_SPAN(scores."email sent" IS NOT NULL) AND created > now() - interval 7 day
| limit: 100
```

For score names with spaces or special characters, wrap the name in double quotes:

```sql theme={"theme":{"light":"github-light","dark":"github-dark-dimmed"}}
-- Filter by tag and score with spaces in name
select: *
| from: project_logs('<PROJECT_ID>') summary
| filter: ANY_SPAN(tags INCLUDES 'production') AND ANY_SPAN(scores."Email Draft vs Sent Similarity" IS NOT NULL) AND created > now() - interval 7 day
| sort: created DESC
| limit: 50
```

### Analyze traces with span filters

Use [single span filters](#single-span-filters) with aggregations to analyze traces based on span-level conditions. This is useful for understanding patterns across complex, multi-step operations.

```sql theme={"theme":{"light":"github-light","dark":"github-dark-dimmed"}}
-- Find traces with both errors and LLM spans, grouped by error type
SELECT
  error.type AS error_type,
  metadata.model AS model,
  count(1) AS trace_count,
  avg(estimated_cost()) AS avg_cost
FROM project_logs('<PROJECT_ID>', shape => 'traces')
WHERE ANY_SPAN(error IS NOT NULL)
  AND ANY_SPAN(span_attributes.type = 'llm')
  AND created > now() - interval 7 day
GROUP BY error.type, metadata.model
ORDER BY trace_count DESC
```

```sql theme={"theme":{"light":"github-light","dark":"github-dark-dimmed"}}
-- Analyze average cost by day for traces with errors and LLM calls
SELECT
  day(created) AS date,
  avg(estimated_cost()) AS avg_cost,
  count(1) AS error_count
FROM project_logs('<PROJECT_ID>', shape => 'summary')
WHERE ANY_SPAN(error IS NOT NULL)
  AND ANY_SPAN(span_attributes.type = 'llm')
  AND created > now() - interval 7 day
GROUP BY day(created)
ORDER BY date DESC
```

Use `FILTER_SPANS()` to analyze only the spans that match specific criteria:

```sql theme={"theme":{"light":"github-light","dark":"github-dark-dimmed"}}
-- Analyze score distribution for only scorer spans
SELECT
  span_attributes.name AS scorer_name,
  avg(scores.Factuality) AS avg_score,
  count(1) AS span_count
FROM project_logs('<PROJECT_ID>', shape => 'traces')
WHERE FILTER_SPANS(span_attributes.type = 'score')
  AND created > now() - interval 7 day
GROUP BY span_attributes.name
ORDER BY avg_score DESC
```

### Extract data from JSON strings

Use `json_extract` to extract values from a JSON string using a key name. This is useful when you have JSON data stored as a string field and need to access specific values within it. The path parameter is treated as a literal key name (not a path expression with traversal).

```sql theme={"theme":{"light":"github-light","dark":"github-dark-dimmed"}}
-- Extract a simple field
SELECT
  id,
  json_extract(metadata.config, 'api_key') AS api_key
FROM project_logs('my-project-id')
WHERE created > now() - interval 7 day
```

```sql theme={"theme":{"light":"github-light","dark":"github-dark-dimmed"}}
-- Extract fields with special characters in the key name
SELECT
  id,
  json_extract(metadata.settings, 'user.preferences.theme') AS theme_key
FROM project_logs('my-project-id')
WHERE created > now() - interval 7 day
-- Note: This extracts a key literally named "user.preferences.theme", not a nested path
```

```sql theme={"theme":{"light":"github-light","dark":"github-dark-dimmed"}}
-- Extract and filter
SELECT *
FROM project_logs('my-project-id')
WHERE json_extract(metadata.config, 'environment') = 'production'
  AND json_extract(metadata.config, 'version') > 2.0
  AND created > now() - interval 7 day
```

<Note>
  `json_extract` returns `null` for invalid JSON or missing keys rather than raising an error, making it safe to use in filters and aggregations. The path parameter is a literal key name, not a path expression - characters like dots, brackets, etc. are treated as part of the key name itself.
</Note>

### Query by classifications

**Classifications** are categorical labels assigned by topic maps (e.g., `classifications.Task[0].label`). Each classification includes `.label` (the category), `.metadata` (with distance metrics), and `.source` information. Classifications are generated by [topics automations](/observe/topics).

Filter and analyze logs by topic classifications to understand patterns in your data.

```sql theme={"theme":{"light":"github-light","dark":"github-dark-dimmed"}}
SELECT
  classifications.Task[0].label as topic,
  count(*) as count
FROM project_logs('my-project-id')
WHERE classifications.Task IS NOT NULL
  AND created > now() - interval 7 day
GROUP BY topic
ORDER BY count DESC
```

<Tip>
  To analyze all classifications in an array rather than just the first element, use [UNPIVOT](#unpivot) to expand the array. See [Array of objects unpivot](#unpivot) for examples.
</Tip>

Filter by specific topic and distance threshold:

```sql theme={"theme":{"light":"github-light","dark":"github-dark-dimmed"}}
SELECT id, facets.task, classifications.Task[0].label as topic
FROM project_logs('my-project-id')
WHERE classifications.Task[0].label = 'Creating datasets'
  AND classifications.Task[0].metadata.distance < 0.5
  AND created > now() - interval 7 day
ORDER BY classifications.Task[0].metadata.distance ASC
LIMIT 50
```

### Analyze facet distributions

**Facets** are AI-extracted attributes that summarize logs (e.g., `facets.task`, `facets.sentiment`). They're generated by [topics automations](/observe/topics).

Query logs by facet values to identify patterns and issues.

```sql theme={"theme":{"light":"github-light","dark":"github-dark-dimmed"}}
SELECT id, created, facets.task, facets.sentiment
FROM project_logs('my-project-id')
WHERE facets.sentiment IN ('NEGATIVE', 'MIXED')
  AND created > now() - interval 7 day
ORDER BY created DESC
LIMIT 100
```

### Combine facets and classifications

Analyze relationships between facets and classifications to gain deeper insights.

```sql theme={"theme":{"light":"github-light","dark":"github-dark-dimmed"}}
SELECT
  facets.sentiment,
  classifications.Task[0].label as topic,
  count(*) as count,
  avg(metrics.total_tokens) as avg_tokens
FROM project_logs('my-project-id', shape => 'traces')
WHERE facets.sentiment IS NOT NULL
  AND classifications.Task IS NOT NULL
  AND created > now() - interval 7 day
GROUP BY facets.sentiment, topic
ORDER BY count DESC
LIMIT 20
```

Analyze topic distribution with distance metrics:

```sql theme={"theme":{"light":"github-light","dark":"github-dark-dimmed"}}
SELECT
  classifications.Task[0].label as topic,
  count(*) as occurrences,
  avg(classifications.Task[0].metadata.distance) as avg_distance
FROM project_logs('my-project-id', shape => 'traces')
WHERE classifications.Task IS NOT NULL
  AND created > now() - interval 7 day
GROUP BY topic
HAVING count(*) > 10
ORDER BY occurrences DESC
```
