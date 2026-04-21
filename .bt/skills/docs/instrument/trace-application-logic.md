> ## Documentation Index
> Fetch the complete documentation index at: https://braintrust.dev/docs/llms.txt
> Use this file to discover all available pages before exploring further.

# Trace application logic

> Manually instrument application logic beyond LLM calls

While [tracing setup](/instrument/trace-llm-calls) automatically logs LLM calls, you often need to trace additional application logic like data retrieval, preprocessing, business logic, or tool invocations. Custom tracing lets you capture these operations.

## Trace function calls

Braintrust SDKs provide tools to trace function execution and capture inputs, outputs, and errors:

* **Python SDK** uses the `@traced` decorator to automatically wrap functions
* **TypeScript SDK** uses `wrapTraced()` to create traced function wrappers
* **Go SDK** uses OpenTelemetry's manual span management with `tracer.Start()` and `span.End()`

All approaches achieve the same result—capturing function-level observability—but with different ergonomics suited to each language's idioms.

<CodeGroup dropdown>
  ```typescript theme={"theme":{"light":"github-light","dark":"github-dark-dimmed"}}
  import { initLogger, wrapTraced } from "braintrust";

  const logger = initLogger({ projectName: "My Project" });

  // Wrap a function to trace it automatically
  const fetchUserData = wrapTraced(async function fetchUserData(userId: string) {
    // This function's input (userId) and output (return value) are logged
    const response = await fetch(`/api/users/${userId}`);
    return response.json();
  });

  // Use the function normally
  const userData = await fetchUserData("user-123");
  ```

  ```python theme={"theme":{"light":"github-light","dark":"github-dark-dimmed"}}
  from braintrust import init_logger, traced

  logger = init_logger(project="My Project")

  # Decorate a function to trace it automatically
  @traced
  def fetch_user_data(user_id: str):
      # This function's input (user_id) and output (return value) are logged
      response = requests.get(f"/api/users/{user_id}")
      return response.json()

  # Use the function normally
  user_data = fetch_user_data("user-123")
  ```

  ```go theme={"theme":{"light":"github-light","dark":"github-dark-dimmed"}}
  package main

  import (
  	"context"
  	"fmt"
  	"go.opentelemetry.io/otel"
  	"go.opentelemetry.io/otel/attribute"
  )

  func fetchUserData(ctx context.Context, userID string) (map[string]interface{}, error) {
  	tracer := otel.Tracer("my-service")
  	ctx, span := tracer.Start(ctx, "fetchUserData")
  	defer span.End()

  	// Log input
  	span.SetAttributes(attribute.String("input.user_id", userID))

  	// Your application logic
  	userData := map[string]interface{}{"id": userID, "name": "John"}

  	// Log output
  	span.SetAttributes(attribute.String("output", fmt.Sprintf("%v", userData)))

  	return userData, nil
  }

  func main() {
  	// Use the function normally
  	userData, _ := fetchUserData(context.Background(), "user-123")
  	fmt.Println(userData)
  }
  ```
</CodeGroup>

The traced function automatically creates a span with:

* Function name as the span name
* Function arguments as input
* Return value as output
* Any errors that occur

## Add metadata and tags

Enrich spans with custom metadata and tags to make them easier to filter and analyze.

<Note>
  Tags from all spans in a trace are aggregated together at the trace level and automatically merged (union) rather than replaced.
</Note>

### Within a span

Attach metadata and tags from within the function body. This is useful for data that's only available during execution, like computed values or results from intermediate steps.

* In TypeScript and Python, use `span.log()`.
* In Go, C#, Ruby, and Java, use the OTel `setAttribute` API. Custom attributes appear in the span's metadata field. Use `braintrust.tags` for tags. For LLM-specific OTel attributes, see [OpenTelemetry](/integrations/sdk-integrations/opentelemetry#manual-tracing).

