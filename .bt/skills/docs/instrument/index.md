> ## Documentation Index
> Fetch the complete documentation index at: https://braintrust.dev/docs/llms.txt
> Use this file to discover all available pages before exploring further.

# Instrument your application

> Capture traces from your AI application to enable observation and evaluation

export const traceDescription_0 = "a single request or interaction in your application"

export const nestingContext_0 = "your application's execution flow"

Instrumentation captures detailed traces from your AI application, recording inputs, outputs, model parameters, latency, token usage, and metadata for every request. This gives you visibility into:

* How your application behaves with real user inputs
* Where failures and edge cases occur
* Performance bottlenecks and token usage
* Data for building evaluation datasets

<Tip>
  New to Braintrust? Start with the [tracing quickstart](/observability) to log your first trace in minutes.
</Tip>

## Anatomy of a trace

A **trace** represents {traceDescription_0}.

Every trace contains one or more **spans**, each representing a unit of work with a start and end time. Spans nest inside each other to reflect {nestingContext_0}.

Braintrust assigns a type to each span:

| Span type                              | What it represents                                                                                                                                                                                                        |
| -------------------------------------- | ------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------- |
| <Icon icon="circle-dot" /> `eval`      | The root span for an evaluation run, wrapping a `task` span for your application code. One per test case — contains the input, expected output, and all child spans.                                                      |
| <Icon icon="box" /> `task`             | A unit of application logic — a workflow, pipeline step, or named operation. In logs, the root span is always a `task` span. Multiple `task` spans can appear in a single trace.                                          |
| <Icon icon="message-circle" /> `llm`   | A single call to an LLM. Shows the model, messages, parameters, token usage, and cost.                                                                                                                                    |
| <Icon icon="parentheses" /> `function` | A named block of application logic — retrieval, formatting, routing, etc.                                                                                                                                                 |
| <Icon icon="bolt" /> `tool`            | A tool call made by the model — an external API, code execution, database query, etc.                                                                                                                                     |
| <Icon icon="percent" /> `score`        | The result of a scorer — [online](/evaluate/score-online) (in logs) or [offline](/evaluate/write-scorers) (in evaluations). Contains the score value, scorer name, and for LLM-as-a-judge scorers, the judge's reasoning. |

Each span captures:

* **Input**: The data sent to this step
* **Output**: The result produced
* **Metadata**: Model parameters, tags, custom data
* **Metrics**: Latency, token counts, costs
* **Scores**: Quality metrics (added later)

## What gets captured

Every instrumented request automatically captures:

* Request inputs and outputs
* Model parameters (model name, temperature, etc.)
* Timing information (start time, duration)
* Token usage and costs
* Nested function calls and tool invocations
* Errors and exceptions
* Custom metadata you add

This data flows directly to Braintrust, where you can view it in real time, filter and search, add human feedback, and build evaluation datasets.

## How to instrument

Braintrust makes it easy to get started with auto-instrumentation, which traces your LLM calls with no per-call code changes. When you need more control, you can trace your application logic — data retrieval, tool calls, business logic — alongside those calls.

<CardGroup cols={2}>
  <Card title="Trace LLM calls" icon="zap" href="/instrument/trace-llm-calls">
    Trace LLM calls from AI providers and frameworks
  </Card>

  <Card title="Trace application logic" icon="code" href="/instrument/trace-application-logic">
    Trace non-LLM application logic like data retrieval and tool calls
  </Card>
</CardGroup>

## Provider and framework support

Braintrust integrates with all major AI providers and frameworks:

* **AI Providers**: OpenAI, Anthropic, Gemini, AWS Bedrock, Azure, Mistral, Together, Groq, and [many more](/integrations#ai-providers)
* **Frameworks and Libraries**: LangChain, LangGraph, CrewAI, Vercel AI SDK, Pydantic AI, DSPy, and [many more](/integrations#agent-frameworks)

Browse the complete [integrations directory](/integrations) to find setup guides for your stack.

## Next steps

Get started instrumenting your application:

* [Trace LLM calls](/instrument/trace-llm-calls) to automatically capture LLM calls
* [Trace application logic](/instrument/trace-application-logic) for application logic
* [Capture user feedback](/instrument/user-feedback) like thumbs up/down
