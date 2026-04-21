> ## Documentation Index
> Fetch the complete documentation index at: https://braintrust.dev/docs/llms.txt
> Use this file to discover all available pages before exploring further.

# Deep search

> Find traces with semantic search

Sometimes you need to find traces based on what they mean, not just what they say. Deep search uses AI to understand the semantic meaning of your query and finds relevant traces even when they don't contain your exact keywords.

Deep search helps you:

* **Find concepts**: Search for "frustrated users" even when they never use that word.
* **Discover patterns**: Identify traces with similar issues or behaviors.
* **Ask questions**: Query like "where did the agent give up?" instead of keyword matching.
* **Surface edge cases**: Find unusual interactions you didn't anticipate.
* **Analyze sentiment**: Locate traces with specific emotional tones.

Unlike keyword search or SQL filters, deep search understands meaning and context.

## Enable deep search

To use deep search, you must enable the deep search feature flag and ensure OpenAI is configured. Deep search relies on `gpt-4o-mini` and `gpt-4o` for semantic evaluation.

<Note>
  For self-hosted deployments, deep search requires version `v1.1.23` or later.
</Note>

1. Enable the feature flag.
   1. Go to **Settings > Feature flags**.
   2. Find **Deep search**.
   3. Toggle it on.

2. Check OpenAI configuration.
   1. Go to **Settings > AI providers**.
   2. Verify OpenAI is configured with an API key.
   3. If not configured, add your OpenAI API key.

## Run a deep search query

Deep search runs in the Loop panel on the **Logs** page.

1. Go to the <Icon icon="activity" /> **Logs** page in your project.
2. Click **Loop** <Icon icon="blend" />.
3. In the Loop panel, click <Icon icon="glasses" /> **Deep search**.
4. Enter a natural language query.

Deep search evaluates a sample and returns up to 20 most relevant traces. For comprehensive analysis, combine with filters.

Up to 20 relevant traces stream into the Loop panel in real-time.

## Understand results

Deep search results stream into Loop. They are ordered by semantic relevance, not chronologically. The most relevant traces appear first.

Each result shows:

* **Input**: The user request or prompt.
* **Output**: The response.
* **Quote**: The specific text excerpt that matched your query.

Click any result to view full trace details with metadata.

Deep search evaluates a sample and returns up to 20 most relevant traces. For comprehensive analysis, combine with filters.

## Apply results as filter

Convert deep search results to a table filter:

1. Review the traces in Loop.
2. Click **Apply as table filter**.

   The logs table filters to just the found traces.
3. Optionally, save this view for later.

## Combine with other filters

Deep search works alongside other filtering:

1. Apply [SQL filters](/observe/filter#write-sql-queries) or [tag filters](/observe/filter#filter-by-tags) first to narrow the search space.
2. Run deep search on the filtered results.

For example, to find production traces with frustrated users:

* Filter to `metadata.environment = "production"`.
* Deep search for "frustrated users".

## Query examples

<AccordionGroup>
  <Accordion title="Find user struggles">
    ```
    Show me examples where users are struggling
    ```

    This finds traces where:

    * Users asked for clarification multiple times
    * The interaction seemed confused or frustrated
    * The application failed to understand the request
  </Accordion>

  <Accordion title="Identify frustrated users">
    ```
    Find conversations with frustrated users
    ```

    This finds traces with:

    * Negative sentiment in messages
    * Complaints or criticism
    * Repeated failed attempts
  </Accordion>

  <Accordion title="Locate happy interactions">
    ```
    Highlight cases where customers are happy
    ```

    This finds traces where:

    * Users expressed satisfaction or thanks
    * Interactions completed successfully
    * Positive sentiment throughout
  </Accordion>

  <Accordion title="Find specific behaviors">
    ```
    Where did the agent refuse to answer?
    ```

    This finds traces where:

    * The application declined to respond
    * Safety or policy limitations were hit
    * Requests were out of scope
  </Accordion>

  <Accordion title="Surface errors without keywords">
    ```
    Show me traces where things went wrong
    ```

    This finds problematic traces even if they don't contain "error":

    * Incomplete responses
    * Wrong information provided
    * User confusion or dissatisfaction
  </Accordion>

  <Accordion title="Discover edge cases">
    ```
    Find unusual or unexpected interactions
    ```

    This surfaces traces that:

    * Don't fit common patterns
    * Show unexpected user behavior
    * Reveal uncommon use cases
  </Accordion>
</AccordionGroup>

## Query tips

<AccordionGroup>
  <Accordion title="Be specific">
    ```
    ❌ "Problems"
    ✓ "Show me traces where users couldn't complete their task"
    ```

    Specific queries return more relevant results.
  </Accordion>

  <Accordion title="Describe what you're looking for">
    ```
    ❌ "Bad"
    ✓ "Find traces where the response was factually incorrect or misleading"
    ```

    Descriptive queries help the AI understand your intent.
  </Accordion>

  <Accordion title="Focus on behavior or output">
    ```
    ❌ "Errors"
    ✓ "Where did the application fail to understand the user's question?"
    ```

    Behavior-focused queries find semantic matches beyond keywords.
  </Accordion>

  <Accordion title="Ask questions naturally">
    ```
    ✓ "Which conversations had back-and-forth clarification?"
    ✓ "Where did users express confusion?"
    ✓ "When did the agent repeat itself?"
    ```

    Natural questions often work better than keywords.
  </Accordion>
</AccordionGroup>

## Deep search vs SQL

Choose the right tool for your task:

| Use case            | Tool        | Example                         |
| ------------------- | ----------- | ------------------------------- |
| Exact field matches | SQL         | `metadata.user_id = "user_123"` |
| Numeric thresholds  | SQL         | `latency > 2000`                |
| Semantic patterns   | Deep search | "frustrated users"              |
| Concept discovery   | Deep search | "where things went wrong"       |
| Complex conditions  | SQL         | `cost > 0.10 AND error IS NULL` |
| Sentiment analysis  | Deep search | "happy customers"               |
