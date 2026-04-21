> ## Documentation Index
> Fetch the complete documentation index at: https://braintrust.dev/docs/llms.txt
> Use this file to discover all available pages before exploring further.

# Stream responses

> Return incremental responses from prompts and functions

Streaming enables prompts and functions to return responses incrementally, improving perceived latency and user experience. Braintrust automatically handles streaming for prompts called through the gateway and when using SDK wrappers.

## Enable streaming

Set `stream: true` when calling prompts or functions:

<CodeGroup dropdown>
  ```typescript theme={"theme":{"light":"github-light","dark":"github-dark-dimmed"}}
  import { invoke } from "braintrust";

  const stream = await invoke({
    projectName: "My Project",
    slug: "summarizer",
    input: { text: "Long text to summarize..." },
    stream: true,
  });

  for await (const chunk of stream) {
    process.stdout.write(chunk);
  }
  ```

  ```python theme={"theme":{"light":"github-light","dark":"github-dark-dimmed"}}
  from braintrust import invoke

  stream = invoke(
      project_name="My Project",
      slug="summarizer",
      input={"text": "Long text to summarize..."},
      stream=True,
  )

  for chunk in stream:
      print(chunk, end="")
  ```
</CodeGroup>

Streaming works automatically through the gateway and logs the complete response to Braintrust.

## Stream format

