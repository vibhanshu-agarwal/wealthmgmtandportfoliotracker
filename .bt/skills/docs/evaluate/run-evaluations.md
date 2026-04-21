> ## Documentation Index
> Fetch the complete documentation index at: https://braintrust.dev/docs/llms.txt
> Use this file to discover all available pages before exploring further.

# Create experiments

> Create experiments in code, the UI, or CI/CD to capture immutable evaluation snapshots

An experiment is an immutable snapshot of an evaluation run — permanently stored, comparable over time, and shareable across your team. Unlike playground runs, which overwrite previous results for fast iteration, experiments preserve exact results so you can measure improvements, catch regressions, and build confidence in your changes.

## Run locally

Run evaluation code locally to create an experiment in Braintrust and return summary metrics, including a direct link to your experiment. See [Interpret results](/evaluate/interpret-results) for how to read it.

<Tabs>
  <Tab title="TypeScript">
    Install the SDK and dependencies:

    ```bash theme={"theme":{"light":"github-light","dark":"github-dark-dimmed"}}
    # pnpm
    pnpm add braintrust openai autoevals
    # npm
    npm install braintrust openai autoevals
    ```

    Create the eval code:

    ```typescript wrap theme={"theme":{"light":"github-light","dark":"github-dark-dimmed"}}
    import { Eval, initDataset } from "braintrust";
    import { Factuality } from "autoevals";

    Eval("My Project", {
      experimentName: "My experiment",
      data: initDataset("My Project", { dataset: "My dataset" }),
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

    Run your evaluation with the [`bt eval`](/reference/cli/eval) CLI:

    ```bash theme={"theme":{"light":"github-light","dark":"github-dark-dimmed"}}
    bt eval my_eval.eval.ts
    ```

    Use `--watch` to re-run automatically when files change:

    ```bash theme={"theme":{"light":"github-light","dark":"github-dark-dimmed"}}
    bt eval --watch my_eval.eval.ts
    ```

    **Benefits of using the CLI:**

    * **Automatic `.env` loading** — reads `.env.development.local`, `.env.local`, `.env.development`, and `.env`
    * **Multi-file support** — pass multiple files or directories: `bt eval [file or directory] ...`. Running `bt eval` with no arguments runs all eval files in the current directory.
    * **TypeScript transpilation** — no build step required; the CLI handles it
  </Tab>

  <Tab title="Python">
    Install the SDK and dependencies:

    ```bash theme={"theme":{"light":"github-light","dark":"github-dark-dimmed"}}
    pip install braintrust openai autoevals
    ```

    Create the eval code:

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

    Run your evaluation with the [`bt eval`](/reference/cli/eval) CLI:

    ```bash theme={"theme":{"light":"github-light","dark":"github-dark-dimmed"}}
    bt eval my_eval.py
    ```

    Use `--watch` to re-run automatically when files change:

    ```bash theme={"theme":{"light":"github-light","dark":"github-dark-dimmed"}}
    bt eval --watch my_eval.py
    ```

    **Benefits of using the CLI:**

    * **Automatic `.env` loading** — reads `.env.development.local`, `.env.local`, `.env.development`, and `.env`
    * **Multi-file support** — pass multiple files or directories: `bt eval [file or directory] ...`. Running `bt eval` with no arguments runs all eval files in the current directory.
    * **TypeScript transpilation** — no build step required; the CLI handles it
  </Tab>

  <Tab title="Go">
    Install the SDK and dependencies:

    ```bash theme={"theme":{"light":"github-light","dark":"github-dark-dimmed"}}
    go get github.com/braintrustdata/braintrust-sdk-go
    go get github.com/openai/openai-go
    ```

    Create the eval code:

    ```go wrap theme={"theme":{"light":"github-light","dark":"github-dark-dimmed"}}
    package main

    import (
    	"context"
    	"log"

    	"go.opentelemetry.io/otel"
    	"go.opentelemetry.io/otel/sdk/trace"

    	"github.com/braintrustdata/braintrust-sdk-go"
    	"github.com/braintrustdata/braintrust-sdk-go/eval"
    )

    func callModel(input string) string {
    	// Your LLM call implementation here
    	return "model output"
    }

    func main() {
    	ctx := context.Background()

    	tp := trace.NewTracerProvider()
    	defer tp.Shutdown(ctx)
    	otel.SetTracerProvider(tp)

    	client, err := braintrust.New(tp)
    	if err != nil {
    		log.Fatal(err)
    	}

    	evaluator := braintrust.NewEvaluator[string, string](client)

    	_, err = evaluator.Run(ctx, eval.Opts[string, string]{
    		Experiment: "My project",
    		Dataset: eval.NewDataset([]eval.Case[string, string]{
    			{Input: "example input", Expected: "example expected"},
    		}),
    		Task: eval.T(func(ctx context.Context, input string) (string, error) {
    			return callModel(input), nil // Your LLM call here
    		}),
    		Scorers: []eval.Scorer[string, string]{
    			eval.NewScorer("exact-match", func(ctx context.Context, r eval.TaskResult[string, string]) (eval.Scores, error) {
    				score := 0.0
    				if r.Output == r.Expected {
    					score = 1.0
    				}
    				return eval.S(score), nil
    			}),
    		},
    		Metadata: map[string]any{
    			"model": "gpt-5-mini",
    		},
    	})
    	if err != nil {
    		log.Fatal(err)
    	}
    }
    ```

    Run your evaluation:

    ```bash theme={"theme":{"light":"github-light","dark":"github-dark-dimmed"}}
    go run my_eval.go
    ```
  </Tab>

  <Tab title="Ruby">
    Install the SDK and dependencies:

    ```bash theme={"theme":{"light":"github-light","dark":"github-dark-dimmed"}}
    # Add to your Gemfile:
    gem "braintrust"
    gem "openai"

    bundle install
    ```

    Create the eval code:

    ```ruby wrap theme={"theme":{"light":"github-light","dark":"github-dark-dimmed"}}
    require "braintrust"

    Braintrust.init

    Braintrust::Eval.run(
      project: "My project",
      cases: [
        {input: "example input", expected: "example expected"},
      ],
      task: ->(input:) { call_model(input) },  # Your LLM call here
      scorers: [
        Braintrust::Scorer.new("exact_match") { |expected:, output:| output == expected ? 1.0 : 0.0 }
      ],
      metadata: {model: "gpt-5-mini"}
    )

    OpenTelemetry.tracer_provider.shutdown
    ```

    Run your evaluation:

    ```bash theme={"theme":{"light":"github-light","dark":"github-dark-dimmed"}}
    ruby my_eval.rb
    ```
  </Tab>

  <Tab title="Java">
    Install the SDK and dependencies:

    ```bash theme={"theme":{"light":"github-light","dark":"github-dark-dimmed"}}
    # Add to build.gradle dependencies{} block:
    implementation 'dev.braintrust:braintrust-sdk-java:<version>'
    implementation 'com.openai:openai-java-sdk:<version>'
    ```

    Create the eval code:

    ```java wrap theme={"theme":{"light":"github-light","dark":"github-dark-dimmed"}}
    import dev.braintrust.Braintrust;
    import dev.braintrust.eval.DatasetCase;
    import dev.braintrust.eval.Scorer;

    class Main {
      static String callModel(String input) {
        // Your LLM call implementation here
        return "model output";
      }

      public static void main(String... args) {
        var braintrust = Braintrust.get();
        var openTelemetry = braintrust.openTelemetryCreate();

        var eval = braintrust.<String, String>evalBuilder()
            .name("My project")
            .cases(DatasetCase.of("example input", "example expected"))
            .taskFunction(input -> callModel(input)) // Your LLM call here
            .scorers(
                Scorer.of("exact_match", (expected, actual) -> expected.equals(actual) ? 1.0 : 0.0)
            )
            .metadata(java.util.Map.of(
                "model", "gpt-5-mini"
            ))
            .build();

        var result = eval.run();
        System.out.println(result.createReportString());
      }
    }
    ```

    Run your evaluation:

    ```bash theme={"theme":{"light":"github-light","dark":"github-dark-dimmed"}}
    javac -cp ".:*" MyEval.java
    java -cp ".:*" MyEval
    ```
  </Tab>

  <Tab title="C#">
    Install the SDK and dependencies:

    ```bash theme={"theme":{"light":"github-light","dark":"github-dark-dimmed"}}
    dotnet add package Braintrust.Sdk
    dotnet add package Braintrust.Sdk.OpenAI
    dotnet add package OpenAI
    ```

    Create the eval code:

    ```csharp wrap theme={"theme":{"light":"github-light","dark":"github-dark-dimmed"}}
    using System;
    using System.Collections.Generic;
    using System.Threading.Tasks;
    using Braintrust.Sdk;
    using Braintrust.Sdk.Eval;

    class Program
    {
        static string CallModel(string input)
        {
            // Your LLM call implementation here
            return "model output";
        }

        static async Task Main(string[] args)
        {
            var braintrust = Braintrust.Sdk.Braintrust.Get();

            var eval = await braintrust
                .EvalBuilder<string, string>()
                .Name("My Project")
                .Cases(
                    new DatasetCase<string, string>("example input", "example expected")
                )
                .TaskFunction(input => CallModel(input)) // Your LLM call here
                .Scorers(
                    new FunctionScorer<string, string>("exact_match", (expected, actual) =>
                        actual == expected ? 1.0 : 0.0)
                )
                .BuildAsync();

            var result = await eval.RunAsync();
            Console.WriteLine(result.CreateReportString());
        }
    }
    ```

    Run your evaluation:

    ```bash theme={"theme":{"light":"github-light","dark":"github-dark-dimmed"}}
    dotnet run
    ```
  </Tab>
</Tabs>

<Tip>
  You can pass a `parameters` option to make configuration values (like model choice, temperature, or prompts) editable in the playground without changing code. Define parameters inline or use `loadParameters()` to reference saved configurations. See [Write parameters](/evaluate/write-parameters) and [Test complex agents](/evaluate/remote-evals) for details.
</Tip>

## Run in UI

### Create from scratch

Create and run experiments directly in the Braintrust UI without writing code:

1. Go to **<Icon icon="beaker" /> Experiments**.
2. Click **+ Experiment** or use the empty state form.
3. Select one or more prompts, workflows, or scorers to evaluate.
4. Choose or create a dataset:
   * **Select existing dataset**: Pick from datasets in your organization
   * **Upload CSV/JSON**: Import test cases from a file
   * **Empty dataset**: Create a blank dataset to populate manually later
5. Add scorers to measure output quality.
6. Click **Create** to execute the experiment.

<Note>
  UI experiments run without a time limit on cloud and on self-hosted deployments running data plane v2.0 or later.
</Note>

### Promote from a playground

Playground runs are mutable — re-running overwrites previous results. When you've iterated to a configuration worth keeping, promote it to an experiment to capture an immutable snapshot:

1. Run your playground.
2. Select **+ Experiment**.
3. Name your experiment.
4. Access it from the **<Icon icon="beaker" /> Experiments** page.

Each playground task maps to its own experiment. Experiments created this way are comparable to any other experiment in your project.

<video controls className="w-full aspect-video rounded-xl" src="https://mintcdn.com/braintrust/b11zJxKLgN0Qiq8B/images/guides/playground/create-experiment-from-playground.mp4?fit=max&auto=format&n=b11zJxKLgN0Qiq8B&q=85&s=f3b55552ab767c405d63b24a7edcef79" poster="/images/guides/playground/create-experiment-poster.png" data-path="images/guides/playground/create-experiment-from-playground.mp4" />

## Run in CI/CD

Integrate evaluations into your CI/CD pipeline to catch regressions before they reach production.

### GitHub Actions

Use the [`braintrustdata/eval-action`](https://github.com/braintrustdata/eval-action) to run evaluations on every pull request:

```yaml theme={"theme":{"light":"github-light","dark":"github-dark-dimmed"}}
name: Run evaluations