<CodeGroup dropdown>
  ```typescript theme={"theme":{"light":"github-light","dark":"github-dark-dimmed"}}
  import OpenAI from "openai";
  import { initLogger, wrapTraced } from "braintrust";

  const logger = initLogger({ projectName: "My Project" });
  const openai = new OpenAI();

  const processDocument = wrapTraced(async function processDocument(
    docId: string,
    span,
  ) {
    // Add metadata and tags
    span.log({
      metadata: {
        documentId: docId,
        processingType: "summarization",
        userId: "user-123",
      },
      tags: ["document-processing", "summarization"],
    });

    const response = await openai.responses.create({
      model: "gpt-5-mini",
      input: `Summarize document ${docId}`,
    });

    return response.output_text;
  });

  await processDocument("doc-123");
  ```

  ```python theme={"theme":{"light":"github-light","dark":"github-dark-dimmed"}}
  from openai import OpenAI
  from braintrust import init_logger, traced, current_span

  logger = init_logger(project="My Project")
  openai = OpenAI()

  @traced
  def process_document(doc_id: str):
      span = current_span()
      # Add metadata and tags
      span.log(
          metadata={
              "document_id": doc_id,
              "processing_type": "summarization",
              "user_id": "user-123",
          },
          tags=["document-processing", "summarization"],
      )

      response = openai.responses.create(
          model="gpt-5-mini",
          input=f"Summarize document {doc_id}",
      )

      return response.output_text

  process_document("doc-123")
  ```

  ```go theme={"theme":{"light":"github-light","dark":"github-dark-dimmed"}}
  package main

  import (
  	"context"
  	"fmt"
  	"log"
  	"os"

  	"github.com/braintrustdata/braintrust-sdk-go"
  	traceopenai "github.com/braintrustdata/braintrust-sdk-go/trace/contrib/openai"
  	"github.com/openai/openai-go"
  	"github.com/openai/openai-go/option"
  	"github.com/openai/openai-go/responses"
  	"go.opentelemetry.io/otel"
  	"go.opentelemetry.io/otel/attribute"
  	sdktrace "go.opentelemetry.io/otel/sdk/trace"
  )

  func processDocument(ctx context.Context, client *openai.Client, docID string) (string, error) {
  	tracer := otel.Tracer("my-service")
  	ctx, span := tracer.Start(ctx, "processDocument")
  	defer span.End()

  	// Add custom metadata and tags
  	span.SetAttributes(
  		attribute.String("app.document_id", docID),
  		attribute.String("app.processing_type", "summarization"),
  		attribute.String("app.user_id", "user-123"),
  		attribute.StringSlice("braintrust.tags", []string{"document-processing", "summarization"}),
  	)

  	response, err := client.Responses.New(ctx, responses.ResponseNewParams{
  		Model: "gpt-5-mini",
  		Input: responses.ResponseNewParamsInputUnion{OfString: openai.String(fmt.Sprintf("Summarize document %s", docID))},
  	})
  	if err != nil {
  		return "", err
  	}

  	return response.OutputText(), nil
  }

  func main() {
  	tp := sdktrace.NewTracerProvider()
  	defer tp.Shutdown(context.Background())
  	otel.SetTracerProvider(tp)

  	_, err := braintrust.New(tp,
  		braintrust.WithProject("My Project"),
  		braintrust.WithAPIKey(os.Getenv("BRAINTRUST_API_KEY")),
  	)
  	if err != nil {
  		log.Fatal(err)
  	}

  	client := openai.NewClient(
  		option.WithAPIKey(os.Getenv("OPENAI_API_KEY")),
  		option.WithMiddleware(traceopenai.NewMiddleware()),
  	)

  	summary, err := processDocument(context.Background(), &client, "doc-123")
  	if err != nil {
  		log.Fatal(err)
  	}
  	fmt.Println(summary)
  }
  ```

  ```csharp theme={"theme":{"light":"github-light","dark":"github-dark-dimmed"}}
  using Braintrust.Sdk;
  using Braintrust.Sdk.OpenAI;
  using OpenAI.Chat;

  var braintrust = Braintrust.Sdk.Braintrust.Get();
  var activitySource = braintrust.GetActivitySource();
  var openAIClient = BraintrustOpenAI.WrapOpenAI(
      activitySource, Environment.GetEnvironmentVariable("OPENAI_API_KEY")!);

  var summary = await ProcessDocument("doc-123");
  Console.WriteLine(summary);

  async Task<string> ProcessDocument(string docId)
  {
      using var activity = activitySource.StartActivity("processDocument");

      // Add custom metadata and tags
      activity?.SetTag("app.document_id", docId);
      activity?.SetTag("app.processing_type", "summarization");
      activity?.SetTag("app.user_id", "user-123");
      activity?.SetTag("braintrust.tags", new[] { "document-processing", "summarization" });

      var response = await openAIClient.GetChatClient("gpt-5-mini")
          .CompleteChatAsync([new UserChatMessage($"Summarize document {docId}")]);

      return response.Value.Content[0].Text;
  }
  ```

  ```ruby theme={"theme":{"light":"github-light","dark":"github-dark-dimmed"}}
  require 'braintrust'
  require 'openai'

  Braintrust.init(
    api_key: ENV['BRAINTRUST_API_KEY'],
    default_project: 'My Project (Ruby)',
    auto_instrument: false
  )

  client = OpenAI::Client.new(access_token: ENV['OPENAI_API_KEY'])
  Braintrust.instrument!(:ruby_openai, target: client)

  tracer = OpenTelemetry.tracer_provider.tracer("my-app")

  tracer.in_span("processDocument") do |span|
    # Add custom metadata and tags
    span.set_attribute("app.document_id", "doc-123")
    span.set_attribute("app.processing_type", "summarization")
    span.set_attribute("app.user_id", "user-123")
    span.set_attribute("braintrust.tags", ["document-processing", "summarization"])

    response = client.responses.create(
      parameters: {
        model: 'gpt-5-mini',
        input: 'Summarize document doc-123',
      }
    )
    puts response.dig('output', 0, 'content', 0, 'text')
  end
  ```

  ```java theme={"theme":{"light":"github-light","dark":"github-dark-dimmed"}}
  import dev.braintrust.Braintrust;
  import dev.braintrust.config.BraintrustConfig;
  import dev.braintrust.instrumentation.openai.BraintrustOpenAI;
  import com.openai.client.OpenAIClient;
  import com.openai.client.okhttp.OpenAIOkHttpClient;
  import com.openai.models.chat.completions.ChatCompletionCreateParams;
  import com.openai.models.chat.completions.ChatCompletionUserMessageParam;
  import io.opentelemetry.api.common.AttributeKey;
  import io.opentelemetry.api.trace.Span;
  import io.opentelemetry.api.trace.Tracer;
  import java.util.List;

  class Main {
      public static void main(String[] args) throws Exception {
          var config = BraintrustConfig.builder()
              .apiKey(System.getenv("BRAINTRUST_API_KEY"))
              .defaultProjectName("My Project (Java)")
              .build();
          var braintrust = Braintrust.get(config);
          var openTelemetry = braintrust.openTelemetryCreate();

          OpenAIClient openaiClient = OpenAIOkHttpClient.builder()
              .apiKey(System.getenv("OPENAI_API_KEY"))
              .build();
          OpenAIClient client = BraintrustOpenAI.wrapOpenAI(openTelemetry, openaiClient);

          Tracer tracer = openTelemetry.getTracer("my-instrumentation");

          Span span = tracer.spanBuilder("processDocument").startSpan();
          try (var scope = span.makeCurrent()) {
              // Add custom metadata and tags
              span.setAttribute("app.document_id", "doc-123");
              span.setAttribute("app.processing_type", "summarization");
              span.setAttribute("app.user_id", "user-123");
              span.setAttribute(
                  AttributeKey.stringArrayKey("braintrust.tags"),
                  List.of("document-processing", "summarization")
              );

              var params = ChatCompletionCreateParams.builder()
                  .model("gpt-5-mini")
                  .addMessage(ChatCompletionUserMessageParam.builder()
                      .content("Summarize document doc-123")
                      .build())
                  .build();
              var response = client.chat().completions().create(params);
              System.out.println(response.choices().get(0).message().content().orElse(""));
          } finally {
              span.end();
          }
      }
  }
  ```
