> ## Documentation Index
> Fetch the complete documentation index at: https://braintrust.dev/docs/llms.txt
> Use this file to discover all available pages before exploring further.

# Capture user feedback

> Log thumbs up/down, corrections, and comments from users

User feedback helps you understand how well your application performs in production. Braintrust lets you capture feedback and attach it to specific traces for analysis and evaluation.

## Types of feedback

Braintrust supports four types of user feedback:

* **Scores**: Numeric ratings like thumbs up/down (1 or 0) or relevance scores (0 to 1)
* **Expected values**: Corrections that show what the correct output should have been
* **Comments**: Free-form text providing additional context
* **Metadata**: Structured information like user ID or session ID

## Log feedback

Use `logFeedback()` to attach feedback to a span by its ID. Return the span ID from your application endpoint so users can reference it when submitting feedback.

<CodeGroup dropdown>
  ```typescript theme={"theme":{"light":"github-light","dark":"github-dark-dimmed"}}
  import { initLogger, wrapTraced } from "braintrust";

  const logger = initLogger({ projectName: "My Project" });

  // Return span ID from your endpoint
  export async function POST(req: Request) {
    return logger.traced(async (span) => {
      const text = await req.text();
      const result = await processRequest(text);
      span.log({ input: text, output: result });

      return {
        result,
        requestId: span.id, // Return this to the client
      };
    });
  }

  // Log feedback from a separate endpoint
  export async function POSTFeedback(req: Request) {
    const body = await req.json();

    logger.logFeedback({
      id: body.requestId, // Span ID from the original request
      scores: {
        correctness: body.score, // 1 for thumbs up, 0 for thumbs down
      },
      comment: body.comment,
      metadata: {
        user_id: body.userId,
      },
    });
  }
  ```

  ```python theme={"theme":{"light":"github-light","dark":"github-dark-dimmed"}}
  from braintrust import init_logger, traced

  logger = init_logger(project="My Project")

  # Return span ID from your endpoint
  def my_route_handler(req):
      with logger.start_span() as span:
          result = process_request(req.body)
          span.log(input=req.body, output=result)

          return {
              "result": result,
              "request_id": span.id,  # Return this to the client
          }

  # Log feedback from a separate endpoint
  def my_feedback_handler(req):
      logger.log_feedback(
          id=req.body.request_id,  # Span ID from the original request
          scores={
              "correctness": req.body.score,  # 1 for thumbs up, 0 for thumbs down
          },
          comment=req.body.comment,
          metadata={
              "user_id": req.user.id,
          },
      )
  ```
</CodeGroup>

As you log feedback, the fields update in real time in the Braintrust dashboard.

## Collect multiple scores

When multiple users provide feedback on the same span, create child spans for each submission instead of overwriting scores. Braintrust automatically averages the scores in parent spans.

<CodeGroup dropdown>
  ```typescript theme={"theme":{"light":"github-light","dark":"github-dark-dimmed"}}
  import { initLogger, currentSpan } from "braintrust";

  const logger = initLogger({ projectName: "My Project" });

  export async function POSTFeedback(req: Request) {
    const body = await req.json();

    // Create a child span for each feedback submission
    await logger.traced(
      async (span) => {
        span.log({
          scores: {
            correctness: body.score,
          },
          comment: body.comment,
          metadata: {
            user_id: body.userId,
            timestamp: new Date().toISOString(),
          },
        });
      },
      {
        name: "user_feedback",
        parent: body.requestId, // Link to original span
      },
    );
  }
  ```

  ```python theme={"theme":{"light":"github-light","dark":"github-dark-dimmed"}}
  from braintrust import init_logger

  logger = init_logger(project="My Project")

  def my_feedback_handler(req):
      # Create a child span for each feedback submission
      with logger.start_span(
          name="user_feedback",
          parent=req.body.request_id,  # Link to original span
      ) as span:
          span.log(
              scores={
                  "correctness": req.body.score,
              },
              comment=req.body.comment,
              metadata={
                  "user_id": req.user.id,
                  "timestamp": datetime.now().isoformat(),
              },
          )
  ```
</CodeGroup>

This pattern preserves all individual feedback while providing aggregated scores at the parent level.

## Next steps

* [Log attachments](/instrument/attachments) like images and PDFs
* [View your logs](/observe/view-logs) to analyze feedback
* [Build evaluation datasets](/annotate/datasets) from feedback