on:
  pull_request:
    branches: [main]

jobs:
  evaluate:
    runs-on: ubuntu-latest
    steps:
      - uses: actions/checkout@v3

      - name: Set up Node.js
        uses: actions/setup-node@v3
        with:
          node-version: '18'

      - name: Install dependencies
        run: npm install

      - name: Run Evals
        uses: braintrustdata/eval-action@v1
        with:
          api_key: ${{ secrets.BRAINTRUST_API_KEY }}
          runtime: node
```

The action automatically posts a comment with results to the pull request.

### Other CI systems

For other CI systems, use [`bt eval`](/reference/cli/eval) directly:

```bash theme={"theme":{"light":"github-light","dark":"github-dark-dimmed"}}
BRAINTRUST_API_KEY=$BRAINTRUST_API_KEY bt eval evals/ --no-input --json
```

Create an API key under <Icon icon="settings-2" /> **Settings** > <Icon icon="key-square" /> **API keys** and set it as `BRAINTRUST_API_KEY` in your CI environment. Use `--no-input` to suppress prompts and `--json` for machine-readable output.

## Configure experiments

Customize experiment behavior with options:

<CodeGroup dropdown>
  ```typescript theme={"theme":{"light":"github-light","dark":"github-dark-dimmed"}}
  Eval("My Project", {
    data: myDataset,
    task: myTask,
    scores: [Factuality],

    // Experiment name
    experiment: "gpt-5-mini-experiment",

    // Metadata for filtering/analysis
    metadata: {
      model: "gpt-5-mini",
      prompt_version: "v2",
    },

    // Maximum concurrency
    maxConcurrency: 10,

    // Trial count for averaging
    trialCount: 3,
  });
  ```

  ```python theme={"theme":{"light":"github-light","dark":"github-dark-dimmed"}}
  Eval(
      "My Project",
      data=my_dataset,
      task=my_task,
      scores=[Factuality],

      # Experiment name
      experiment="gpt-5-mini-experiment",

      # Metadata for filtering/analysis
      metadata={
          "model": "gpt-5-mini",
          "prompt_version": "v2",
      },

      # Maximum concurrency
      max_concurrency=10,

      # Trial count for averaging
      trial_count=3,
  )
  ```

  ```go theme={"theme":{"light":"github-light","dark":"github-dark-dimmed"}}
  package main

  import (
  	"context"
  	"log"

  	"go.opentelemetry.io/otel"
  	"go.opentelemetry.io/otel/sdk/trace"

  	"github.com/braintrustdata/braintrust-sdk-go"
  	"github.com/braintrustdata/braintrust-sdk-go/eval"
  )

  func main() {
  	ctx := context.Background()

  	tp := trace.NewTracerProvider()
  	defer tp.Shutdown(ctx)
  	otel.SetTracerProvider(tp)

  	client, err := braintrust.New(tp)
  	if err != nil {
  		log.Fatal(err)
  	}

  	evaluator := braintrust.NewEvaluator[string, string](client)

  	// Example variables (define your own)
  	myDataset := eval.NewDataset([]eval.Case[string, string]{
  		{Input: "example input", Expected: "example expected"},
  	})
  	myTask := eval.T(func(ctx context.Context, input string) (string, error) {
  		return "model output", nil
  	})
  	myScorers := []eval.Scorer[string, string]{
  		eval.NewScorer("exact-match", func(ctx context.Context, r eval.TaskResult[string, string]) (eval.Scores, error) {
  			score := 0.0
  			if r.Output == r.Expected {
  				score = 1.0
  			}
  			return eval.S(score), nil
  		}),
  	}

  	_, err = evaluator.Run(ctx, eval.Opts[string, string]{
  		Experiment: "gpt-5-mini-experiment",
  		Dataset:    myDataset,
  		Task:       myTask,
  		Scorers:    myScorers,
  		Metadata: map[string]any{
  			"model":          "gpt-5-mini",
  			"prompt_version": "v2",
  		},
  	})
  	if err != nil {
  		log.Fatal(err)
  	}
  }
  ```

  ```ruby theme={"theme":{"light":"github-light","dark":"github-dark-dimmed"}}
  Braintrust::Eval.run(
    project: "My Project",
    experiment: "gpt-5-mini-experiment",
    cases: my_dataset,
    task: my_task,
    scorers: my_scorers,
    metadata: {model: "gpt-5-mini", prompt_version: "v2"},
    max_concurrency: 10,
    trial_count: 3
  )
  ```

  ```java theme={"theme":{"light":"github-light","dark":"github-dark-dimmed"}}
  import dev.braintrust.Braintrust;
  import dev.braintrust.eval.DatasetCase;
  import dev.braintrust.eval.Scorer;
  import java.util.function.Function;

  class Main {
    public static void main(String... args) {
      var braintrust = Braintrust.get();
      var openTelemetry = braintrust.openTelemetryCreate();

      // Example variables (define your own)
      var myDataset = new DatasetCase[]{
          DatasetCase.of("example input", "example expected")
      };
      Function<String, String> myTask = input -> "model output";
      var myScorers = new Scorer[]{
          Scorer.of("exact_match", (expected, actual) -> expected.equals(actual) ? 1.0 : 0.0)
      };

      var result = braintrust.<String, String>evalBuilder()
          .name("gpt-5-mini-experiment")
          .cases(myDataset)
          .taskFunction(myTask)
          .scorers(myScorers)
          .metadata(java.util.Map.of(
              "model", "gpt-5-mini",
              "prompt_version", "v2"
          ))
          .build()
          .run();

      System.out.println(result.createReportString());
    }
  }
  ```

  ```csharp theme={"theme":{"light":"github-light","dark":"github-dark-dimmed"}}
  using System;
  using System.Collections.Generic;
  using System.Threading.Tasks;
  using Braintrust.Sdk;
  using Braintrust.Sdk.Eval;

  class Program
  {
      static async Task Main(string[] args)
      {
          var braintrust = Braintrust.Sdk.Braintrust.Get();

          // Example variables (define your own)
          var myDataset = new[] {
              new DatasetCase<string, string>("example input", "example expected")
          };
          Func<string, string> myTask = input => "model output";
          var myScorers = new[] {
              new FunctionScorer<string, string>("exact_match", (expected, actual) =>
                  actual == expected ? 1.0 : 0.0)
          };

          var eval = await braintrust
              .EvalBuilder<string, string>()
              .Name("gpt-5-mini-experiment")
              .Cases(myDataset)
              .TaskFunction(myTask)
              .Scorers(myScorers)
              .BuildAsync();

          var result = await eval.RunAsync();
          Console.WriteLine(result.CreateReportString());
      }
  }
  ```
</CodeGroup>

### Run without uploading results

Sometimes you want to run your evaluation locally without creating an experiment in Braintrust — while iterating on a new scorer, wiring up a new eval pipeline, or running in an environment without a Braintrust API key. Your tasks and scorers still run and print a summary to your terminal; results just aren't uploaded.

<Tabs>
  <Tab title="TypeScript">
    Via the CLI:

    ```bash theme={"theme":{"light":"github-light","dark":"github-dark-dimmed"}}
    bt eval --no-send-logs my_eval.eval.ts
    ```

    Or in code:

    ```typescript theme={"theme":{"light":"github-light","dark":"github-dark-dimmed"}}
    Eval("My Project", {
      data: ...,
      task: ...,
      scores: [...],
      noSendLogs: true,
    });
    ```
  </Tab>

  <Tab title="Python">
    Via the CLI:

    ```bash theme={"theme":{"light":"github-light","dark":"github-dark-dimmed"}}
    bt eval --no-send-logs my_eval.py
    ```

    Or in code:

    ```python theme={"theme":{"light":"github-light","dark":"github-dark-dimmed"}}
    Eval(
        "My Project",
        data=...,
        task=...,
        scores=[...],
        no_send_logs=True,
    )
    ```
  </Tab>
</Tabs>

### Run trials

Run each input multiple times to measure variance and get more robust scores. Braintrust intelligently aggregates results by bucketing test cases with the same `input` value:

<CodeGroup dropdown>
  ```typescript theme={"theme":{"light":"github-light","dark":"github-dark-dimmed"}}
  Eval("My Project", {
    data: myDataset,
    task: myTask,
    scores: [Factuality],
    trialCount: 10, // Run each input 10 times
  });
  ```

  ```python theme={"theme":{"light":"github-light","dark":"github-dark-dimmed"}}
  Eval(
      "My Project",
      data=my_dataset,
      task=my_task,
      scores=[Factuality],
      trial_count=10,  # Run each input 10 times
  )
  ```

  ```go theme={"theme":{"light":"github-light","dark":"github-dark-dimmed"}}
  package main

  import (
  	"context"
  	"log"

  	"go.opentelemetry.io/otel"
  	"go.opentelemetry.io/otel/sdk/trace"

  	"github.com/braintrustdata/braintrust-sdk-go"
  	"github.com/braintrustdata/braintrust-sdk-go/eval"
  )

  func main() {
  	ctx := context.Background()

  	tp := trace.NewTracerProvider()
  	defer tp.Shutdown(ctx)
  	otel.SetTracerProvider(tp)

  	client, err := braintrust.New(tp)
  	if err != nil {
  		log.Fatal(err)
  	}

  	evaluator := braintrust.NewEvaluator[string, string](client)

  	// Example variables (define your own)
  	myDataset := eval.NewDataset([]eval.Case[string, string]{
  		{Input: "example input", Expected: "example expected"},
  	})
  	myTask := eval.T(func(ctx context.Context, input string) (string, error) {
  		return "model output", nil
  	})
  	myScorers := []eval.Scorer[string, string]{
  		eval.NewScorer("exact-match", func(ctx context.Context, r eval.TaskResult[string, string]) (eval.Scores, error) {
  			score := 0.0
  			if r.Output == r.Expected {
  				score = 1.0
  			}
  			return eval.S(score), nil
  		}),
  	}

  	_, err = evaluator.Run(ctx, eval.Opts[string, string]{
  		Experiment: "My Project",
  		Dataset:    myDataset,
  		Task:       myTask,
  		Scorers:    myScorers,
  	})
  	if err != nil {
  		log.Fatal(err)
  	}
  }
  ```

  ```ruby theme={"theme":{"light":"github-light","dark":"github-dark-dimmed"}}
  Braintrust::Eval.run(
    project: "My Project",
    cases: my_dataset,
    task: my_task,
    scorers: my_scorers,
    trial_count: 10  # Run each input 10 times
  )
  ```

  ```java theme={"theme":{"light":"github-light","dark":"github-dark-dimmed"}}
  import dev.braintrust.Braintrust;
  import dev.braintrust.eval.DatasetCase;
  import dev.braintrust.eval.Scorer;
  import java.util.function.Function;

  class Main {
    public static void main(String... args) {
      var braintrust = Braintrust.get();
      var openTelemetry = braintrust.openTelemetryCreate();

      // Example variables (define your own)
      var myDataset = new DatasetCase[]{
          DatasetCase.of("example input", "example expected")
      };
      Function<String, String> myTask = input -> "model output";
      var myScorers = new Scorer[]{
          Scorer.of("exact_match", (expected, actual) -> expected.equals(actual) ? 1.0 : 0.0)
      };

      var result = braintrust.<String, String>evalBuilder()
          .name("My Project")
          .cases(myDataset)
          .taskFunction(myTask)
          .scorers(myScorers)
          .build()
          .run();

      System.out.println(result.createReportString());
    }
  }
  ```

  ```csharp theme={"theme":{"light":"github-light","dark":"github-dark-dimmed"}}
  using System;
  using System.Threading.Tasks;
  using Braintrust.Sdk;
  using Braintrust.Sdk.Eval;

  class Program
  {
      static async Task Main(string[] args)
      {
          var braintrust = Braintrust.Sdk.Braintrust.Get();

          // Example variables (define your own)
          var myDataset = new[] {
              new DatasetCase<string, string>("example input", "example expected")
          };
          Func<string, string> myTask = input => "model output";
          var myScorers = new[] {
              new FunctionScorer<string, string>("exact_match", (expected, actual) =>
                  actual == expected ? 1.0 : 0.0)
          };

          var eval = await braintrust
              .EvalBuilder<string, string>()
              .Name("My Project")
              .Cases(myDataset)
              .TaskFunction(myTask)
              .Scorers(myScorers)
              .BuildAsync();

          var result = await eval.RunAsync();
          Console.WriteLine(result.CreateReportString());
      }
  }
  ```
</CodeGroup>

To analyze trial results and compare variance across inputs, see [Compare trials](/evaluate/compare-experiments#compare-trials).

### Enable hill climbing

Hill climbing lets you improve iteratively without expected outputs by using a previous experiment's `output` as the `expected` for the current run. To enable it, use `BaseExperiment()` in the `data` field. Autoevals scorers like `Battle` and `Summary` are designed specifically for this workflow.

<CodeGroup dropdown>
  ```typescript theme={"theme":{"light":"github-light","dark":"github-dark-dimmed"}}
  import { Battle } from "autoevals";
  import { Eval, BaseExperiment } from "braintrust";

  Eval<string, string, string>(
    "Say Hi Bot", // Replace with your project name
    {
      data: BaseExperiment(),
      task: (input) => {
        return "Hi " + input; // Replace with your task function
      },
      scores: [Battle.partial({ instructions: "Which response said 'Hi'?" })],
    },
  );
  ```

  ```python theme={"theme":{"light":"github-light","dark":"github-dark-dimmed"}}
  from autoevals import Battle
  from braintrust import BaseExperiment, Eval

  Eval(
      "Say Hi Bot",  # Replace with your project name
      data=BaseExperiment(),
      task=lambda input: "Hi " + input,  # Replace with your task function
      scores=[Battle.partial(instructions="Which response said 'Hi'?")],
  )
  ```
</CodeGroup>

Braintrust automatically picks the best base experiment using git metadata if available or timestamps otherwise, then populates the `expected` field by merging the `expected` and `output` fields from the base experiment. If you set `expected` through the UI while reviewing results, it will be used as the `expected` field for the next experiment.

To use a specific experiment as the base, pass the `name` field to `BaseExperiment()`:

<CodeGroup dropdown>
  ```typescript theme={"theme":{"light":"github-light","dark":"github-dark-dimmed"}}
  import { Battle } from "autoevals";
  import { Eval, BaseExperiment } from "braintrust";

  Eval<string, string, string>(
    "Say Hi Bot", // Replace with your project name
    {
      data: BaseExperiment({ name: "main-123" }),
      task: (input) => {
        return "Hi " + input; // Replace with your task function
      },
      scores: [Battle.partial({ instructions: "Which response said 'Hi'?" })],
    },
  );
  ```

  ```python theme={"theme":{"light":"github-light","dark":"github-dark-dimmed"}}
  from autoevals import Battle
  from braintrust import BaseExperiment, Eval

  Eval(
      "Say Hi Bot",  # Replace with your project name
      data=BaseExperiment(name="main-123"),
      task=lambda input: "Hi " + input,  # Replace with your task function
      scores=[Battle.partial(instructions="Which response said 'Hi'?")],
  )
  ```
</CodeGroup>

When hill climbing, use two types of scoring functions:

* **Non-comparative methods** like `ClosedQA` that judge output quality based purely on input and output without requiring an expected value. Track these across experiments to compare any two experiments, even if they aren't sequentially related.
* **Comparative methods** like `Battle` or `Summary` that accept an `expected` output but don't treat it as ground truth. If you score > 50% on a comparative method, you're doing better than the base on average. Learn more about [how Battle and Summary work](https://github.com/braintrustdata/autoevals/tree/main/templates).

### Create custom reporters

When you run an experiment, Braintrust logs results to your terminal, and `bt eval` returns a non-zero exit code if any eval throws an exception. Customize this behavior for CI/CD pipelines to precisely define what constitutes a failure or to report results to different systems.

Define custom reporters using `Reporter()`. A reporter has two functions:

<CodeGroup dropdown>
  ```typescript theme={"theme":{"light":"github-light","dark":"github-dark-dimmed"}}
  import { Reporter } from "braintrust";

  Reporter(
    "My reporter", // Replace with your reporter name
    {
      reportEval(evaluator, result, opts) {
        // Summarizes the results of a single reporter, and return whatever you
        // want (the full results, a piece of text, or both!)
      },

      reportRun(results) {
        // Takes all the results and summarizes them. Return a true or false
        // which tells the process to exit.
        return true;
      },
    },
  );
  ```

  ```python theme={"theme":{"light":"github-light","dark":"github-dark-dimmed"}}
  from braintrust import Reporter

  def report_eval(evaluator, result, opts):
      # Summarizes the results of a single reporter, and return whatever you
      # want (the full results, a piece of text, or both!)
      pass

  def report_run(results):
      # Takes all the results and summarizes them. Return a true or false
      # which tells the process to exit.
      return True

  Reporter(
      "My reporter",  # Replace with your reporter name
      report_eval=report_eval,
      report_run=report_run,
  )
  ```
</CodeGroup>

Any `Reporter` included among your evaluated files will be automatically picked up by the `bt eval` CLI command.

* If no reporters are defined, the default reporter logs results to the console.
* If you define one reporter, it's used for all `Eval` blocks.
* If you define multiple `Reporter`s, specify the reporter name as an optional third argument to the eval function.

### Include attachments

Braintrust allows you to log binary data like images, audio, and PDFs as [attachments](/instrument/attachments). Use attachments in evaluations by initializing an `Attachment` object in your data:

<CodeGroup dropdown>
  ```typescript theme={"theme":{"light":"github-light","dark":"github-dark-dimmed"}}
  import { Eval, Attachment } from "braintrust";
  import { NumericDiff } from "autoevals";
  import path from "path";

  function loadPdfs() {
    return ["example.pdf"].map((pdf) => ({
      input: {
        file: new Attachment({
          filename: pdf,
          contentType: "application/pdf",
          data: path.join("files", pdf),
        }),
      },
      // This is a toy example where we check that the file size is what we expect.
      expected: 469513,
    }));
  }

  async function getFileSize(input: { file: Attachment }) {
    return (await input.file.data()).size;
  }

  Eval("Project with PDFs", {
    data: loadPdfs,
    task: getFileSize,
    scores: [NumericDiff],
  });
  ```

  ```python theme={"theme":{"light":"github-light","dark":"github-dark-dimmed"}}
  import os
  from typing import Any, Dict, Iterable

  from autoevals import NumericDiff
  from braintrust import Attachment, Eval, EvalCase

  def load_pdfs() -> Iterable[EvalCase[Dict[str, Any], int]]:
      for filename in ["example.pdf"]:
          yield EvalCase(
              input={
                  "file": Attachment(
                      filename=filename,
                      content_type="application/pdf",
                      # The file on your filesystem or the file's bytes.
                      data=os.path.join("files", filename),
                  )
              },
              # This is a toy example where we check that the file size is what we expect.
              expected=469513,
          )

  def get_file_size(input: Dict[str, Any]) -> int:
      return len(input["file"].data)

  # Our evaluation uses a NumericDiff scorer to check the file size.
  Eval(
      "Project with PDFs",
      data=load_pdfs(),
      task=get_file_size,
      scores=[NumericDiff],
  )
  ```
</CodeGroup>

You can also [store attachments in a dataset](/annotate/datasets#multimodal-datasets) for reuse across multiple experiments. After creating the dataset, reference it by name in an eval. The attachment data is automatically downloaded from Braintrust when accessed:

<CodeGroup dropdown>
  ```typescript theme={"theme":{"light":"github-light","dark":"github-dark-dimmed"}}
  import { NumericDiff } from "autoevals";
  import { initDataset, Eval, ReadonlyAttachment } from "braintrust";

  async function getFileSize(input: {
    file: ReadonlyAttachment;
  }): Promise<number> {
    return (await input.file.data()).size;
  }

  Eval("Project with PDFs", {
    data: initDataset({
      project: "Project with PDFs",
      dataset: "My PDF Dataset",
    }),
    task: getFileSize,
    scores: [NumericDiff],
  });
  ```

  ```python theme={"theme":{"light":"github-light","dark":"github-dark-dimmed"}}
  from autoevals import NumericDiff
  from braintrust import Eval, init_dataset

  def get_file_size(input: Dict[str, Any]) -> int:
      """Download the attachment and get its length."""
      return len(input["file"].data)

  Eval(
      "Project with PDFs",
      data=init_dataset("Project with PDFs", "My PDF Dataset"),
      task=get_file_size,
      scores=[NumericDiff],
  )
  ```
</CodeGroup>

To forward an attachment to an external service like OpenAI, obtain a signed URL instead of downloading the data directly:

<CodeGroup dropdown>
  ```typescript theme={"theme":{"light":"github-light","dark":"github-dark-dimmed"}}
  import { initDataset, wrapOpenAI, ReadonlyAttachment } from "braintrust";
  import { OpenAI } from "openai";

  const client = wrapOpenAI(
    new OpenAI({
      apiKey: process.env.OPENAI_API_KEY,
    }),
  );

  async function main() {
    const dataset = initDataset({
      project: "Project with images",
      dataset: "My Image Dataset",
    });
    for await (const row of dataset) {
      const attachment: ReadonlyAttachment = row.input.file;
      const attachmentUrl = (await attachment.metadata()).downloadUrl;
      const response = await client.chat.completions.create({
        model: "gpt-5-mini",
        messages: [
          {
            role: "system",
            content: "You are a helpful assistant",
          },
          {
            role: "user",
            content: [
              { type: "text", text: "Please summarize the attached image" },
              { type: "image_url", image_url: { url: attachmentUrl } },
            ],
          },
        ],
      });
      const summary = response.choices[0].message.content || "Unknown";
      console.log(
        `Summary for file ${attachment.reference.filename}: ${summary}`,
      );
    }
  }

  main();
  ```

  ```python theme={"theme":{"light":"github-light","dark":"github-dark-dimmed"}}
  from braintrust import init_dataset, wrap_openai
  from openai import OpenAI

  openai = wrap_openai(OpenAI(api_key=os.environ["OPENAI_API_KEY"]))

  def main():
      dataset = init_dataset("Project with images", "My Image Dataset")
      for row in dataset:
          attachment = row["input"]["file"]
          attachment_url = attachment.metadata()["downloadUrl"]
          response = openai.chat.completions.create(
              model="gpt-5-mini",
              messages=[
                  {"role": "system", "content": "You are a helpful assistant"},
                  {
                      "role": "user",
                      "content": [
                          {"type": "text", "text": "Please summarize the attached image"},
                          {"type": "image_url", "image_url": {"url": attachment_url}},
                      ],
                  },
              ],
          )
          summary = response.choices[0].message.content or "Unknown"
          print(f"Summary for file {attachment.reference['filename']}: {summary}")

  main()
  ```
</CodeGroup>

### Trace your evals

Add detailed tracing to your evaluation task functions to measure performance and debug issues. Each span in the trace represents an operation like an LLM call, database lookup, or API request.

<Note>
  Use `wrapOpenAI`/`wrap_openai` to automatically trace OpenAI API calls. See [Trace LLM calls](/instrument/trace-llm-calls#wrap-functions) for details.
</Note>

<Warning>
  Each call to `experiment.log()` creates its own trace. Do not mix `experiment.log()` with tracing functions like `traced()` - this creates incorrectly parented traces.
</Warning>

Wrap task code with `traced()` to log incrementally to spans. This example progressively logs input, output, and metrics:

<CodeGroup dropdown>
  ```typescript theme={"theme":{"light":"github-light","dark":"github-dark-dimmed"}}
  import { Eval, traced } from "braintrust";

  async function callModel(input: string) {
    return traced(
      async (span) => {
        const messages = { messages: [{ role: "system", text: input }] };
        span.log({ input: messages });

        // Replace this with a model call
        const result = {
          content: "China",
          latency: 1,
          prompt_tokens: 10,
          completion_tokens: 2,
        };

        span.log({
          output: result.content,
          metrics: {
            latency: result.latency,
            prompt_tokens: result.prompt_tokens,
            completion_tokens: result.completion_tokens,
          },
        });
        return result.content;
      },
      {
        name: "My AI model",
      },
    );
  }

  const exactMatch = (args: {
    input: string;
    output: string;
    expected?: string;
  }) => {
    return {
      name: "Exact match",
      score: args.output === args.expected ? 1 : 0,
    };
  };

  Eval("My Evaluation", {
    data: () => [
      { input: "Which country has the highest population?", expected: "China" },
    ],
    task: async (input, { span }) => {
      return await callModel(input);
    },
    scores: [exactMatch],
  });
  ```

  ```python theme={"theme":{"light":"github-light","dark":"github-dark-dimmed"}}
  from braintrust import Eval, current_span, traced

  @traced
  async def call_model(input):
      messages = dict(
          messages=[
              dict(role="system", text=input),
          ]
      )
      current_span().log(input=messages)

      # Replace this with a model call
      result = {
          "content": "China",
          "latency": 1,
          "prompt_tokens": 10,
          "completion_tokens": 2,
      }
      current_span().log(
          output=result["content"],
          metrics=dict(
              latency=result["latency"],
              prompt_tokens=result["prompt_tokens"],
              completion_tokens=result["completion_tokens"],
          ),
      )
      return result["content"]

  async def run_input(input):
      return await call_model(input)

  def exact_match(input, expected, output):
      return 1 if output == expected else 0

  Eval(
      "My Evaluation",
      data=[dict(input="Which country has the highest population?", expected="China")],
      task=run_input,
      scores=[exact_match],
  )
  ```
</CodeGroup>

This creates a span tree you can visualize in the UI by clicking on each test case in the experiment.

## Troubleshooting

<AccordionGroup>
  <Accordion title="Evaluations running slowly with maxConcurrency?">
    If your evaluations are slower than expected when using `maxConcurrency`, you may be on an older SDK version that flushes logs after every single task completion. Upgrade to TypeScript SDK v3.3.0+ for up to an 8x performance improvement. The SDK now uses byte-based backpressure for better flushing performance.

    You can tune the flush threshold with the `BRAINTRUST_FLUSH_BACKPRESSURE_BYTES` environment variable. See [Tune performance](/instrument/advanced-tracing#tune-performance) for all available configuration options.
  </Accordion>

  <Accordion title="Task function throws an exception during eval (C# SDK v0.2.2+)">
    When the task function throws, the C# eval framework catches the exception, records it on the task span and root span (with `ActivityStatusCode.Error`), and calls `ScoreForTaskException` on every scorer instead of `Score`. The eval continues — no cases are skipped.

    By default, `ScoreForTaskException` returns a single score of `0.0`. Override it on your `IScorer` to return a custom fallback score, return an empty list to omit scoring for that case, or re-throw to abort the eval.

    ```csharp #skip-compile theme={"theme":{"light":"github-light","dark":"github-dark-dimmed"}}
    using Braintrust.Sdk.Eval;

    sealed class MyScorer : IScorer<string, string>
    {
        public string Name => "my_scorer";

        public Task<IReadOnlyList<Score>> Score(TaskResult<string, string> taskResult)
        {
            var matches = taskResult.Result == taskResult.DatasetCase.Expected;
            return Task.FromResult<IReadOnlyList<Score>>([new Score(Name, matches ? 1.0 : 0.0)]);
        }

        // Called instead of Score() when the task function threw.
        // Return [] to skip recording a score; throw to abort the eval.
        public Task<IReadOnlyList<Score>> ScoreForTaskException(
            Exception taskException,
            DatasetCase<string, string> datasetCase)
        {
            // Distinguish between expected and unexpected failures
            if (taskException is TimeoutException)
                return Task.FromResult<IReadOnlyList<Score>>([new Score(Name, 0.0)]);

            return Task.FromResult<IReadOnlyList<Score>>([]); // skip scoring
        }
    }
    ```

    The task span and root eval span both receive an OTel exception event with `exception.type`, `exception.message`, and `exception.stacktrace` attributes, visible in any OTel-compatible backend connected to Braintrust.
  </Accordion>

  <Accordion title="Scorer throws an exception during eval (C# SDK v0.2.2+)">
    When a scorer's `Score` method throws, the exception is recorded on that scorer's span (with `ActivityStatusCode.Error` and an OTel exception event) and `ScoreForScorerException` is called as a fallback. Other scorers continue running unaffected.

    By default, `ScoreForScorerException` returns a single score of `0.0`. Override it to return a custom fallback, return an empty list to omit the score, or re-throw to abort the eval.

    ```csharp #skip-compile theme={"theme":{"light":"github-light","dark":"github-dark-dimmed"}}
    using Braintrust.Sdk.Eval;

    sealed class MyScorer : IScorer<string, string>
    {
        public string Name => "my_scorer";

        public Task<IReadOnlyList<Score>> Score(TaskResult<string, string> taskResult)
        {
            // ... scoring logic that might throw
            throw new InvalidOperationException("unexpected output format");
        }

        // Called when Score() throws. Other scorers are not affected.
        // Return [] to skip recording a score; throw to abort the eval.
        public Task<IReadOnlyList<Score>> ScoreForScorerException(
            Exception scorerException,
            TaskResult<string, string> taskResult)
        {
            return Task.FromResult<IReadOnlyList<Score>>([new Score(Name, 0.0)]);
        }
    }
    ```

    Score spans are named `score:<scorer_name>` (e.g. `score:my_scorer`), making individual scorer traces distinguishable in Braintrust and any connected OTel backend.
  </Accordion>
</AccordionGroup>

## Next steps

* [Interpret results](/evaluate/interpret-results) from your experiments
* [Compare experiments](/evaluate/compare-experiments) to measure improvements
* [Test complex agents](/evaluate/remote-evals) to connect custom code to the playground
* [Write scorers](/evaluate/write-scorers) to measure quality
