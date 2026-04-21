> ## Documentation Index
> Fetch the complete documentation index at: https://braintrust.dev/docs/llms.txt
> Use this file to discover all available pages before exploring further.

# Add labels and corrections

> Annotate traces with tags, comments, and expected values

Beyond numeric scores, you can annotate traces with tags, comments, expected values, and metadata to provide context and build better datasets. These annotations flow between logs, datasets, and experiments.

After reviewing and scoring traces, use tags, comments, and corrections to further organize and enrich your data for dataset creation.

## Apply tags

Tags categorize and organize traces across your project. Use tags to mark traces for review, indicate status, or group related examples.

<Tip>
  You can also [apply tags to entire datasets](/annotate/datasets#tag-and-star-datasets) to organize them in the datasets list.
</Tip>

<Tabs>
  <Tab title="UI" icon="mouse-pointer-2">
    1. Select one or more traces.
    2. Click **<Icon icon="tag" /> Tag** in the toolbar.
    3. Select or create tags to apply.

    <Note>
      Tags are configured in [project tag settings](/admin/projects#add-tags) and shared across all objects — logs, experiments, dataset records, and entire datasets.
    </Note>
  </Tab>

  <Tab title="SDK" icon="terminal">
    Include tags when logging or providing feedback:

    <CodeGroup dropdown>
      ```typescript theme={"theme":{"light":"github-light","dark":"github-dark-dimmed"}}
      import { initLogger } from "braintrust";

      const logger = initLogger({ projectName: "My Project" });

      // When logging
      logger.traced(async (span) => {
        span.log({
          input,
          output: result,
          tags: ["user-action", "triage"],
        });
      });

      // When logging feedback
      logger.logFeedback({
        id: spanId,
        scores: { correctness: score },
        tags: ["needs-review"],
      });
      ```

      ```python theme={"theme":{"light":"github-light","dark":"github-dark-dimmed"}}
      from braintrust import init_logger

      logger = init_logger(project="My Project")

      # When logging
      with logger.start_span() as span:
          span.log(input=input, output=result, tags=["user-action", "triage"])

      # When logging feedback
      logger.log_feedback(
          id=span_id,
          scores={"correctness": score},
          tags=["needs-review"],
      )
      ```
    </CodeGroup>

    <Note>
      Tags can be applied to any span in a trace. When viewing traces, tags from all spans are aggregated and displayed together at the trace level. When you log additional tags, they are automatically merged (union), rather than replaced.
    </Note>

    <Tip>
      When filtering logs by tags, use `ANY_SPAN(tags INCLUDES 'tag-name')` in SQL queries to find traces where any span contains the tag. This is useful when combining tag filters with other span-level filters like scores. See [Analyze based on tags and scores](/reference/sql#analyze-based-on-tags-and-scores) for examples.
    </Tip>
  </Tab>
</Tabs>

## Add comments

Comments provide free-form context and explanations. Use comments to explain why a trace succeeded or failed, note patterns or edge cases, share insights with teammates, or document corrections.

<Tabs>
  <Tab title="UI" icon="mouse-pointer-2">
    Open any trace and add comments in the trace view:

    <video className="border rounded-md" loop autoPlay muted playsInline poster="/images/guides/human-review/comment-poster.png">
      <source src="https://mintcdn.com/braintrust/ORZ9J5LROFjITLRP/images/guides/human-review/comment.mp4?fit=max&auto=format&n=ORZ9J5LROFjITLRP&q=85&s=b8aeec14bc3456cc40591c3e3a1e085c" type="video/mp4" data-path="images/guides/human-review/comment.mp4" />
    </video>

    Copy links to comments to share with teammates. Comments are searchable using the filter menu.

    <Note>
      Mention team members in comments to notify them and draw their attention to specific traces. Type `@` followed by their name and select from the autocomplete dropdown. Mentioned users receive email notifications with direct links to the specific row and comment.
    </Note>
  </Tab>

  <Tab title="SDK" icon="terminal">
    Include comments when logging feedback:

    <CodeGroup dropdown>
      ```typescript theme={"theme":{"light":"github-light","dark":"github-dark-dimmed"}}
      logger.logFeedback({
        id: spanId,
        comment: "User reported incorrect information in the response",
        scores: { correctness: 0 },
      });
      ```

      ```python theme={"theme":{"light":"github-light","dark":"github-dark-dimmed"}}
      logger.log_feedback(
          id=span_id,
          comment="User reported incorrect information in the response",
          scores={"correctness": 0},
      )
      ```
    </CodeGroup>
  </Tab>
</Tabs>

## Update expected values

Expected values define the correct or ideal output for a given input. Update expected values to provide ground truth for evaluation, document user corrections, specify multiple acceptable answers, or label categorical data.

<Tabs>
  <Tab title="UI" icon="mouse-pointer-2">
    Click any expected field in a trace to edit it directly. You can:

    * Enter structured JSON data
    * Use form-based editing (if schemas are defined)
    * Write categorical labels via human review scores
    * Copy expected values from other spans

          <img src="https://mintcdn.com/braintrust/ORZ9J5LROFjITLRP/images/guides/human-review/expected-fields.png?fit=max&auto=format&n=ORZ9J5LROFjITLRP&q=85&s=406359641fc9f3de4f70086d56f236dd" alt="Write to expected" width="1852" height="966" data-path="images/guides/human-review/expected-fields.png" />

    <Tip>
      You can also [create human review scores](/annotate/human-review#configure-review-scores) when reviewing traces.
    </Tip>
  </Tab>

  <Tab title="SDK" icon="terminal">
    Include expected values when logging feedback or updating datasets:

    <CodeGroup dropdown>
      ```typescript theme={"theme":{"light":"github-light","dark":"github-dark-dimmed"}}
      // Update via feedback
      logger.logFeedback({
        id: spanId,
        expected: { answer: "The correct answer is 42" },
        comment: "User provided correction",
      });

      // Update dataset record
      dataset.update({
        id: recordId,
        expected: { answer: "Updated expected value" },
      });
      ```

      ```python theme={"theme":{"light":"github-light","dark":"github-dark-dimmed"}}
      # Update via feedback
      logger.log_feedback(
          id=span_id,
          expected={"answer": "The correct answer is 42"},
          comment="User provided correction",
      )

      # Update dataset record
      dataset.update(
          id=record_id,
          expected={"answer": "Updated expected value"},
      )
      ```
    </CodeGroup>
  </Tab>
</Tabs>

## Add metadata

Metadata provides structured context for filtering and analysis. Common metadata includes user IDs and session IDs, feature flags or A/B test variants, geographic or demographic information, request source or client type, and custom business context.

<Tabs>
  <Tab title="SDK" icon="terminal">
    Add metadata when logging or via feedback:

    <CodeGroup dropdown>
      ```typescript theme={"theme":{"light":"github-light","dark":"github-dark-dimmed"}}
      // When logging
      span.log({
        input,
        output: result,
        metadata: {
          user_id: userId,
          feature_variant: "test-a",
          request_source: "mobile-app",
        },
      });

      // Via feedback
      logger.logFeedback({
        id: spanId,
        metadata: {
          reviewer: "jane@company.com",
          review_date: new Date().toISOString(),
          category: "edge-case",
        },
      });
      ```

      ```python theme={"theme":{"light":"github-light","dark":"github-dark-dimmed"}}
      # When logging
      span.log(
          input=input,
          output=result,
          metadata={
              "user_id": user_id,
              "feature_variant": "test-a",
              "request_source": "mobile-app",
          },
      )

      # Via feedback
      logger.log_feedback(
          id=span_id,
          metadata={
              "reviewer": "jane@company.com",
              "review_date": datetime.now().isoformat(),
              "category": "edge-case",
          },
      )
      ```
    </CodeGroup>
  </Tab>
</Tabs>

## Filter by annotations

Use filters to find annotated traces:

```sql theme={"theme":{"light":"github-light","dark":"github-dark-dimmed"}}
WHERE tags INCLUDES "needs-review"
```

```sql theme={"theme":{"light":"github-light","dark":"github-dark-dimmed"}}
WHERE comment IS NOT NULL
```

```sql theme={"theme":{"light":"github-light","dark":"github-dark-dimmed"}}
WHERE metadata.category = "edge-case"
```

```sql theme={"theme":{"light":"github-light","dark":"github-dark-dimmed"}}
WHERE expected IS NOT NULL
```

Combine filters to build precise queries for dataset curation.

## Next steps

* [Build datasets](/annotate/datasets) from annotated traces
* [Add human feedback](/annotate/human-review) for structured scoring
* [Run evaluations](/evaluate/run-evaluations) using labeled datasets
* [Export data](/annotate/export) with annotations intact
