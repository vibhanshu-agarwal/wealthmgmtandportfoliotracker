> ## Documentation Index
> Fetch the complete documentation index at: https://braintrust.dev/docs/llms.txt
> Use this file to discover all available pages before exploring further.

# Create scorers

> Create scorers to measure AI output quality

Scorers evaluate AI output quality by assigning scores between 0 and 1 based on criteria you define like factual accuracy, helpfulness, or correct formatting.

## Overview

Braintrust offers three types of scorers:

* [**Autoevals**](#score-with-autoevals) - Pre-built, battle-tested scorers for common evaluation tasks like factuality checking, semantic similarity, and format validation. Best for standard evaluation needs where reliable scorers already exist.
* [**LLM-as-a-judge**](#score-with-llms) - Use language models to evaluate outputs based on natural language criteria and instructions. Best for subjective judgments like tone, helpfulness, or creativity that are difficult to encode in deterministic code.
* [**Custom code**](#score-with-custom-code) - Write custom evaluation logic with full control over the scoring algorithm. Best for specific business rules, pattern matching, or calculations unique to your use case.

You can define scorers in three places:

* **Inline in SDK code** - Define scorers directly in your evaluation scripts using TypeScript, Python, or Ruby for local development, access to complex dependencies, or application-specific logic that's tightly coupled to your codebase.
* **Pushed via CLI** - Define TypeScript or Python scorers in code files and push them to Braintrust for version control in Git, team-wide sharing across projects, and automatic evaluation of production logs.
* **Created in UI** - Build TypeScript or Python scorers in the Braintrust web interface for non-technical users to create evaluations, rapid prototyping of scoring ideas, and simple LLM-as-a-judge scorers.

Most teams prototype in the UI, develop complex scorers inline, then push production-ready scorers to Braintrust for team-wide use.

## Score with autoevals

The `autoevals` library provides pre-built, battle-tested scorers for common evaluation tasks like factuality checking, semantic similarity, and format validation. Autoevals are open-source, deterministic (where possible), and optimized for speed and reliability. They can evaluate individual spans, but not entire traces.

Available scorers include:

* **Factuality**: Check if output contains factual information
* **Semantic**: Measure semantic similarity to expected output
* **Levenshtein**: Calculate edit distance from expected output
* **JSON**: Validate JSON structure and content
* **SQL**: Validate SQL query syntax and semantics

See the [autoevals library](https://github.com/braintrustdata/autoevals) for the complete list.

<Tabs className="tabs-border">
  <Tab title="SDK" icon="code">
    Use scorers inline in your evaluation code:

    <CodeGroup dropdown>
      ```typescript wrap theme={"theme":{"light":"github-light","dark":"github-dark-dimmed"}}
      import { Eval, initDataset } from "braintrust";
      import { Factuality } from "autoevals";

      Eval("My Project", {
        experimentName: "My experiment",
        data: initDataset("My Project", { dataset: "My Dataset" }),
        task: async (input) => {
          // Your LLM call here
          return await callModel(input);
        },
        scores: [Factuality],
        metadata: {
          model: "gpt-5-mini",
        },
      });
      ```

      ```python wrap theme={"theme":{"light":"github-light","dark":"github-dark-dimmed"}}
      from braintrust import Eval, init_dataset
      from autoevals import Factuality

      Eval(
          "My project",
          experiment_name="My experiment",
          data=init_dataset(project="My project", name="My dataset"),
          task=lambda input: call_model(input),  # Your LLM call here
          scores=[Factuality],
          metadata={
              "model": "gpt-5-mini",
          },
      )
      ```
    </CodeGroup>

    Autoevals automatically receive these parameters when used in evaluations:

    * `input`: The input to your task
    * `output`: The output from your task
    * `expected`: The expected output (optional)
    * `metadata`: Custom metadata from the test case
  </Tab>

  <Tab title="UI" icon="mouse-pointer-2">
    Create scorers in the Braintrust UI:

    * **Use in playgrounds**: When testing prompts in [playgrounds](/evaluate/playgrounds), add autoevals in the scoring section to evaluate results interactively.

    * **Use in experiments**: When creating [experiments](/evaluate/run-evaluations#run-in-ui), select autoevals from the scorer dropdown to measure output quality across your dataset.

    * **Use in online scoring**: Add autoevals to [online scoring rules](/evaluate/score-online) to automatically evaluate production logs.
  </Tab>
</Tabs>

## Score with LLMs

LLM-as-a-judge scorers use a language model to evaluate based on natural language criteria. They are best for subjective judgments like tone, helpfulness, or creativity that are difficult to encode in code. They can evaluate individual spans or entire traces.

<Tabs className="tabs-border">
  <Tab title="Score spans">
    Span-level scorers evaluate individual operations or outputs. Use them for measuring single LLM responses, checking specific tool calls, or validating individual outputs. Each matching span receives an independent score.

    Your prompt template can reference these variables:

    * `{{input}}`: The input to your task
    * `{{output}}`: The output from your task
    * `{{expected}}`: The expected output (optional)
    * `{{metadata}}`: Custom metadata from the test case

    <Tabs className="tabs-border">
      <Tab title="SDK" icon="code">
        Use scorers inline in your evaluation code:

        <CodeGroup dropdown>
          ```typescript llm_scorer.eval.ts theme={"theme":{"light":"github-light","dark":"github-dark-dimmed"}}
          import { Eval } from "braintrust";
          import { LLMClassifierFromTemplate } from "autoevals";
          import OpenAI from "openai";

          const client = new OpenAI();

          // Inline dataset: movie descriptions and expected titles
          const MOVIE_DATASET = [
            {
              input:
                "A detective investigates a series of murders based on the seven deadly sins.",
              expected: "Se7en",
            },
            {
              input:
                "A thief who steals corporate secrets through the use of dream-sharing technology is given the inverse task of planting an idea into the mind of a C.E.O.",
              expected: "Inception",
            },
          ];

          async function task(input: string): Promise<string> {
            const response = await client.responses.create({
              model: "gpt-5-mini",
              input: [
                {
                  role: "system",
                  content:
                    "Based on the following description, identify the movie. Reply with only the movie title.",
                },
                { role: "user", content: input },
              ],
            });
            return response.output_text ?? "";
          }

          // LLM-as-judge scorer using LLMClassifier template
          const correctnessScorer = LLMClassifierFromTemplate({
            name: "Correctness",
            promptTemplate: `You are evaluating a movie-identification task.

          Output (model's answer): {{output}}
          Expected (correct movie): {{expected}}

          Does the output correctly identify the same movie as the expected answer?
          Consider alternate titles (e.g. "Harry Potter 1" vs "Harry Potter and the Sorcerer's Stone") as correct.

          Return only "correct" if the output is the right movie (exact or equivalent title).
          Return only "incorrect" otherwise.`,
            choiceScores: {
              correct: 1,
              incorrect: 0,
            },
            useCoT: true,
          });

          Eval("Movie Matcher", {
            data: MOVIE_DATASET,
            task,
            scores: [correctnessScorer],
          });
          ```

          ```python eval_llm_scorer.py theme={"theme":{"light":"github-light","dark":"github-dark-dimmed"}}
          from braintrust import Eval
          from autoevals import LLMClassifier
          from openai import OpenAI

          client = OpenAI()

          # Inline dataset: movie descriptions and expected titles
          MOVIE_DATASET = [
              {
                  "input": "A detective investigates a series of murders based on the seven deadly sins.",
                  "expected": "Se7en",
              },
              {
                  "input": "A thief who steals corporate secrets through the use of dream-sharing technology is given the inverse task of planting an idea into the mind of a C.E.O.",
                  "expected": "Inception",
              },
          ]


          def task(input):
              response = client.responses.create(
                  model="gpt-5-mini",
                  input=[
                      {
                          "role": "system",
                          "content": "Based on the following description, identify the movie. Reply with only the movie title.",
                      },
                      {"role": "user", "content": input},
                  ],
              )
              return response.output_text


          # LLM-as-judge scorer using LLMClassifier
          correctness_scorer = LLMClassifier(
              name="Correctness",
              prompt_template="""You are evaluating a movie-identification task.

          Output (model's answer): {{output}}
          Expected (correct movie): {{expected}}

          Does the output correctly identify the same movie as the expected answer?
          Consider alternate titles (e.g. "Harry Potter 1" vs "Harry Potter and the Sorcerer's Stone") as correct.

          Return only "correct" if the output is the right movie (exact or equivalent title).
          Return only "incorrect" otherwise.""",
              choice_scores={
                  "correct": 1,
                  "incorrect": 0,
              },
              model="gpt-5-mini",
          )

          Eval(
              "Movie Matcher",
              data=MOVIE_DATASET,
              task=task,
              scores=[correctness_scorer],
          )
          ```

          ```ruby eval_llm_scorer.rb theme={"theme":{"light":"github-light","dark":"github-dark-dimmed"}}
          require "braintrust"
          require "openai"

          Braintrust.init

          client = OpenAI::Client.new(api_key: ENV.fetch("OPENAI_API_KEY", nil))
          judge_client = OpenAI::Client.new(api_key: ENV.fetch("OPENAI_API_KEY", nil))

          MOVIE_DATASET = [
            {
              input: "A detective investigates a series of murders based on the seven deadly sins.",
              expected: "Se7en",
            },
            {
              input: "A thief who steals corporate secrets through the use of dream-sharing technology is given the inverse task of planting an idea into the mind of a C.E.O.",
              expected: "Inception",
            },
          ]

          # LLM-as-judge scorer: evaluates movie identification correctness
          correctness_scorer = Braintrust::Scorer.new("correctness") do |output:, expected:|
            response = judge_client.chat.completions.create(
              model: "gpt-5-mini",
              messages: [{
                role: "user",
                content: <<~PROMPT
                  You are evaluating a movie-identification task.

                  Output (model's answer): #{output}
                  Expected (correct movie): #{expected}

                  Does the output correctly identify the same movie as the expected answer?
                  Consider alternate titles (e.g. "Harry Potter 1" vs "Harry Potter and the Sorcerer's Stone") as correct.

                  Return only "correct" if the output is the right movie (exact or equivalent title).
                  Return only "incorrect" otherwise.
                PROMPT
              }]
            )

            verdict = response.choices.first.message.content.to_s.strip.downcase
            {name: "Correctness", score: verdict == "correct" ? 1.0 : 0.0}
          end

          Braintrust::Eval.run(
            project: "Movie Matcher",
            cases: MOVIE_DATASET,
            task: lambda do |input:|
              response = client.chat.completions.create(
                model: "gpt-5-mini",
                messages: [
                  {role: "system", content: "Based on the following description, identify the movie. Reply with only the movie title."},
                  {role: "user", content: input}
                ]
              )
              response.choices.first.message.content || ""
            end,
            scorers: [correctness_scorer]
          )

          OpenTelemetry.tracer_provider.shutdown
          ```

          ```csharp llm_scorer.cs theme={"theme":{"light":"github-light","dark":"github-dark-dimmed"}}
          using System.Text.Json;
          using Braintrust.Sdk;
          using Braintrust.Sdk.Eval;
          using Braintrust.Sdk.OpenAI;
          using OpenAI;
          using OpenAI.Chat;

          // LLM-as-a-judge scorer: calls an LLM to evaluate output correctness.
          // The C# SDK has no autoevals equivalent, so implement IScorer directly.
          sealed class CorrectnessScorer(string openAIApiKey) : IScorer<string, string>
          {
              public string Name => "Correctness";

              private readonly ChatClient _chatClient =
                  new OpenAIClient(openAIApiKey).GetChatClient("gpt-5-mini");

              public async Task<IReadOnlyList<Score>> Score(TaskResult<string, string> taskResult)
              {
                  var prompt = $$"""
                      You are evaluating a movie-identification task.

                      Output (model's answer): {{taskResult.Result}}
                      Expected (correct movie): {{taskResult.DatasetCase.Expected}}

                      Does the output correctly identify the same movie as the expected answer?
                      Consider alternate titles (e.g. "Harry Potter 1" vs "Harry Potter and the Sorcerer's Stone") as correct.

                      Reply with JSON only: {"score": 1, "reasoning": "..."} for correct, or {"score": 0, "reasoning": "..."} for incorrect.
                      """;

                  var completion = await _chatClient.CompleteChatAsync([new UserChatMessage(prompt)]);
                  using var json = JsonDocument.Parse(completion.Value.Content[0].Text);
                  var score = json.RootElement.GetProperty("score").GetDouble();
                  var reasoning = json.RootElement.GetProperty("reasoning").GetString() ?? "";

                  return [new Score(Name, score, new Dictionary<string, object> { { "reasoning", reasoning } })];
              }
          }

          class Program
          {
              static readonly DatasetCase<string, string>[] MovieDataset =
              [
                  DatasetCase.Of("A detective investigates a series of murders based on the seven deadly sins.", "Se7en"),
                  DatasetCase.Of("A thief plants an idea into a CEO's mind through dream-sharing technology.", "Inception"),
              ];

              static async Task Main(string[] args)
              {
                  var openAIApiKey = Environment.GetEnvironmentVariable("OPENAI_API_KEY")!;
                  var braintrust = Braintrust.Sdk.Braintrust.Get();
                  var activitySource = braintrust.GetActivitySource();
                  var openAIClient = BraintrustOpenAI.WrapOpenAI(activitySource, openAIApiKey);

                  async Task<string> MovieTask(string input)
                  {
                      var response = await openAIClient.GetChatClient("gpt-5-mini").CompleteChatAsync(
                          new SystemChatMessage("Identify the movie from the description. Reply with the title only."),
                          new UserChatMessage(input));
                      return response.Value.Content[0].Text;
                  }

                  var eval = await braintrust
                      .EvalBuilder<string, string>()
                      .Name("Movie Matcher")
                      .Cases(MovieDataset)
                      .TaskFunction(MovieTask)
                      .Scorers(new CorrectnessScorer(openAIApiKey))
                      .BuildAsync();

                  var result = await eval.RunAsync();
                  Console.WriteLine(result.CreateReportString());
              }
          }
          ```
        </CodeGroup>
      </Tab>

      <Tab title="CLI" icon="terminal">
        Define TypeScript or Python scorers in code and push to Braintrust:

        <CodeGroup dropdown>
          ```typescript title="llm_scorer.ts" theme={"theme":{"light":"github-light","dark":"github-dark-dimmed"}}
          import braintrust from "braintrust";

          const project = braintrust.projects.create({ name: "my-project" });

          project.scorers.create({
            name: "Helpfulness scorer",
            slug: "helpfulness-scorer",
            description: "Evaluate helpfulness of response",
            messages: [
              {
                role: "user",
                content:
                  'Rate the helpfulness of this response: {{output}}\n\nReturn "A" for very helpful, "B" for somewhat helpful, "C" for not helpful.',
              },
            ],
            model: "gpt-5-mini",
            useCot: true,
            choiceScores: {
              A: 1,
              B: 0.5,
              C: 0,
            },
            metadata: {
              __pass_threshold: 0.7,
            },
          });
          ```

          ```python title="llm_scorer.py" theme={"theme":{"light":"github-light","dark":"github-dark-dimmed"}}
          import braintrust

          project = braintrust.projects.create(name="My project")

          project.scorers.create(
              name="Helpfulness scorer",
              slug="helpfulness-scorer",
              description="Evaluate helpfulness of response",
              messages=[
                  {
                      "role": "user",
                      "content": 'Rate the helpfulness of this response: {{output}}\n\nReturn "A" for very helpful, "B" for somewhat helpful, "C" for not helpful.',
                  }
              ],
              model="gpt-5-mini",
              use_cot=True,
              choice_scores={
                  "A": 1,
                  "B": 0.5,
                  "C": 0,
              },
              metadata={"__pass_threshold": 0.7},
          )
          ```
        </CodeGroup>

        Push to Braintrust using the [`bt` CLI](/reference/cli/quickstart):

        <CodeGroup>
          ```bash TypeScript theme={"theme":{"light":"github-light","dark":"github-dark-dimmed"}}
          bt functions push llm_scorer.ts
          ```

          ```bash Python theme={"theme":{"light":"github-light","dark":"github-dark-dimmed"}}
          bt functions push llm_scorer.py
          ```
        </CodeGroup>
      </Tab>

      <Tab title="UI" icon="mouse-pointer-2">
        Create scorers in the Braintrust UI:

        1. Go to <Icon icon="triangle" /> **Scorers** > **+ Scorer**.
        2. Enter a scorer name and slug.
        3. Select **LLM-as-a-judge**.
        4. Keep **Scope** set to **Span** (default) to evaluate individual spans.
        5. Configure:
           * **Prompt**: Instructions for evaluating the output
           * **Model**: Which model to use as judge
           * **Choice scores**: Map model choices (A, B, C) to numeric scores
           * **Use CoT**: Enable chain-of-thought reasoning for complex evaluations
        6. Click **Save as custom scorer**.
      </Tab>
    </Tabs>
  </Tab>

  <Tab title="Score traces">
    Trace-level scorers evaluate entire execution traces including all spans and conversation history. Use these for assessing multi-turn conversation quality, overall workflow completion, or when your scorer needs access to the full execution context. The scorer runs once per trace.

    Your prompt template can reference the `{{thread}}` template variable, which provides the full conversation formatted as human-readable text. `input`, `output`, `expected`, and `metadata` are automatically populated from the root span of the trace.

    <Note>
      Trace-level scoring requires TypeScript v2.2.1+, Python SDK v0.5.6+, or Ruby SDK v0.2.1+.
    </Note>

    <Tabs className="tabs-border">
      <Tab title="SDK" icon="code">
        Use scorers inline in your evaluation code:

        <CodeGroup dropdown>
          ```typescript trace_llm_scorer.eval.ts theme={"theme":{"light":"github-light","dark":"github-dark-dimmed"}}
          import { Eval, wrapOpenAI, wrapTraced, type Scorer } from "braintrust";
          import OpenAI from "openai";

          const client = new OpenAI();
          const wrappedClient = wrapOpenAI(new OpenAI());

          // Customer support dataset
          const SUPPORT_DATASET = [
            { input: "My order hasn't arrived yet. Order #12345." },
            { input: "I need help resetting my password." },
          ];

          // Helper function to call the LLM (creates an LLM span)
          const callLLM = wrapTraced(async function callLLM(messages: Array<{ role: string; content: string }>) {
            const response = await wrappedClient.chat.completions.create({
              model: "gpt-5-mini",
              messages,
            });
            return response.choices[0].message.content || "";
          });

          // Multi-turn customer support conversation
          async function supportTask(input: string): Promise<string> {
            const messages: Array<{ role: string; content: string }> = [
              { role: "system", content: "You are a helpful customer support agent." }
            ];

            // Turn 1: Customer's initial question
            messages.push({ role: "user", content: input });
            const response1 = await callLLM(messages);
            messages.push({ role: "assistant", content: response1 });

            // Turn 2: Customer asks for clarification
            messages.push({ role: "user", content: "Can you provide more details?" });
            const response2 = await callLLM(messages);
            messages.push({ role: "assistant", content: response2 });

            // Turn 3: Customer thanks the agent
            messages.push({ role: "user", content: "Thank you for your help!" });
            const response3 = await callLLM(messages);

            return response3;
          }

          // LLM-as-judge scorer: evaluates conversation coherence using {{thread}}
          const conversationCoherence: Scorer = async ({ trace }) => {
            if (!trace) return null;

            // Get the conversation thread (this is what {{thread}} provides)
            const thread = await trace.getThread();
            const threadText = thread
              .map(msg => `${msg.role}: ${msg.content}`)
              .join("\n\n");

            const response = await client.responses.create({
              model: "gpt-5-mini",
              input: [
                {
                  role: "user",
                  content: `Evaluate the coherence of this customer support conversation:

          ${threadText}

          Rate the conversation coherence:
          - "A" for highly coherent with natural flow and consistent context
          - "B" for mostly coherent with minor gaps or context issues
          - "C" for incoherent, disjointed, or lost context

          Return only the letter (A, B, or C).`,
                },
              ],
            });

            const rating = response.output_text?.trim().toUpperCase() || "C";
            const choiceScores = { A: 1, B: 0.6, C: 0 };
            const score = choiceScores[rating as keyof typeof choiceScores] ?? 0;

            return {
              name: "Conversation coherence",
              score,
              metadata: { rating, thread_length: thread.length },
            };
          };

          Eval("Support Conversation Quality", {
            data: SUPPORT_DATASET,
            task: supportTask,
            scores: [conversationCoherence],
          });
          ```

          ```python eval_trace_llm_scorer.py theme={"theme":{"light":"github-light","dark":"github-dark-dimmed"}}
          from braintrust import Eval, wrap_openai, traced
          from openai import AsyncOpenAI, OpenAI

          client = OpenAI()
          wrapped_client = wrap_openai(AsyncOpenAI())

          # Customer support dataset
          SUPPORT_DATASET = [
              {"input": "My order hasn't arrived yet. Order #12345."},
              {"input": "I need help resetting my password."},
          ]


          # Helper function to call the LLM (creates an LLM span)
          @traced
          async def call_llm(messages):
              response = await wrapped_client.chat.completions.create(
                  model="gpt-5-mini",
                  messages=messages,
              )
              return response.choices[0].message.content or ""


          # Multi-turn customer support conversation
          async def support_task(input):
              messages = [
                  {"role": "system", "content": "You are a helpful customer support agent."}
              ]

              # Turn 1: Customer's initial question
              messages.append({"role": "user", "content": input})
              response1 = await call_llm(messages)
              messages.append({"role": "assistant", "content": response1})

              # Turn 2: Customer asks for clarification
              messages.append({"role": "user", "content": "Can you provide more details?"})
              response2 = await call_llm(messages)
              messages.append({"role": "assistant", "content": response2})

              # Turn 3: Customer thanks the agent
              messages.append({"role": "user", "content": "Thank you for your help!"})
              response3 = await call_llm(messages)

              return response3


          # LLM-as-judge scorer: evaluates conversation coherence using {{thread}}
          async def conversation_coherence(input, output, expected, trace=None):
              if not trace:
                  return None

              # Get the conversation thread (this is what {{thread}} provides)
              thread = await trace.get_thread()
              thread_text = "\n\n".join([f"{msg['role']}: {msg['content']}" for msg in thread])

              response = client.responses.create(
                  model="gpt-5-mini",
                  input=[
                      {
                          "role": "user",
                          "content": f"""Evaluate the coherence of this customer support conversation:

          {thread_text}

          Rate the conversation coherence:
          - "A" for highly coherent with natural flow and consistent context
          - "B" for mostly coherent with minor gaps or context issues
          - "C" for incoherent, disjointed, or lost context

          Return only the letter (A, B, or C).""",
                      }
                  ],
              )

              rating = (response.output_text or "C").strip().upper()
              choice_scores = {"A": 1, "B": 0.6, "C": 0}
              score = choice_scores.get(rating, 0)

              return {
                  "name": "Conversation coherence",
                  "score": score,
                  "metadata": {"rating": rating, "thread_length": len(thread)},
              }


          Eval(
              "Support Conversation Quality",
              data=SUPPORT_DATASET,
              task=support_task,
              scores=[conversation_coherence],
          )
          ```

          ```ruby eval_trace_llm_scorer.rb theme={"theme":{"light":"github-light","dark":"github-dark-dimmed"}}
          require "braintrust"
          require "openai"

          Braintrust.init

          client = OpenAI::Client.new(api_key: ENV.fetch("OPENAI_API_KEY", nil))
          judge_client = OpenAI::Client.new(api_key: ENV.fetch("OPENAI_API_KEY", nil))

          SUPPORT_DATASET = [
            {input: "My order hasn't arrived yet. Order #12345."},
            {input: "I need help resetting my password."},
          ]

          def chat(client, messages)
            client.chat.completions.create(model: "gpt-5-mini", messages: messages)
              .choices.first.message.content || ""
          end

          # Multi-turn customer support task (each LLM call creates a span)
          support_task = Braintrust::Task.new("support") do |input:|
            messages = [{role: "system", content: "You are a helpful customer support agent."}]

            messages << {role: "user", content: input}
            messages << {role: "assistant", content: chat(client, messages)}

            messages << {role: "user", content: "Can you provide more details?"}
            messages << {role: "assistant", content: chat(client, messages)}

            messages << {role: "user", content: "Thank you for your help!"}
            chat(client, messages)
          end

          # LLM-as-judge scorer: evaluates conversation coherence using the thread
          conversation_coherence = Braintrust::Scorer.new("conversation_coherence") do |trace:|
            next nil unless trace

            thread = trace.thread
            thread_text = thread.map { |msg| "#{msg["role"]}: #{msg["content"]}" }.join("\n\n")

            response = judge_client.chat.completions.create(
              model: "gpt-5-mini",
              messages: [{
                role: "user",
                content: <<~PROMPT
                  Evaluate the coherence of this customer support conversation:

                  #{thread_text}

                  Rate the conversation coherence:
                  - "A" for highly coherent with natural flow and consistent context
                  - "B" for mostly coherent with minor gaps or context issues
                  - "C" for incoherent, disjointed, or lost context

                  Return only the letter (A, B, or C).
                PROMPT
              }]
            )

            rating = (response.choices.first.message.content || "C").strip.upcase
            scores = {"A" => 1.0, "B" => 0.6, "C" => 0.0}

            {
              name: "Conversation coherence",
              score: scores.fetch(rating, 0.0),
              metadata: {rating: rating, thread_length: thread.length}
            }
          end

          Braintrust::Eval.run(
            project: "Support Conversation Quality",
            cases: SUPPORT_DATASET,
            task: support_task,
            scorers: [conversation_coherence]
          )

          OpenTelemetry.tracer_provider.shutdown
          ```
        </CodeGroup>
      </Tab>

      <Tab title="CLI" icon="terminal">
        Define TypeScript or Python scorers in code and push to Braintrust:

        <CodeGroup dropdown>
          ```typescript title="trace_llm_scorer.ts" theme={"theme":{"light":"github-light","dark":"github-dark-dimmed"}}
          import braintrust from "braintrust";
          import { z } from "zod";

          const project = braintrust.projects.create({ name: "my-project" });

          project.scorers.create({
            name: "Conversation coherence",
            slug: "conversation-coherence",
            description: "Evaluate multi-turn conversation coherence",
            parameters: z.object({
              trace: z.any(),
            }),
            messages: [
              {
                role: "user",
                content: `Evaluate the coherence of this conversation:

          {{thread}}

          Rate the coherence:
          - "A" for highly coherent with natural flow
          - "B" for mostly coherent with minor gaps
          - "C" for incoherent or disjointed`,
              },
            ],
            model: "gpt-5-mini",
            useCot: true,
            choiceScores: {
              A: 1,
              B: 0.6,
              C: 0,
            },
          });
          ```

          ```python title="trace_llm_scorer.py" theme={"theme":{"light":"github-light","dark":"github-dark-dimmed"}}
          import braintrust
          from pydantic import BaseModel

          project = braintrust.projects.create(name="my-project")

          class TraceParams(BaseModel):
              trace: dict

          project.scorers.create(
              name="Conversation coherence",
              slug="conversation-coherence",
              description="Evaluate multi-turn conversation coherence",
              parameters=TraceParams,
              messages=[
                  {
                      "role": "user",
                      "content": """Evaluate the coherence of this conversation:

          {{thread}}

          Rate the coherence:
          - "A" for highly coherent with natural flow
          - "B" for mostly coherent with minor gaps
          - "C" for incoherent or disjointed""",
                  }
              ],
              model="gpt-5-mini",
              use_cot=True,
              choice_scores={
                  "A": 1,
                  "B": 0.6,
                  "C": 0,
              },
          )
          ```
        </CodeGroup>

        Push to Braintrust:

        <CodeGroup>
          ```bash TypeScript theme={"theme":{"light":"github-light","dark":"github-dark-dimmed"}}
          bt functions push trace_llm_scorer.ts
          ```

          ```bash Python theme={"theme":{"light":"github-light","dark":"github-dark-dimmed"}}
          bt functions push trace_llm_scorer.py
          ```
        </CodeGroup>
      </Tab>

      <Tab title="UI" icon="mouse-pointer-2">
        Create trace-level scorers in the Braintrust UI:

        1. Go to <Icon icon="triangle" /> **Scorers** > **+ Scorer**.
        2. Enter a scorer name and slug.
        3. Select **LLM-as-a-judge**.
        4. Set **Scope** to **Trace** to evaluate entire traces.
        5. Configure:
           * **Prompt**: Use the `{{thread}}` variable to reference the conversation thread. Instructions for evaluating the trace.
           * **Model**: Which model to use as judge
           * **Choice scores**: Map model choices (A, B, C) to numeric scores
           * **Use CoT**: Enable chain-of-thought reasoning for complex evaluations
        6. Click **Save as custom scorer**.
      </Tab>
    </Tabs>
  </Tab>
</Tabs>

## Score with custom code

Write custom evaluation logic in TypeScript, Python, or Ruby. Custom code scorers give you full control over the evaluation logic and can use any packages you need. They are best when you have specific rules, patterns, or calculations to implement. Custom code scorers can evaluate individual spans or entire traces.

<Tabs className="tabs-border">
  <Tab title="Score spans">
    Span-level scorers evaluate individual operations or outputs. Use them for measuring single LLM responses, checking specific tool calls, or validating individual outputs. Each matching span receives an independent score.

    Your scorer function receives these parameters:

    * `input`: The input to your task
    * `output`: The output from your task
    * `expected`: The expected output (optional)
    * `metadata`: Custom metadata from the test case

    Return a number between 0 and 1, or an object with `score` and optional metadata.

    In Ruby, declare only the parameters you need as keyword arguments — the runner automatically filters out the rest: `|output:, expected:|`.

    <Tabs className="tabs-border">
      <Tab title="SDK" icon="code">
        Use scorers inline in your evaluation code:

        <CodeGroup dropdown>
          ```typescript equality_scorer.eval.ts theme={"theme":{"light":"github-light","dark":"github-dark-dimmed"}}
          import { Eval, type Scorer } from "braintrust";
          import OpenAI from "openai";

          const client = new OpenAI();

          // Inline dataset
          const DATASET = [
            {
              input: "What is 2+2?",
              expected: "4",
            },
            {
              input: "What is the capital of France?",
              expected: "Paris",
            },
          ];

          async function task(input: string): Promise<string> {
            const response = await client.responses.create({
              model: "gpt-5-mini",
              input: [
                { role: "user", content: input },
              ],
            });
            return response.output_text ?? "";
          }

          // Custom code scorer: checks exact match
          const equalityScorer: Scorer = ({ output, expected }) => {
            if (!expected) return null;
            const matches = output === expected;
            return {
              name: "Equality",
              score: matches ? 1 : 0,
              metadata: { exact_match: matches },
            };
          };

          // Custom code scorer: checks if output contains expected substring
          const containsScorer: Scorer = ({ output, expected }) => {
            if (!expected) return null;
            const contains = output.toLowerCase().includes(expected.toLowerCase());
            return {
              name: "Contains expected",
              score: contains ? 1 : 0,
            };
          };

          Eval("Custom Code Scorer Example", {
            data: DATASET,
            task,
            scores: [equalityScorer, containsScorer],
          });
          ```

          ```python eval_custom_scorer.py theme={"theme":{"light":"github-light","dark":"github-dark-dimmed"}}
          from braintrust import Eval
          from openai import OpenAI

          client = OpenAI()

          # Inline dataset
          DATASET = [
              {
                  "input": "What is 2+2?",
                  "expected": "4",
              },
              {
                  "input": "What is the capital of France?",
                  "expected": "Paris",
              },
          ]


          def task(input):
              response = client.responses.create(
                  model="gpt-5-mini",
                  input=[
                      {"role": "user", "content": input},
                  ],
              )
              return response.output_text


          # Custom code scorer: checks exact match
          def equality_scorer(input, output, expected, metadata):
              if not expected:
                  return None
              matches = output == expected
              return {
                  "name": "Equality",
                  "score": 1 if matches else 0,
                  "metadata": {"exact_match": matches},
              }


          # Custom code scorer: checks if output contains expected substring
          def contains_scorer(input, output, expected, metadata):
              if not expected:
                  return None
              contains = expected.lower() in output.lower()
              return {
                  "name": "Contains expected",
                  "score": 1 if contains else 0,
              }


          Eval(
              "Custom Code Scorer Example",
              data=DATASET,
              task=task,
              scores=[equality_scorer, contains_scorer],
          )
          ```

          ```ruby eval_custom_scorer.rb theme={"theme":{"light":"github-light","dark":"github-dark-dimmed"}}
          require "braintrust"
          require "openai"

          Braintrust.init

          client = OpenAI::Client.new(api_key: ENV.fetch("OPENAI_API_KEY", nil))

          DATASET = [
            {input: "What is 2+2?", expected: "4"},
            {input: "What is the capital of France?", expected: "Paris"},
          ]

          # Custom code scorer: checks exact match
          equality_scorer = Braintrust::Scorer.new("equality") do |output:, expected:|
            next nil unless expected
            matches = output == expected
            {name: "Equality", score: matches ? 1.0 : 0.0, metadata: {exact_match: matches}}
          end

          # Custom code scorer: checks if output contains expected substring
          contains_scorer = Braintrust::Scorer.new("contains_expected") do |output:, expected:|
            next nil unless expected
            contains = output.downcase.include?(expected.downcase)
            {name: "Contains expected", score: contains ? 1.0 : 0.0}
          end

          Braintrust::Eval.run(
            project: "Custom Code Scorer Example",
            cases: DATASET,
            task: lambda do |input:|
              response = client.chat.completions.create(
                model: "gpt-5-mini",
                messages: [{role: "user", content: input}]
              )
              response.choices.first.message.content || ""
            end,
            scorers: [equality_scorer, contains_scorer]
          )

          OpenTelemetry.tracer_provider.shutdown
          ```

          ```csharp eval_custom_scorer.cs theme={"theme":{"light":"github-light","dark":"github-dark-dimmed"}}
          using Braintrust.Sdk;
          using Braintrust.Sdk.Eval;
          using Braintrust.Sdk.OpenAI;
          using OpenAI;
          using OpenAI.Chat;

          // Class-based scorer: implement IScorer for full control, including metadata
          sealed class ContainsScorer : IScorer<string, string>
          {
              public string Name => "Contains expected";

              public Task<IReadOnlyList<Score>> Score(TaskResult<string, string> taskResult)
              {
                  if (taskResult.DatasetCase.Expected is null)
                      return Task.FromResult<IReadOnlyList<Score>>([]);

                  var contains = taskResult.Result.Contains(
                      taskResult.DatasetCase.Expected, StringComparison.OrdinalIgnoreCase);
                  return Task.FromResult<IReadOnlyList<Score>>(
                      [new Score(Name, contains ? 1.0 : 0.0)]);
              }
          }

          class Program
          {
              static readonly DatasetCase<string, string>[] Dataset =
              [
                  DatasetCase.Of("What is 2+2?", "4"),
                  DatasetCase.Of("What is the capital of France?", "Paris"),
              ];

              static async Task Main(string[] args)
              {
                  // FunctionScorer: quick lambda-based scorer (analogous to a function scorer in TypeScript/Python)
                  var equalityScorer = new FunctionScorer<string, string>(
                      "Equality",
                      (expected, actual) => actual == expected ? 1.0 : 0.0);

                  var braintrust = Braintrust.Sdk.Braintrust.Get();
                  var activitySource = braintrust.GetActivitySource();
                  var openAIClient = BraintrustOpenAI.WrapOpenAI(
                      activitySource, Environment.GetEnvironmentVariable("OPENAI_API_KEY")!);

                  async Task<string> Task(string input)
                  {
                      var response = await openAIClient.GetChatClient("gpt-5-mini")
                          .CompleteChatAsync([new UserChatMessage(input)]);
                      return response.Value.Content[0].Text;
                  }

                  var eval = await braintrust
                      .EvalBuilder<string, string>()
                      .Name("Custom Code Scorer Example")
                      .Cases(Dataset)
                      .TaskFunction(Task)
                      .Scorers(equalityScorer, new ContainsScorer())
                      .BuildAsync();

                  var result = await eval.RunAsync();
                  Console.WriteLine(result.CreateReportString());
              }
          }
          ```
        </CodeGroup>
      </Tab>

      <Tab title="CLI" icon="terminal">
        Define TypeScript or Python scorers in code and push to Braintrust:

        <CodeGroup dropdown>
          ```typescript title="code_scorer.ts" theme={"theme":{"light":"github-light","dark":"github-dark-dimmed"}}
          import braintrust from "braintrust";
          import { z } from "zod";

          const project = braintrust.projects.create({ name: "my-project" });

          project.scorers.create({
            name: "Equality scorer",
            slug: "equality-scorer",
            description: "Check if output equals expected",
            parameters: z.object({
              output: z.string(),
              expected: z.string(),
            }),
            handler: async ({ output, expected }) => {
              const matches = output === expected;
              return {
                score: matches ? 1 : 0,
                metadata: { exact_match: matches },
              };
            },
            metadata: {
              __pass_threshold: 0.5,
            },
          });
          ```

          ```python title="code_scorer.py" theme={"theme":{"light":"github-light","dark":"github-dark-dimmed"}}
          import braintrust
          from pydantic import BaseModel

          project = braintrust.projects.create(name="Tracing quickstart")

          class EqualityParams(BaseModel):
              output: str
              expected: str

          def equality_scorer(output: str, expected: str):
              matches = output == expected
              return {
                  "score": 1 if matches else 0,
                  "metadata": {"exact_match": matches},
              }

          project.scorers.create(
              name="Equality scorer",
              slug="equality-scorer",
              description="Check if output equals expected",
              parameters=EqualityParams,
              handler=equality_scorer,
              metadata={"__pass_threshold": 0.5},
          )
          ```
        </CodeGroup>

        Push to Braintrust:

        <CodeGroup>
          ```bash TypeScript theme={"theme":{"light":"github-light","dark":"github-dark-dimmed"}}
          bt functions push code_scorer.ts
          ```

          ```bash Python theme={"theme":{"light":"github-light","dark":"github-dark-dimmed"}}
          bt functions push code_scorer.py
          ```
        </CodeGroup>

        <Note>
          **Important notes for Python scorers:**

          * Scorers must be pushed from within their directory (e.g., `bt functions push scorer.py`); pushing with relative paths (e.g., `bt functions push path/to/scorer.py`) is unsupported and will cause import errors.
          * Scorers using local imports must be defined at the project root.
          * The maximum supported Python version for scorers created with the Braintrust CLI is `3.13`.
          * Braintrust uses uv to cross-bundle dependencies to Linux. This works for binary dependencies except libraries requiring on-demand compilation.
        </Note>

        <Accordion title="TypeScript bundling">
          In TypeScript, Braintrust uses `esbuild` to bundle your code and dependencies. This works for most dependencies but does not support native (compiled) libraries like SQLite.

          If you have trouble bundling dependencies, [file an issue in the braintrust-sdk repo](https://github.com/braintrustdata/braintrust-sdk/issues).
        </Accordion>

        <Accordion title="Python external dependencies">
          Python scorers created via the CLI have these default packages:

          * `autoevals`
          * `braintrust`
          * `openai`
          * `pydantic`
          * `requests`

          For additional packages, use the `--requirements` flag.

          For scorers with external dependencies:

          ```python title="scorer-with-deps.py" theme={"theme":{"light":"github-light","dark":"github-dark-dimmed"}}
          import braintrust
          from langdetect import detect  # External package
          from pydantic import BaseModel

          project = braintrust.projects.create(name="my-project")

          class LanguageMatchParams(BaseModel):
              output: str
              expected: str

          @project.scorers.create(
              name="Language match",
              slug="language-match",
              description="Check if output and expected are same language",
              parameters=LanguageMatchParams,
              metadata={"__pass_threshold": 0.5},
          )
          def language_match_scorer(output: str, expected: str):
              return 1.0 if detect(output) == detect(expected) else 0.0
          ```

          Create requirements file:

          ```
          langdetect==1.0.9
          ```

          Push with requirements:

          ```bash theme={"theme":{"light":"github-light","dark":"github-dark-dimmed"}}
          bt functions push scorer-with-deps.py --requirements requirements.txt
          ```
        </Accordion>
      </Tab>

      <Tab title="UI" icon="mouse-pointer-2">
        Create scorers in the Braintrust UI:

        1. Go to <Icon icon="triangle" /> **Scorers** > **+ Scorer**.
        2. Enter a scorer name and slug.
        3. Select **TypeScript** or **Python**.
        4. Write your scorer function. The code editor provides real-time linting and autocomplete to help you write correct code faster.
        5. Click **Save as custom scorer**.

        <CodeGroup dropdown>
          ```typescript theme={"theme":{"light":"github-light","dark":"github-dark-dimmed"}}
          // Enter handler function that returns a numeric score between 0 and 1,
          // or null to skip scoring
          function handler({
            input,
            output,
            expected,
            metadata,
          }: {
            input: any;
            output: any;
            expected: any;
            metadata: Record<string, any>;
          }): number | null {
            if (expected === null) return null;
            return output === expected ? 1 : 0;
          }
          ```

          ```python theme={"theme":{"light":"github-light","dark":"github-dark-dimmed"}}
          from typing import Any
          # Enter handler function that returns a numeric score between 0 and 1,
          # or None to skip scoring
          def handler(
            input: Any,
            output: Any,
            expected: Any,
            metadata: dict[str, Any]
          ) -> float | None:
            if expected is None:
              return None
            return 1.0 if output == expected else 0.0
          ```
        </CodeGroup>

        <Note>
          UI scorers have access to these packages:

          * `anthropic`
          * `autoevals`
          * `braintrust`
          * `json`
          * `math`
          * `openai`
          * `re`
          * `requests`
          * `typing`

          For additional packages, use the SDK tab.
        </Note>
      </Tab>
    </Tabs>
  </Tab>

  <Tab title="Score traces">
    Trace-level scorers evaluate entire execution traces including all spans and conversation history. Use these for assessing multi-turn conversation quality, overall workflow completion, or when your scorer needs access to the full execution context. The scorer runs once per trace.

    Your handler function receives the `trace` parameter, which provides methods for accessing execution data:

    * **Get spans**: Returns spans matching the filter. Each span includes `input`, `output`, `metadata`, `span_id`, and `span_attributes`. Omit the filter to get all spans, or pass multiple types like `["llm", "tool"]`.
      * TypeScript: `trace.getSpans({ spanType: ["llm"] })`
      * Python: `trace.get_spans(span_type=["llm"])`
      * Ruby: `trace.spans(span_type: "llm")`

    * **Get thread**: Returns an array of conversation messages extracted from LLM spans. Use for evaluating conversation quality and multi-turn interactions.
      * TypeScript: `trace.getThread()`
      * Python: `trace.get_thread()`
      * Ruby: `trace.thread`

    When using trace-level scorers, `input`, `output`, `expected`, and `metadata` are automatically populated from the root span of the trace and passed to your scorer function, allowing you to evaluate both the trace structure and the root span's data without additional queries.

    <Note>
      Trace-level scoring requires TypeScript v2.2.1+, Python SDK v0.5.6+, or Ruby SDK v0.2.1+.
    </Note>

    <Tabs className="tabs-border">
      <Tab title="SDK" icon="code">
        Use scorers inline in your evaluation code:

        <CodeGroup dropdown>
          ```typescript trace_code_scorer.eval.ts theme={"theme":{"light":"github-light","dark":"github-dark-dimmed"}}
          import { Eval, wrapOpenAI, wrapTraced, type Scorer } from "braintrust";
          import OpenAI from "openai";

          const client = wrapOpenAI(new OpenAI());

          // Customer support dataset
          const SUPPORT_DATASET = [
            { input: "My order hasn't arrived yet. Order #12345." },
            { input: "I need help resetting my password." },
          ];

          // Helper function to call the LLM (creates an LLM span)
          const callLLM = wrapTraced(async function callLLM(messages: Array<{ role: string; content: string }>) {
            const response = await client.chat.completions.create({
              model: "gpt-5-mini",
              messages,
            });
            return response.choices[0].message.content || "";
          });

          // Multi-turn customer support conversation
          async function supportTask(input: string): Promise<string> {
            const messages: Array<{ role: string; content: string }> = [
              { role: "system", content: "You are a helpful customer support agent." }
            ];

            // Turn 1: Customer's initial question
            messages.push({ role: "user", content: input });
            const response1 = await callLLM(messages);
            messages.push({ role: "assistant", content: response1 });

            // Turn 2: Customer asks for clarification
            messages.push({ role: "user", content: "Can you provide more details?" });
            const response2 = await callLLM(messages);
            messages.push({ role: "assistant", content: response2 });

            // Turn 3: Customer thanks the agent
            messages.push({ role: "user", content: "Thank you for your help!" });
            const response3 = await callLLM(messages);

            return response3;
          }

          // Scorer: Check if assistant responds politely using the conversation thread
          const politenessScorer: Scorer = async ({ trace }) => {
            if (!trace) return 0;

            // Get the full conversation as an array of messages
            const thread = await trace.getThread();

            // Check the last assistant message for polite language
            const lastAssistantMsg = thread.reverse().find(msg => msg.role === "assistant");
            const content = lastAssistantMsg?.content?.toLowerCase() || "";

            const politeWords = ["welcome", "glad", "happy", "pleasure", "thank"];
            const isPolite = politeWords.some(word => content.includes(word));

            return {
              name: "Politeness",
              score: isPolite ? 1 : 0,
              metadata: { checked_message_preview: content.slice(0, 80) },
            };
          };

          // Scorer: Check conversation efficiency by analyzing all spans
          const efficiencyScorer: Scorer = async ({ trace }) => {
            if (!trace) return 0;

            // Get all LLM spans to count how many calls were made
            const llmSpans = await trace.getSpans({ spanType: ["llm"] });

            // Efficient conversations should resolve in 3-5 LLM calls
            const isEfficient = llmSpans.length >= 3 && llmSpans.length <= 5;

            return {
              name: "Efficiency",
              score: isEfficient ? 1 : 0,
              metadata: { llm_calls: llmSpans.length },
            };
          };

          Eval("Support Quality", {
            data: SUPPORT_DATASET,
            task: supportTask,
            scores: [politenessScorer, efficiencyScorer],
          });
          ```

          ```python eval_trace_code_scorer.py theme={"theme":{"light":"github-light","dark":"github-dark-dimmed"}}
          from braintrust import Eval, wrap_openai, traced
          from openai import AsyncOpenAI

          client = wrap_openai(AsyncOpenAI())

          # Customer support dataset
          SUPPORT_DATASET = [
              {"input": "My order hasn't arrived yet. Order #12345."},
              {"input": "I need help resetting my password."},
          ]


          # Helper function to call the LLM (creates an LLM span)
          @traced
          async def call_llm(messages):
              response = await client.chat.completions.create(
                  model="gpt-5-mini",
                  messages=messages,
              )
              return response.choices[0].message.content or ""


          # Multi-turn customer support conversation
          async def support_task(input):
              messages = [
                  {"role": "system", "content": "You are a helpful customer support agent."}
              ]

              # Turn 1: Customer's initial question
              messages.append({"role": "user", "content": input})
              response1 = await call_llm(messages)
              messages.append({"role": "assistant", "content": response1})

              # Turn 2: Customer asks for clarification
              messages.append({"role": "user", "content": "Can you provide more details?"})
              response2 = await call_llm(messages)
              messages.append({"role": "assistant", "content": response2})

              # Turn 3: Customer thanks the agent
              messages.append({"role": "user", "content": "Thank you for your help!"})
              response3 = await call_llm(messages)

              return response3


          # Scorer: Check if assistant responds politely using the conversation thread
          async def politeness_scorer(input, output, expected, trace=None):
              if not trace:
                  return 0

              # Get the full conversation as an array of messages
              thread = await trace.get_thread()

              # Check the last assistant message for polite language
              last_assistant_msg = next(
                  (msg for msg in reversed(thread) if msg.get("role") == "assistant"), None
              )
              content = (last_assistant_msg.get("content") or "").lower() if last_assistant_msg else ""

              polite_words = ["welcome", "glad", "happy", "pleasure", "thank"]
              is_polite = any(word in content for word in polite_words)

              return {
                  "name": "Politeness",
                  "score": 1 if is_polite else 0,
                  "metadata": {"checked_message_preview": content[:80]},
              }


          # Scorer: Check conversation efficiency by analyzing all spans
          async def efficiency_scorer(input, output, expected, trace=None):
              if not trace:
                  return 0

              # Get all LLM spans to count how many calls were made
              llm_spans = await trace.get_spans(span_type=["llm"])

              # Efficient conversations should resolve in 3-5 LLM calls
              is_efficient = 3 <= len(llm_spans) <= 5

              return {
                  "name": "Efficiency",
                  "score": 1 if is_efficient else 0,
                  "metadata": {"llm_calls": len(llm_spans)},
              }


          Eval(
              "Support Quality",
              data=SUPPORT_DATASET,
              task=support_task,
              scores=[politeness_scorer, efficiency_scorer],
          )
          ```

          ```ruby eval_trace_code_scorer.rb theme={"theme":{"light":"github-light","dark":"github-dark-dimmed"}}
          require "braintrust"
          require "openai"

          Braintrust.init

          client = OpenAI::Client.new(api_key: ENV.fetch("OPENAI_API_KEY", nil))

          SUPPORT_DATASET = [
            {input: "My order hasn't arrived yet. Order #12345."},
            {input: "I need help resetting my password."},
          ]

          def chat(client, messages)
            client.chat.completions.create(model: "gpt-5-mini", messages: messages)
              .choices.first.message.content || ""
          end

          # Multi-turn customer support task (each LLM call creates a span)
          support_task = Braintrust::Task.new("support") do |input:|
            messages = [{role: "system", content: "You are a helpful customer support agent."}]

            messages << {role: "user", content: input}
            messages << {role: "assistant", content: chat(client, messages)}

            messages << {role: "user", content: "Can you provide more details?"}
            messages << {role: "assistant", content: chat(client, messages)}

            messages << {role: "user", content: "Thank you for your help!"}
            chat(client, messages)
          end

          # Scorer: check if assistant responds politely using the conversation thread
          politeness_scorer = Braintrust::Scorer.new("politeness") do |trace:|
            next 0 unless trace

            thread = trace.thread
            last_assistant = thread.reverse.find { |msg| msg["role"] == "assistant" }
            content = (last_assistant&.dig("content") || "").downcase

            polite_words = ["welcome", "glad", "happy", "pleasure", "thank"]
            is_polite = polite_words.any? { |word| content.include?(word) }

            {score: is_polite ? 1.0 : 0.0, metadata: {checked_message_preview: content[0, 80]}}
          end

          # Scorer: check conversation efficiency by counting LLM calls
          efficiency_scorer = Braintrust::Scorer.new("efficiency") do |trace:|
            next 0 unless trace

            llm_spans = trace.spans(span_type: "llm")
            is_efficient = llm_spans.length.between?(3, 5)

            {score: is_efficient ? 1.0 : 0.0, metadata: {llm_calls: llm_spans.length}}
          end

          Braintrust::Eval.run(
            project: "Support Quality",
            cases: SUPPORT_DATASET,
            task: support_task,
            scorers: [politeness_scorer, efficiency_scorer]
          )

          OpenTelemetry.tracer_provider.shutdown
          ```
        </CodeGroup>
      </Tab>

      <Tab title="CLI" icon="terminal">
        Define TypeScript or Python scorers in code and push to Braintrust:

        <CodeGroup dropdown>
          ```typescript title="trace_code_scorer.ts" theme={"theme":{"light":"github-light","dark":"github-dark-dimmed"}}
          import braintrust from "braintrust";
          import { z } from "zod";

          const project = braintrust.projects.create({ name: "my-project" });

          project.scorers.create({
            name: "Politeness scorer",
            slug: "politeness-scorer",
            description: "Check if assistant responds politely",
            parameters: z.object({
              trace: z.any(),
            }),
            handler: async ({ trace }) => {
              if (!trace) return 0;

              // Get the full conversation thread
              const thread = await trace.getThread();

              // Check the last assistant message for polite language
              const lastAssistantMsg = thread.reverse().find(msg => msg.role === "assistant");
              const content = lastAssistantMsg?.content?.toLowerCase() || "";

              const politeWords = ["welcome", "glad", "happy", "pleasure", "thank"];
              const isPolite = politeWords.some(word => content.includes(word));

              return {
                score: isPolite ? 1 : 0,
                metadata: { checked_message_preview: content.slice(0, 80) },
              };
            },
          });

          project.scorers.create({
            name: "Efficiency scorer",
            slug: "efficiency-scorer",
            description: "Check if conversation was efficient",
            parameters: z.object({
              trace: z.any(),
            }),
            handler: async ({ trace }) => {
              if (!trace) return 0;

              // Get all LLM spans to count how many calls were made
              const llmSpans = await trace.getSpans({ spanType: ["llm"] });

              // Efficient conversations should resolve in 3-5 LLM calls
              const isEfficient = llmSpans.length >= 3 && llmSpans.length <= 5;

              return {
                score: isEfficient ? 1 : 0,
                metadata: { llm_calls: llmSpans.length },
              };
            },
          });
          ```

          ```python title="trace_code_scorer.py" theme={"theme":{"light":"github-light","dark":"github-dark-dimmed"}}
          import braintrust
          from pydantic import BaseModel

          project = braintrust.projects.create(name="my-project")

          class TraceParams(BaseModel):
              trace: dict

          async def politeness_scorer(trace):
              if not trace:
                  return 0

              # Get the full conversation thread
              thread = await trace.get_thread()

              # Check the last assistant message for polite language
              last_assistant_msg = next(
                  (msg for msg in reversed(thread) if msg.get("role") == "assistant"), None
              )
              content = (last_assistant_msg.get("content") or "").lower() if last_assistant_msg else ""

              polite_words = ["welcome", "glad", "happy", "pleasure", "thank"]
              is_polite = any(word in content for word in polite_words)

              return {
                  "score": 1 if is_polite else 0,
                  "metadata": {"checked_message_preview": content[:80]},
              }

          async def efficiency_scorer(trace):
              if not trace:
                  return 0

              # Get all LLM spans to count how many calls were made
              llm_spans = await trace.get_spans(span_type=["llm"])

              # Efficient conversations should resolve in 3-5 LLM calls
              is_efficient = 3 <= len(llm_spans) <= 5

              return {
                  "score": 1 if is_efficient else 0,
                  "metadata": {"llm_calls": len(llm_spans)},
              }

          project.scorers.create(
              name="Politeness scorer",
              slug="politeness-scorer",
              description="Check if assistant responds politely",
              parameters=TraceParams,
              handler=politeness_scorer,
          )

          project.scorers.create(
              name="Efficiency scorer",
              slug="efficiency-scorer",
              description="Check if conversation was efficient",
              parameters=TraceParams,
              handler=efficiency_scorer,
          )
          ```
        </CodeGroup>

        Push to Braintrust:

        <CodeGroup>
          ```bash TypeScript theme={"theme":{"light":"github-light","dark":"github-dark-dimmed"}}
          bt functions push trace_code_scorer.ts
          ```

          ```bash Python theme={"theme":{"light":"github-light","dark":"github-dark-dimmed"}}
          bt functions push trace_code_scorer.py
          ```
        </CodeGroup>
      </Tab>

      <Tab title="UI" icon="mouse-pointer-2">
        Create trace-level scorers in the Braintrust UI:

        1. Go to <Icon icon="triangle" /> **Scorers** > **+ Scorer**.
        2. Enter a scorer name and slug.
        3. Select **TypeScript** or **Python**.
        4. Set **Scope** to **Trace** to evaluate entire traces.
        5. Write your scorer function with the `trace` parameter. The code editor provides real-time linting and autocomplete to help you write correct code faster.
        6. Click **Save as custom scorer**.

        <CodeGroup dropdown>
          ```typescript theme={"theme":{"light":"github-light","dark":"github-dark-dimmed"}}
          import type { Trace } from 'braintrust';

          // Enter handler function that returns a numeric score between 0 and 1,
          // or an object with `score` and optional `metadata` and `name` fields,
          // or null to skip scoring
          async function handler({
            input,      // Automatically populated from root span
            output,     // Automatically populated from root span
            expected,   // Automatically populated from root span
            metadata,   // Automatically populated from root span
            trace,      // Trace object for accessing spans
          }: {
            input: any;
            output: any;
            expected: any;
            metadata: Record<string, any>;
            trace: Trace;
          }): Promise<
            | number
            | { score: number; name?: string; metadata?: Record<string, unknown> }
            | null
          > {
            if (expected === null) return null;

            // Get all spans (no filter)
            const allSpans = await trace.getSpans();
            // Get only LLM spans
            const llmSpans = await trace.getSpans({ spanType: ["llm"] });

            return {
              name: "span count scorer",
              score: output === expected ? 1 : 0,
              metadata: {
                totalSpanCount: allSpans.length,
                llmSpanCount: llmSpans.length,
              },
            };
          }
          ```

          ```python theme={"theme":{"light":"github-light","dark":"github-dark-dimmed"}}
          from typing import Any
          # Enter handler function that returns a numeric score between 0 and 1,
          # or a dict with `score` and optional `metadata` and `name` fields,
          # or None to skip scoring
          async def handler(
            input: Any,                    # Automatically populated from root span
            output: Any,                   # Automatically populated from root span
            expected: Any,                 # Automatically populated from root span
            metadata: dict[str, Any],      # Automatically populated from root span
            trace: Any                     # Trace object for accessing spans
          ) -> float | dict[str, Any] | None:
            if expected is None:
              return None

            # Get all spans (no filter)
            all_spans = await trace.get_spans()
            # Get only LLM spans
            llm_spans = await trace.get_spans(span_type=['llm'])

            return {
              'name': 'span count scorer',
              'score': 1.0 if output == expected else 0.0,
              'metadata': {
                'total_span_count': len(all_spans),
                'llm_span_count': len(llm_spans),
              },
            }
          ```
        </CodeGroup>

        <Note>
          UI scorers have access to these packages:

          * `anthropic`
          * `autoevals`
          * `braintrust`
          * `json`
          * `math`
          * `openai`
          * `re`
          * `requests`
          * `typing`

          For additional packages, use the SDK tab.
        </Note>
      </Tab>
    </Tabs>
  </Tab>
</Tabs>

## Set pass thresholds

Define minimum acceptable scores to automatically mark results as passing or failing. When configured, scores that meet or exceed the threshold are marked as **passing** (green highlighting with checkmark), while scores below are marked as **failing** (red highlighting).

<Tabs className="tabs-border">
  <Tab title="SDK" icon="code">
    Add `__pass_threshold` to the scorer's metadata (value between 0 and 1):

    ```typescript theme={"theme":{"light":"github-light","dark":"github-dark-dimmed"}}
    metadata: {
      __pass_threshold: 0.7,  // Scores below 0.7 are considered failures
    }
    ```

    Example with a custom code scorer:

    <CodeGroup dropdown>
      ```typescript theme={"theme":{"light":"github-light","dark":"github-dark-dimmed"}}
      project.scorers.create({
        name: "Quality checker",
        slug: "quality-checker",
        handler: async ({ output, expected }) => {
          return output === expected ? 1 : 0;
        },
        metadata: {
          __pass_threshold: 0.8,
        },
      });
      ```

      ```python theme={"theme":{"light":"github-light","dark":"github-dark-dimmed"}}
      @project.scorers.create(
          name="Quality checker",
          slug="quality-checker",
          metadata={"__pass_threshold": 0.8},
      )
      def quality_checker(output, expected):
          return 1 if output == expected else 0
      ```

      ```ruby theme={"theme":{"light":"github-light","dark":"github-dark-dimmed"}}
      # Pass thresholds are not supported in the Ruby SDK.
      # Use the UI or push a TypeScript/Python scorer via the CLI to set a pass threshold.
      ```

      ```csharp #skip-compile theme={"theme":{"light":"github-light","dark":"github-dark-dimmed"}}
      // Pass thresholds are not supported in the C# SDK.
      // Use the UI or push a TypeScript/Python scorer via the CLI to set a pass threshold.
      ```
    </CodeGroup>
  </Tab>

  <Tab title="UI" icon="mouse-pointer-2">
    When creating or editing a scorer in the UI:

    1. Look for the **Pass threshold** slider in the scorer configuration.
    2. Drag the slider to set your minimum acceptable score (0-1).
    3. Click **Save as custom scorer**.

    The threshold can be set for any scorer type (autoevals, LLM-as-a-judge, or custom code).
  </Tab>
</Tabs>

## Return multiple scores

A single scorer can return an array of score objects to emit multiple named metrics from one call. This is useful when several quality dimensions can be computed together in one pass or share a single LLM judge call. Each item appears as its own score column in the Braintrust UI.

Each item requires `name` and `score`. `metadata` is optional.

<Tabs className="tabs-border">
  <Tab title="SDK" icon="code">
    <CodeGroup dropdown>
      ```typescript theme={"theme":{"light":"github-light","dark":"github-dark-dimmed"}}
      // Return an array of score objects from a scorer function
      Eval("Summary Quality", {
        data: DATASET,
        task,
        scores: [
          ({ output, expected }) => {
            const words = (output ?? "").toLowerCase().split(/\s+/);
            const keyTerms: string[] = expected.key_terms;
            const covered = keyTerms.filter((t) => words.includes(t)).length;
            return [
              {
                name: "coverage",
                score: keyTerms.length ? covered / keyTerms.length : 1,
                metadata: { missing: keyTerms.filter((t) => !words.includes(t)) },
              },
              {
                name: "conciseness",
                score: words.length <= expected.max_words ? 1 : 0,
                metadata: { word_count: words.length, limit: expected.max_words },
              },
            ];
          },
        ],
      });
      ```

      ```python theme={"theme":{"light":"github-light","dark":"github-dark-dimmed"}}
      from braintrust import Eval, Score

      def summary_quality(output, expected, **kwargs):
          words = (output or "").lower().split()
          key_terms = expected["key_terms"]
          covered = sum(1 for t in key_terms if t in words)
          return [
              Score(
                  name="coverage",
                  score=covered / len(key_terms) if key_terms else 1.0,
                  metadata={"missing": [t for t in key_terms if t not in words]},
              ),
              Score(
                  name="conciseness",
                  score=1.0 if len(words) <= expected["max_words"] else 0.0,
                  metadata={"word_count": len(words), "limit": expected["max_words"]},
              ),
          ]

      Eval("Summary Quality", data=DATASET, task=task, scores=[summary_quality])
      ```

      ```ruby multi_score.rb theme={"theme":{"light":"github-light","dark":"github-dark-dimmed"}}
      # Block-based: return an array of score hashes
      summary_quality = Braintrust::Scorer.new("summary_quality") do |output:, expected:|
        words = output.to_s.downcase.split
        key_terms = expected[:key_terms]
        covered = key_terms.count { |t| words.include?(t) }

        [
          {
            name: "coverage",
            score: key_terms.empty? ? 1.0 : covered.to_f / key_terms.size,
            metadata: {missing: key_terms - words}
          },
          {
            name: "conciseness",
            score: words.size <= expected[:max_words] ? 1.0 : 0.0,
            metadata: {word_count: words.size, limit: expected[:max_words]}
          }
        ]
      end

      # Class-based: include Braintrust::Scorer and define #call
      class StyleChecker
        include Braintrust::Scorer

        def call(output:, **)
          text = output.to_s
          [
            {name: "ends_with_period", score: text.strip.end_with?(".") ? 1.0 : 0.0},
            {name: "no_first_person", score: (%w[i me my we us].none? { |w| text.downcase.include?(w) }) ? 1.0 : 0.0}
          ]
        end
      end
      ```
    </CodeGroup>
  </Tab>
</Tabs>

## Test scorers

Scorers need to be developed iteratively against real data. When creating or editing a scorer in the UI, use the <Icon icon="play" /> **Run** section to test your scorer with data from different sources. Each variable source populates the scorer's input parameters (like `input`, `output`, `expected`, `metadata`) from a different location.

### Test with manual input

Best for initial development when you have a specific example in mind. Use this to quickly prototype and verify basic scorer logic before testing on larger datasets.

1. Select **Editor** in the <Icon icon="play" /> **Run** section.
2. Enter values for `input`, `output`, `expected`, and `metadata` fields.
3. Click **Test** to see how your scorer evaluates the example
4. Iterate on your scorer logic based on the results

### Test with a dataset

Best for testing specific scenarios, edge cases, or regression testing. Use this when you want controlled, repeatable test cases or need to ensure your scorer handles specific situations correctly.

1. Select **Dataset** in the <Icon icon="play" /> **Run** section.
2. Choose a dataset from your project.
3. Select a record to test with.
4. Click **Test** to see how your scorer evaluates the example.
5. Review results to identify patterns and edge cases.

### Test with logs

Best for testing against actual usage patterns and debugging real-world edge cases. Use this when you want to see how your scorer performs on data your system is actually generating.

1. Select **Logs** in the <Icon icon="play" /> **Run** section.
2. Select the project containing the logs you want to test against.
3. Filter logs to find relevant examples:
   * Click <Icon icon="list-filter" /> **Add filter** and choose just root spans, specific span names, or a more advanced filter based on specific input, output, metadata, or other values.
   * Select a timeframe.
4. Click **Test** to see how your scorer evaluates real production data.
5. Identify cases where the scorer needs adjustment for real-world scenarios.

<Tip>
  To create a new online scoring rule with the filters automatically prepopulated from your current log filters, click <Icon icon="radio" /> **Automations**. This enables rapid iteration from logs to scoring rules. See [Create scoring rules](/evaluate/score-online#create-scoring-rules) for more details.
</Tip>

## Scorer permissions

Both [LLM-as-a-judge scorers](#llm-as-a-judge) and [custom code scorers](#custom-code) automatically receive a `BRAINTRUST_API_KEY` environment variable that allows them to:

* Make LLM calls using organization and project AI secrets
* Access attachments from the current project
* Read and write logs to the current project
* Read prompts from the organization

For custom code scorers that need expanded permissions beyond the current project (such as logging to other projects, reading datasets, or accessing other organization data), you can provide your own API key using the [`PUT /v1/env_var`](https://www.braintrust.dev/docs/api-reference/envvars/create-or-replace-env_var#create-or-replace-env_var) endpoint.

## Optimize with Loop

Generate and improve scorers using [<Icon icon="blend" /> **Loop**](/loop):

Example queries:

* "Write an LLM-as-a-judge scorer for a chatbot that answers product questions"
* "Generate a code-based scorer based on project logs"
* "Optimize the Helpfulness scorer"
* "Adjust the scorer to be more lenient"

## Best practices

**Start with autoevals**: Use pre-built scorers when they fit your needs. They're well-tested and reliable.

**Be specific**: Define clear evaluation criteria in your scorer prompts or code.

**Use multiple scorers**: Measure different aspects (factuality, helpfulness, tone) with separate scorers.

**Choose the right scope**: Use trace scorers (custom code with `trace` parameter) for multi-step workflows and agents. Use output scorers for simple quality checks.

**Test scorers**: Run scorers on known examples to verify they behave as expected.

**Version scorers**: Like prompts, scorers are versioned automatically. Track what works.

**Balance cost and quality**: LLM-as-a-judge scorers are more flexible but cost more and take longer than custom code scorers.

## Create custom table views

The **Scorers** page supports custom table views to save your preferred filters, column order, and display settings.

To create or update a custom table view:

1. Apply the filters and display settings you want.
2. Open the <Icon icon="layers-2" /> menu and select **Save view\...** or **Save view as...**.

<Note>
  Custom table views are visible to all project members. Creating or editing a table view requires the **Update** project permission.
</Note>

## Set default table views

You can set default views at two levels:

* **Organization default**: Visible to all members when they open the page. This applies per page — for example, you can set separate organization defaults for Logs, Experiments, and Review. To set an organization default, you need the **Manage settings** organization permission (included by default in the **Owner** role). See [Access control](/admin/access-control) for details.
* **Personal default**: Overrides the organization default for you only. Personal defaults are stored in your browser, so they do not carry over across devices or browsers.

To set a default view:

1. Switch to the view you want by selecting it from the <Icon icon="layers-2" /> menu.
2. Open the menu again and hover over the currently selected view to reveal its submenu.
3. Choose <Icon icon="flag-triangle-right" /> **Set as personal default view** or <Icon icon="pin" /> **Set as organization default view**.

To clear a default view:

1. Open the <Icon icon="layers-2" /> menu and hover over the currently selected view to reveal its submenu.
2. Choose <Icon icon="flag-triangle-right" /> **Clear personal default view** or <Icon icon="pin" /> **Clear organization default view**.

When a user opens a page, Braintrust loads the first match in this order: personal default, organization default, then the standard "All ..." view (e.g., "All logs view").

## Next steps

* [Run evaluations](/evaluate/run-evaluations) using your scorers
* [Interpret results](/evaluate/interpret-results) to understand scores
* [Write prompts](/evaluate/write-prompts) to guide model behavior
* [Use playgrounds](/evaluate/playgrounds) to test scorers interactively