</CodeGroup>

### At span creation

The TypeScript and Python SDKs support passing metadata and tags at span creation time, which avoids a separate `span.log()` call. This is useful at request entry points where you have request-scoped data — like a user ID or org ID — already available and don't want to thread it through helper functions.

<CodeGroup dropdown>
  ```typescript theme={"theme":{"light":"github-light","dark":"github-dark-dimmed"}}
  import OpenAI from "openai";
  import { initLogger } from "braintrust";

  const logger = initLogger({ projectName: "My Project" });
  const openai = new OpenAI();

  async function handleRequest(userId: string, orgId: string, prompt: string) {
    return logger.traced(
      async (span) => {
        const response = await openai.responses.create({
          model: "gpt-5-mini",
          input: prompt,
        });
        return response.output_text;
      },
      {
        event: {
          metadata: { userId, orgId },
          tags: ["handle-request"],
        },
      },
    );
  }

  await handleRequest("user-123", "org-456", "What is the capital of France?");
  ```

  ```python theme={"theme":{"light":"github-light","dark":"github-dark-dimmed"}}
  from openai import OpenAI
  from braintrust import init_logger

  logger = init_logger(project="My Project")
  openai = OpenAI()

  def handle_request(user_id: str, org_id: str, prompt: str):
      with logger.start_span(
          name="handleRequest",
          metadata={"user_id": user_id, "org_id": org_id},
          tags=["handle-request"],
      ) as span:
          response = openai.responses.create(
              model="gpt-5-mini",
              input=prompt,
          )
          return response.output_text

  handle_request("user-123", "org-456", "What is the capital of France?")
  ```
