> ## Documentation Index
> Fetch the complete documentation index at: https://braintrust.dev/docs/llms.txt
> Use this file to discover all available pages before exploring further.

# Discover insights with Topics

> Automatically discover and classify patterns in logs

export const version_0 = "v2.0+"

<Icon icon="pentagon" /> **Topics** automatically analyze and classify your logs, discovering patterns and insights across all your traces. Topics help you:

* Discover what users are trying to accomplish
* Find where users struggle, get confused, or encounter errors
* Track user satisfaction and frustration across interactions
* Pinpoint failures and recurring issues in your AI's responses

<Warning>
  **Beta** — This feature is subject to change.
</Warning>

<Tooltip tip="Self-hosted customers must upgrade to use this feature." cta="Upgrade" href="/admin/self-hosting/index#upgrades"><Badge size="md" className="text-xs px-2">Requires data plane {version_0}</Badge></Tooltip>

## How it works

Topics runs a daily pipeline on your logs:

1. **Preprocessing** — Each trace is formatted into readable text. Messages, tool calls, and nested spans become a narrative.
2. **Facets** — An LLM analyzes the preprocessed trace and extracts a concise summary based on user intent (Task), emotional tone (Sentiment), and agent behavior (Issues).
3. **Topics** — Once at least 100 facet summaries are collected, a clustering algorithm groups similar ones. For example, "User wants a refund," "Requesting a chargeback," and "Asking for money back" might all become the topic "Refund requests."
4. **Classification** — Each trace is labeled with the closest topic (e.g., "Refund requests"). Classifications appear in your logs table, where you can filter, query with SQL, and build evaluation datasets.

The pipeline runs on a set cadence:

* **Initially**: Existing logs are optionally backfilled with facet summaries.
* **Continuously**: New logs are processed contiuously as they arrive.
* **Daily**: Topics are regenerated daily from collected facet summaries. Topic generation requires at least 100 summaries.

## Enable topics

Topics live inside your project's logs.