Braintrust uses [Server-Sent Events (SSE)](https://developer.mozilla.org/en-US/docs/Web/API/Server-sent_events/Using_server-sent_events) format for streaming responses. The Braintrust SDK and UI automatically parse the SSE stream, and we have adapters for common libraries like the [Vercel AI SDK](https://sdk.vercel.ai/docs).

### Why this format

Streaming is powerful for consuming LLM outputs, but the predominant "chat" data structure produced by modern LLMs is more complex than most applications need. The most common use cases are to (a) convert the text of the first message into a string or (b) parse the arguments of the first tool call into a JSON object. The Braintrust SSE format is optimized to make these use cases easy to parse, while also supporting more advanced scenarios like parallel tool calls.

### Event types

SSE events consist of three fields: `id` (optional), `event` (optional), and `data`. The Braintrust SSE format always sets `event` and `data`, and never sets `id`.

* **`text_delta`**: Incremental text chunk (JSON-encoded string)
* **`json_delta`**: Incremental JSON snippet (concatenate all deltas and parse as JSON at the end)
* **`error`**: Error during execution (JSON-encoded string)
* **`progress`**: Intermediate events from function execution (JSON-encoded object)
* **`done`**: Stream completion signal (empty data)

### Text deltas

A `text_delta` is a snippet of text, which is JSON-encoded:

```
event: text_delta
data: "this is a line\nbreak"

event: text_delta
data: "with some \"nested quotes\"."

event: done
data:
```

As you process a `text_delta`, JSON-decode the string and display it directly.

### JSON deltas

A `json_delta` is a snippet of JSON-encoded data, which cannot necessarily be parsed on its own:

```
event: json_delta
data: {"name": "Cecil",

event: json_delta
data: "age": 30}

event: done
data:
```

As you process `json_delta` events, concatenate the strings together and parse them as JSON at the end of the stream.

### Progress events

A `progress` event is a JSON-encoded object containing intermediate events produced by functions while executing:

```json theme={"theme":{"light":"github-light","dark":"github-dark-dimmed"}}
{
  "id": "A span id for this event",
  "object_type": "prompt" | "tool" | "scorer" | "task",
  "format": "llm" | "code" | "global",
  "output_type": "completion" | "score" | "any",
  "name": "The name of the function or prompt",
  "event": "text_delta" | "json_delta" | "error" | "start" | "done",
  "data": "The delta or error message"
}
```

The `event` field is the type of event produced by the intermediate function call, and the `data` field follows the same format as `text_delta` and `json_delta` events.

A `start` event is a progress event with `event: "start"` and an empty string for `data`. Start is not guaranteed to be sent and is for display purposes only.

## Stream with gateway

The gateway automatically streams responses from all providers:

<CodeGroup dropdown>
  ```typescript theme={"theme":{"light":"github-light","dark":"github-dark-dimmed"}}
  import { OpenAI } from "openai";

  const client = new OpenAI({
    baseURL: "https://gateway.braintrust.dev/v1",
    apiKey: process.env.BRAINTRUST_API_KEY,
  });

  const stream = await client.chat.completions.create({
    model: "claude-sonnet-4-5-20250929",
    messages: [{ role: "user", content: "Write a story" }],
    stream: true,
  });

  for await (const chunk of stream) {
    const content = chunk.choices[0]?.delta?.content;
    if (content) {
      process.stdout.write(content);
    }
  }
  ```

  ```python theme={"theme":{"light":"github-light","dark":"github-dark-dimmed"}}
  import os
  from openai import OpenAI

  client = OpenAI(
      base_url="https://gateway.braintrust.dev/v1",
      api_key=os.getenv("BRAINTRUST_API_KEY"),
  )

  stream = client.chat.completions.create(
      model="claude-sonnet-4-5-20250929",
      messages=[{"role": "user", "content": "Write a story"}],
      stream=True,
  )

  for chunk in stream:
      content = chunk.choices[0].delta.content
      if content:
          print(content, end="")
  ```
</CodeGroup>

## Stream with wrapped clients

AI provider wrappers automatically handle streaming:

<CodeGroup dropdown>
  ```typescript theme={"theme":{"light":"github-light","dark":"github-dark-dimmed"}}
  import { wrapOpenAI } from "braintrust";
  import { OpenAI } from "openai";

  const client = wrapOpenAI(new OpenAI());

  const stream = await client.chat.completions.create({
    model: "gpt-4o",
    messages: [{ role: "user", content: "Write a story" }],
    stream: true,
  });

  for await (const chunk of stream) {
    const content = chunk.choices[0]?.delta?.content;
    if (content) {
      process.stdout.write(content);
    }
  }
  ```

  ```python theme={"theme":{"light":"github-light","dark":"github-dark-dimmed"}}
  from braintrust import wrap_openai
  from openai import OpenAI

  client = wrap_openai(OpenAI())

  stream = client.chat.completions.create(
      model="gpt-4o",
      messages=[{"role": "user", "content": "Write a story"}],
      stream=True,
  )

  for chunk in stream:
      content = chunk.choices[0].delta.content
      if content:
          print(content, end="")
  ```
</CodeGroup>

The complete streamed response is automatically logged to Braintrust.

## Stream via REST API

Call the Data API with streaming enabled.

<Note>
  In the examples below, organizations on the EU [data plane](/admin/organizations#data-plane-region) should replace `api.braintrust.dev` with `api-eu.braintrust.dev`.
</Note>

```bash theme={"theme":{"light":"github-light","dark":"github-dark-dimmed"}}
curl https://api.braintrust.dev/v1/function \
  -H "Content-Type: application/json" \
  -H "Authorization: Bearer $BRAINTRUST_API_KEY" \
  -d '{
    "project_name": "My Project",
    "slug": "summarizer",
    "input": {"text": "Long text to summarize..."},
    "stream": true
  }' \
  --no-buffer
```

The response streams SSE events until completion.

## Handle streaming errors

Catch errors during streaming:

<CodeGroup dropdown>
  ```typescript theme={"theme":{"light":"github-light","dark":"github-dark-dimmed"}}
  import { invoke } from "braintrust";

  try {
    const stream = await invoke({
      projectName: "My Project",
      slug: "summarizer",
      input: { text: "Long text..." },
      stream: true,
    });

    for await (const chunk of stream) {
      process.stdout.write(chunk);
    }
  } catch (error) {
    console.error("Streaming error:", error);
  }
  ```

  ```python theme={"theme":{"light":"github-light","dark":"github-dark-dimmed"}}
  from braintrust import invoke

  try:
      stream = invoke(
          project_name="My Project",
          slug="summarizer",
          input={"text": "Long text..."},
          stream=True,
      )

      for chunk in stream:
          print(chunk, end="")
  except Exception as error:
      print(f"Streaming error: {error}")
  ```
</CodeGroup>

## Next steps

* [Use the Braintrust gateway](/deploy/gateway) for unified streaming across providers
* [Deploy prompts](/deploy/prompts) that support streaming
* [Trace LLM calls](/instrument/trace-llm-calls) to automatically log streams