</CodeGroup>

## Manual spans

For more control, create spans manually using `logger.traced()` or `startSpan()`:

<CodeGroup dropdown>
  ```typescript theme={"theme":{"light":"github-light","dark":"github-dark-dimmed"}}
  import { initLogger } from "braintrust";

  const logger = initLogger({ projectName: "My Project" });

  async function complexWorkflow(input: string) {
    // Create a manual span
    await logger.traced(
      async (span) => {
        span.log({ input });

        // Step 1
        const data = await fetchData(input);
        span.log({ metadata: { step: "fetch", recordCount: data.length } });

        // Step 2
        const processed = await processData(data);
        span.log({ metadata: { step: "process" } });

        // Log final output
        span.log({ output: processed });
      },
      { name: "complexWorkflow", type: "task" },
    );
  }
  ```

  ```python theme={"theme":{"light":"github-light","dark":"github-dark-dimmed"}}
  from braintrust import init_logger

  logger = init_logger(project="My Project")

  def complex_workflow(input_text: str):
      # Create a manual span
      with logger.start_span(name="complexWorkflow", span_attributes={"type": "task"}) as span:
          span.log(input=input_text)

          # Step 1
          data = fetch_data(input_text)
          span.log(metadata={"step": "fetch", "record_count": len(data)})

          # Step 2
          processed = process_data(data)
          span.log(metadata={"step": "process"})

          # Log final output
          span.log(output=processed)
  ```

  ```go theme={"theme":{"light":"github-light","dark":"github-dark-dimmed"}}
  package main

  import (
  	"context"
  	"encoding/json"
  	"go.opentelemetry.io/otel"
  	"go.opentelemetry.io/otel/attribute"
  )

  func complexWorkflow(ctx context.Context, input string) error {
  	tracer := otel.Tracer("my-service")
  	ctx, span := tracer.Start(ctx, "complexWorkflow")
  	defer span.End()

  	span.SetAttributes(attribute.String("type", "task"))
  	span.SetAttributes(attribute.String("input", input))

  	// Step 1
  	data := []string{"item1", "item2"} // Placeholder for fetchData
  	metadata1, _ := json.Marshal(map[string]any{"step": "fetch", "record_count": len(data)})
  	span.SetAttributes(attribute.String("braintrust.metadata", string(metadata1)))

  	// Step 2
  	processed := "processed data" // Placeholder for processData
  	metadata2, _ := json.Marshal(map[string]any{"step": "process"})
  	span.SetAttributes(attribute.String("braintrust.metadata", string(metadata2)))

  	// Log final output
  	span.SetAttributes(attribute.String("output", processed))

  	return nil
  }

  func main() {
  	_ = complexWorkflow(context.Background(), "user input")
  }
  ```