1. Go to [<Icon icon="activity" /> **Logs**](https://www.braintrust.dev/app/~/logs) and click **<Icon icon="pentagon" /> Enable topics**.

   A dialog opens showing the three built-in facets that will be activated:

   * **Task**: Extracts the user's intent or goal from the conversation (e.g., "Creating a dataset," "Debugging an API error").
   * **Sentiment**: Extracts the user's emotional tone (e.g., "POSITIVE," "FRUSTRATED," "NEUTRAL").
   * **Issues**: Identifies problems with agent behavior or responses (e.g., "Tool call failed," "Incomplete answer").

2. Choose whether to **Apply to existing traces** or only to new traces.

3. Click **Enable topics**.

Facet summaries appear on traces as they're processed in the background. Topics and classifications follow once at least 100 summaries are collected.

To check progress, select **<Icon icon="pentagon" /> Topics > <Icon icon="info" /> Status**.

## Review insights

Topics surfaces results across your logs — in the logs table, within individual traces, as distributions across all facets, and as trends over time.

### Examine specific traces

Once topics are generated, each trace in the logs table shows a **facet summary** — the raw label extracted by the LLM — and the **classification** — the topic it was matched to. If the LLM couldn't extract a meaningful label for a facet, the summary will indicate no match and the trace won't have a classification for that facet. You can filter by classification using the **Filter** menu or SQL, sort by any facet column, and select rows to build datasets.

For a deeper look, open any trace — facet summaries and classifications are also visible in the [**<Icon icon="messages-square" /> Thread**](/observe/view-logs#view-as-a-conversation) and [**<Icon icon="triangle-dashed" /> Signals**](/observe/view-logs#test-and-apply-signals) views.

<img src="https://mintcdn.com/braintrust/7nbTi_oqpw1F8C8t/images/topics/examine-trace-topics.png?fit=max&auto=format&n=7nbTi_oqpw1F8C8t&q=85&s=98fb1441e4a1c4d70bc7afa48b44ec8c" alt="Examine a single trace" width="2710" height="1452" data-path="images/topics/examine-trace-topics.png" />

### View topic distributions

Select **<Icon icon="settings-2" /> Display > <Icon icon="rows-3" /> Row type > [<Icon icon="pentagon" /> Topics](https://www.braintrust.dev/app/~/logs?qs=topics)** to see topic distributions across all your facets. Each topic appears as a card showing its percentage of the total and trace count.

<img src="https://mintcdn.com/braintrust/7nbTi_oqpw1F8C8t/images/topics/visualize-topic-distributions.png?fit=max&auto=format&n=7nbTi_oqpw1F8C8t&q=85&s=caecb76093b85e0ace0d1621e53b75e0" alt="Visualize topic distributions" width="2712" height="1434" data-path="images/topics/visualize-topic-distributions.png" />

### Cluster a filtered subset

Filter your logs to any subset — by user, time range, or any other attribute — then use **Display** > **Cluster traces by** and choose a facet to cluster that subset on demand. For example, filter to a specific user's conversations and cluster by Task to discover how they use your product. Each topic can be expanded to show keywords and sample summaries.

<img src="https://mintcdn.com/braintrust/7nbTi_oqpw1F8C8t/images/topics/cluster-filtered-subset.png?fit=max&auto=format&n=7nbTi_oqpw1F8C8t&q=85&s=9ffd1e5c9f408cd670ca2130545c327d" alt="Cluster a filtered subset" width="2712" height="1470" data-path="images/topics/cluster-filtered-subset.png" />

### Track trends over time

When topics are active, a **Topics** chart automatically appears on [<Icon icon="chart-no-axes-column" /> **Monitor**](https://www.braintrust.dev/app/~/monitor). This chart shows classified log volume over time. Click any data point to see those traces in the logs table.

<img src="https://mintcdn.com/braintrust/Xph7wlO6esteYmUR/images/topics/topics-chart.png?fit=max&auto=format&n=Xph7wlO6esteYmUR&q=85&s=ddb546d163290fec044f2fcca17a125c" alt="Topics chart" width="810" height="668" data-path="images/topics/topics-chart.png" />

## Act on findings

Once patterns emerge, you can turn them into action: Build datasets for evaluation, automatically score problematic interactions, or assign issues to your team for review.

### Build datasets from topics

Filter logs by topic to build targeted evaluation datasets.

1. Go to [<Icon icon="activity" /> **Logs**](https://www.braintrust.dev/app/~/logs) and click <Icon icon="list-filter" /> **Filter**.
2. Select **Classifications** and choose the classification you want to filter by.

   Alternately, click **SQL** and enter a filter clause. See the [SQL reference](/reference/sql#query-by-classifications) for more query patterns.

   <CodeGroup>
     ```sql Filter by specific topic theme={"theme":{"light":"github-light","dark":"github-dark-dimmed"}}
     classifications.Task.label = "Dataset creation"
     ```

     ```sql Filter by multiple topics theme={"theme":{"light":"github-light","dark":"github-dark-dimmed"}}
     classifications.Task.label IN ("Dataset creation", "API errors")
     ```

     ```sql Combine topics across facets theme={"theme":{"light":"github-light","dark":"github-dark-dimmed"}}
     classifications.Task.label = "Checkout flow"
       AND classifications.Sentiment.label = "NEGATIVE"
     ```
   </CodeGroup>
3. Select the logs you want to include.
4. Click **+ Dataset** and choose an existing dataset or create a new one.

Common use cases:

* "Error Investigation" tasks → test your error handling
* Negative sentiment interactions → improve responses
* "Pricing Questions" → evaluate your pricing explanations

See [Build datasets](/annotate/datasets) for more on working with datasets.

### Score logs based on topics

Create scorers that flag logs with negative sentiment, penalize specific issue types, or alert when certain topics appear together.

Example scorer that flags negative checkout experiences:

<CodeGroup>
  ```typescript title="topic_scorer.ts" theme={"theme":{"light":"github-light","dark":"github-dark-dimmed"}}
  import braintrust from "braintrust";
  import { z } from "zod";

  const project = braintrust.projects.create({ name: "my-project" });

  project.scorers.create({
    name: "Checkout experience",
    slug: "checkout-experience",
    description: "Flag traces with negative checkout experiences",
    parameters: z.object({
      trace: z.any(),
    }),
    handler: async ({ trace }) => {
      if (!trace) return { score: null };

      const spans = await trace.getSpans();
      const rootSpan = spans.find((s) => s.span_id === s.root_span_id);
      if (!rootSpan) return { score: null };

      const classifications = rootSpan.classifications || {};
      const taskClassification = (classifications.Task || [{}])[0];
      const sentimentClassification = (classifications.Sentiment || [{}])[0];

      if (
        taskClassification.label === "Checkout Flow" &&
        sentimentClassification.label === "NEGATIVE"
      ) {
        return {
          score: 0,
          metadata: { reason: "Negative sentiment during checkout" },
        };
      }

      return { score: 1 };
    },
  });
  ```

  ```python title="topic_scorer.py" theme={"theme":{"light":"github-light","dark":"github-dark-dimmed"}}
  import braintrust
  from pydantic import BaseModel

  project = braintrust.projects.create(name="my-project")

  class TraceParams(BaseModel):
      trace: dict

  async def checkout_experience_scorer(trace):
      if not trace:
          return {"score": None}

      spans = await trace.get_spans()
      root_span = next(
          (s for s in spans if s.get("span_id") == s.get("root_span_id")),
          None
      )
      if not root_span:
          return {"score": None}

      classifications = root_span.get("classifications", {})
      task_classification = classifications.get("Task", [{}])[0]
      sentiment_classification = classifications.get("Sentiment", [{}])[0]

      if (
          task_classification.get("label") == "Checkout Flow"
          and sentiment_classification.get("label") == "NEGATIVE"
      ):
          return {
              "score": 0,
              "metadata": {"reason": "Negative sentiment during checkout"},
          }

      return {"score": 1}

  project.scorers.create(
      name="Checkout experience",
      slug="checkout-experience",
      description="Flag traces with negative checkout experiences",
      parameters=TraceParams,
      handler=checkout_experience_scorer,
  )
  ```
</CodeGroup>

1. Save the code to a file and push it:

   <CodeGroup>
     ```bash TypeScript theme={"theme":{"light":"github-light","dark":"github-dark-dimmed"}}
     bt functions push topic_scorer.ts
     ```

     ```bash Python theme={"theme":{"light":"github-light","dark":"github-dark-dimmed"}}
     bt functions push topic_scorer.py
     ```
   </CodeGroup>

2. Go to <Icon icon="settings-2" /> **Settings** > [<Icon icon="radio" /> **Automations**](https://www.braintrust.dev/app/~/configuration/automations) and click **+ Rule**.

3. Select your scorer, set **Scope** to **<Icon icon="list-tree" /> Trace**, configure the sampling rate, and click **Create rule**.

See [Score online](/evaluate/score-online) and [Trace-level scorers](/evaluate/write-scorers#score-traces) for more details.

### Assign topics for review

Assign logs matching specific topics for human review.

1. Go to <Icon icon="activity" /> **Logs** and click <Icon icon="list-filter" /> **Filter**.
2. Select **Classifications** and choose the classification you want to filter by.

   Alternately, click **SQL** and enter a filter clause. See the [SQL reference](/reference/sql#query-by-classifications) for more query patterns.

   <CodeGroup>
     ```sql Filter by specific topic theme={"theme":{"light":"github-light","dark":"github-dark-dimmed"}}
     classifications.Task.label = "Dataset creation"
     ```

     ```sql Filter by multiple topics theme={"theme":{"light":"github-light","dark":"github-dark-dimmed"}}
     classifications.Task.label IN ("Dataset creation", "API errors")
     ```

     ```sql Combine topics across facets theme={"theme":{"light":"github-light","dark":"github-dark-dimmed"}}
     classifications.Task.label = "Checkout flow"
       AND classifications.Sentiment.label = "NEGATIVE"
     ```
   </CodeGroup>
3. Select the logs you want to assign.
4. Select **<Icon icon="flag" /> Assign** and choose a team member.

<Tip>
  Team members receive email notifications when rows are assigned to them.
</Tip>

See [Add human feedback](/annotate/datasets) for more on human review.

## Configure and manage topics

### Adjust topics automation

To adjust sampling rate, idle timeout, or add a filter for which logs get processed, go to <Icon icon="settings-2" /> **Settings** > > [<Icon icon="radio" /> **Automations**](https://www.braintrust.dev/app/~/configuration/automations) and edit the **Topics** rule.

* **Filter**: Restricts which traces are processed. Use the basic filter UI or enter a SQL expression directly.
* **Sampling rate**: Percentage of incoming traces that get processed (default: 100%).
* **Idle time**: Seconds after the last trace before the automation is run again (default: 600).

### Check automation status

Topics runs as a daily pipeline. To see a live indicator of the current stage, go to [<Icon icon="activity" /> **Logs**](https://www.braintrust.dev/app/~/logs) and select **<Icon icon="pentagon" /> Topics > <Icon icon="info" /> Status**.

The pipeline moves through these stages:

| Stage                       | Meaning                                                                                                                   |
| --------------------------- | ------------------------------------------------------------------------------------------------------------------------- |
| **Idle**                    | Waiting until the next scheduled run. Click **<Icon icon="redo-dot" /> Re-generate topics** to trigger a run immediately. |
| **Waiting for facets**      | Waiting for at least 100 processed traces before generating topics.                                                       |
| **Recomputing topics**      | Generating new topics based on collected facet summaries.                                                                 |
| **Pending logs processing** | New topics generated; preparing to apply classifications to existing logs.                                                |
| **Processing logs**         | Applying newly generated topics to existing logs.                                                                         |

### Create custom facets

<Note>
  Custom facets require Pro or Enterprise plans. The built-in facets (Task, Sentiment, Issues) are available on all plans.
</Note>

Create a custom topic map when the built-in ones (Task, Sentiment, Issues) don't capture the patterns you need:

* **Domain-specific categories**: Your logs have patterns that built-in topic maps don't capture.
* **Too many uncategorized traces**: The built-in topic maps aren't extracting relevant summaries.
* **Wrong level of detail**: You need more specific categorization (e.g., distinguish between different API endpoints instead of just “API request”).
* **Business-specific needs**: Track patterns unique to your product (e.g., “Feature requests,” “Pricing questions,” “Integration issues”).

For example, if you're trying to assess customer churn risk, create a custom “Churn Risk” topic map that analyzes conversation summaries to determine whether a user is at low, medium, high, or critical risk of churning, based on their satisfaction, language, and outcome.

Create a custom facet when the built-in ones don't capture the patterns you need, for example, tracking churn risk signals, domain-specific task types, or product-specific failure modes.

1. Go to [<Icon icon="activity" /> **Logs**](https://www.braintrust.dev/app/~/logs) and select **<Icon icon="pentagon" /> Topics > + Create facet**.

2. Give your topic map a name and description. Example:
   * "Churn risk"
   * "Topic map for assessing customer churn risk based on conversations"

3. Choose a preprocessor to transform your trace data.
   * Select **Preprocessor** > **Thread** (default) to format traces as conversation threads.
   * Or select **+ Custom preprocessor** to write a JavaScript function that filters or transforms your data. Common patterns:

     <CodeGroup>
       ```javascript title="Extract user messages" theme={"theme":{"light":"github-light","dark":"github-dark-dimmed"}}
       // Extract only user messages from a conversation
       function userMessagesOnly(span) {
         const messages = span.input?.messages || [];
         const userMessages = messages
           .filter(m => m.role === 'user')
           .map(m => m.content)
           .join('\n\n');

         return userMessages || '(No user messages found)';
       }
       ```

       ```javascript title="Format with metadata" theme={"theme":{"light":"github-light","dark":"github-dark-dimmed"}}
       // Include metadata context in the output
       function withMetadata(span) {
         const { input, output, metadata } = span;

         let text = '';

         // Add metadata context
         if (metadata) {
           text += `Context:\n`;
           if (metadata.user_id) text += `- User: ${metadata.user_id}\n`;
           if (metadata.session_id) text += `- Session: ${metadata.session_id}\n`;
           if (metadata.environment) text += `- Environment: ${metadata.environment}\n`;
           text += '\n';
         }

         // Add interaction
         if (input) {
           text += `User: ${JSON.stringify(input)}\n\n`;
         }
         if (output) {
           text += `Assistant: ${JSON.stringify(output)}`;
         }

         return text;
       }
       ```

       ```javascript title="Filter to errors only" theme={"theme":{"light":"github-light","dark":"github-dark-dimmed"}}
       // Create a preprocessor that focuses on error cases
       function errorsOnly(span) {
         // Return null for non-error spans
         if (!span.error) {
           return null;
         }

         // Format error with context
         return `Error occurred: ${span.error}

       Input that caused error:
       ${JSON.stringify(span.input, null, 2)}

       Span attributes:
       ${JSON.stringify(span.span_attributes, null, 2)}`;
       }
       ```
     </CodeGroup>

4. Enter a prompt with clear instructions for what to extract. Example:

   ```
   Based on this conversation, assess the churn risk for this customer.

   Consider:

   - Frustration level and language used (complaints, strong negative words)
   - Whether their issue was resolved satisfactorily
   - Mentions of competitors, alternatives, or cancellation
   - Overall satisfaction signals (thanks, happy, vs angry, disappointed)
   - Severity and recurrence of issues

   Classify as:
   - LOW RISK: Satisfied customer, issue resolved, positive interaction
   - MEDIUM RISK: Some frustration but issue handled, no major red flags
   - HIGH RISK: Frustrated customer, unresolved issues, or mentions of dissatisfaction
   - CRITICAL: Explicitly mentioned canceling, switching to competitor, or very angry

   Respond with the label followed by a colon and the key risk indicators (one sentence).

   Examples:
   - "LOW_RISK: User thanked the agent and confirmed their billing question was answered."
   - "HIGH_RISK: User expressed frustration about repeated API errors and said this is unacceptable."
   - "CRITICAL: User stated they are considering switching to a competitor if issues persist."
   ```

5. If necessary, enter a case-insensitive regex to exclude facet outputs from being included in topic generation. For example, if the facet output is `'NONE'`, a regex of `'^NONE'` will exclude it from being used in topic generation.

6. Click <Icon icon="play" /> **Test** to verify extraction quality on sample traces.

7. Choose whether to **Apply to existing traces** or only to new traces.

   <Note>
     This option triggers processing across all automated facets, not just this one. Logs already processed for a given facet are skipped, but facets you previously opted out of backfilling may get processed.
   </Note>

8. Click **Create**.

### Pause a facet

To pause a facet from processing new traces:

1. Go to [<Icon icon="activity" /> **Logs**](https://www.braintrust.dev/app/~/logs).
2. Select **<Icon icon="pentagon" /> Topics** and choose the facet.
3. Click **Pause**.

Once paused, you can edit the facet to resume processing. For custom facets, you can also delete the facet and its associated set of topics.

<Warning>
  Permanently deleting a custom facet and its associated set of topics is irreversible. You will not be able to re-use the facet summaries for other topics in the future.
</Warning>

## Troubleshooting

<AccordionGroup>
  <Accordion title="Results not showing up yet">
    Topics run on a daily cycle, so new classifications won't appear immediately after enabling. [Check the automation status](#check-automation-status)  to see where the pipeline is. If you want results sooner, click **Re-generate topics** in the status dialog to trigger a run immediately.
  </Accordion>

  <Accordion title="Topics not generating">
    Topic generation requires at least 100 facet summaries. If the [automation status](#check-automation-status) shows **Waiting for facets**, more traces need to be processed first.

    * [Increase the sampling rate](#adjust-topics-automation) to process traces faster.
    * If the project has few traces, wait for more to arrive.
  </Accordion>

  <Accordion title="Poor topic quality">
    **Too many uncategorized traces**: Topics may be too specific for your data. Try [adjusting the automation filter](#adjust-topics-automation) to include a broader range of traces, or [create a custom facet](#create-custom-facets) with a more targeted prompt.

    **Topics too generic**: Refine the facet prompt to extract more specific summaries, such as distinguishing between different task subtypes.

    **Summaries too similar**: If the facet produces nearly identical summaries, clustering can't differentiate well. Review sample summaries and [adjust the prompt](#create-custom-facets) to capture more variation.
  </Accordion>

  <Accordion title="Missing classifications on logs">
    Classification runs automatically after topics are generated. [Check the automation status](#check-automation-status):

    * **Processing logs**: Classification is in progress. Wait for it to complete.
    * **Idle**: Click **Re-generate topics** in the status dialog to trigger a new cycle.
    * **Older logs**: Only approximately the most recent 500 logs are backfilled on enable. Traces outside that window won't have classifications.
  </Accordion>
</AccordionGroup>

## Next steps

* [Query classifications with SQL](/reference/sql#query-by-classifications)
* [Monitor trends](/observe/dashboards) with dashboards and charts
* [Create evaluation datasets](/annotate/datasets) from specific topics
* [Set up online scoring](/evaluate/score-online) based on classifications