</CodeGroup>

<Note>
  Span names must always be strings. Passing non-string values will cause validation failures. See [Troubleshooting](#troubleshooting-span-names-must-be-strings) for details.
</Note>

## Nest spans

Spans automatically nest when called within other spans, creating a hierarchy that represents your application's execution flow:

<CodeGroup dropdown>
  ```typescript theme={"theme":{"light":"github-light","dark":"github-dark-dimmed"}}
  import { initLogger, wrapTraced } from "braintrust";

  const logger = initLogger({ projectName: "My Project" });

  const fetchData = wrapTraced(async function fetchData(query: string) {
    // Database query logic
    return await db.query(query);
  });

  const transformData = wrapTraced(async function transformData(data: any[]) {
    // Data transformation logic
    return data.map((item) => transform(item));
  });

  // Parent span containing child spans
  const pipeline = wrapTraced(async function pipeline(input: string) {
    const data = await fetchData(input); // Child span 1
    const transformed = await transformData(data); // Child span 2
    return transformed;
  });

  // Creates a trace with nested spans:
  // pipeline
  //   └─ fetchData
  //   └─ transformData
  await pipeline("user query");
  ```

  ```python theme={"theme":{"light":"github-light","dark":"github-dark-dimmed"}}
  from braintrust import init_logger, traced

  logger = init_logger(project="My Project")

  @traced
  def fetch_data(query: str):
      # Database query logic
      return db.query(query)

  @traced
  def transform_data(data: list):
      # Data transformation logic
      return [transform(item) for item in data]

  # Parent span containing child spans
  @traced
  def pipeline(input_text: str):
      data = fetch_data(input_text)  # Child span 1
      transformed = transform_data(data)  # Child span 2
      return transformed

  # Creates a trace with nested spans:
  # pipeline
  #   └─ fetch_data
  #   └─ transform_data
  pipeline("user query")
  ```
</CodeGroup>

This nesting makes it easy to see which operations happened as part of a larger workflow.

## Troubleshooting

<AccordionGroup>
  <Accordion title="Span names must be strings">
    If you pass a non-string value (like an object or array) to the `name` field of a span, your logs will not appear in the UI - they will be hidden due to schema validation failure. Span names must always be strings.

    Before passing a value to the `name` parameter in tracing functions, ensure it is a string:

    <CodeGroup dropdown>
      ```typescript theme={"theme":{"light":"github-light","dark":"github-dark-dimmed"}}
      // ❌ Wrong - passing an object
      await logger.traced(
        async (span) => { /* ... */ },
        { name: { operation: "process" } } // This will fail validation
      );

      // ✅ Correct - passing a string
      await logger.traced(
        async (span) => { /* ... */ },
        { name: "process" }
      );

      // ✅ Correct - validating dynamic names
      const spanName = typeof customName === "string"
        ? customName
        : String(customName);

      await logger.traced(
        async (span) => { /* ... */ },
        { name: spanName }
      );
      ```

      ```python theme={"theme":{"light":"github-light","dark":"github-dark-dimmed"}}
      # ❌ Wrong - passing a dictionary
      with logger.start_span(name={"operation": "process"}) as span:  # This will fail validation
          pass

      # ✅ Correct - passing a string
      with logger.start_span(name="process") as span:
          pass

      # ✅ Correct - validating dynamic names
      span_name = custom_name if isinstance(custom_name, str) else str(custom_name)

      with logger.start_span(name=span_name) as span:
          pass
      ```
    </CodeGroup>
  </Accordion>
</AccordionGroup>

## Next steps

* [Advanced tracing](/instrument/advanced-tracing) patterns and techniques
* [Capture user feedback](/instrument/user-feedback) like thumbs up/down
* [View your logs](/observe/view-logs) in the Braintrust dashboard
