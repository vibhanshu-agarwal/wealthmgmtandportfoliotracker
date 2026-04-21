> ## Documentation Index
> Fetch the complete documentation index at: https://braintrust.dev/docs/llms.txt
> Use this file to discover all available pages before exploring further.

# Test complex agents with remote evals and sandboxes

> Connect custom agent code to a playground

When your task can't be expressed as a prompt (agents, multi-step workflows, custom tooling, or heavy dependencies), connect your code to a playground. The iteration workflow stays the same: run evaluations, compare results side-by-side, and share with teammates. Your code handles task execution. The playground handles the rest.

Two approaches differ in where your code runs:

* **Remote evals** — Run evals on your own infrastructure, controlled from Braintrust. Your evaluation code runs on your machine or server. The Braintrust playground triggers execution, sends parameters, and displays results.

* **Sandboxes** — Run evals in an isolated cloud sandbox, controlled from Braintrust. You push an execution artifact (a code bundle or container snapshot) and Braintrust invokes it on demand from the playground. No server to keep running.

  <Note>
    Sandboxes are in beta and require a [Pro or Enterprise plan](/plans-and-limits). Self-hosted deployments require data plane version v2.0.
  </Note>

## Common use cases

<Tabs>
  <Tab title="Remote evals">
    <AccordionGroup>
      <Accordion title="Private internal resources">
        Your eval needs to call internal APIs, query private databases, or access services inside your VPN. Because remote evals execute on your infrastructure, that access is already available.
      </Accordion>

      <Accordion title="OS-specific or platform-locked tooling">
        Your eval requires software that only runs on a specific OS or machine — for example, a Windows-only simulation or a Unity project on a dedicated workstation. Remote evals let Braintrust trigger execution on whichever machine has the right environment set up.
      </Accordion>

      <Accordion title="Heavy or complex dev setup">
        Some tools are too painful to install on every teammate's machine — game engines, large models, specialized SDKs. Set up the environment once on a shared server and let everyone else run the eval from the playground.
      </Accordion>

      <Accordion title="Data security and compliance">
        Sensitive data stays on your infrastructure. Only results are sent to Braintrust.
      </Accordion>
    </AccordionGroup>
  </Tab>

  <Tab title="Sandboxes">
    <AccordionGroup>
      <Accordion title="No server to maintain">
        Push your eval once and it's always available from the playground — without keeping a process alive or worrying about uptime. This works well for stable eval versions the whole team can run on demand.
      </Accordion>

      <Accordion title="Team sharing without dev setup">
        An engineer packages the eval and pushes it. Teammates run it from the playground without cloning the repo, installing dependencies, or knowing anything about the execution environment.
      </Accordion>

      <Accordion title="Custom Python or TypeScript environments">
        Include pip packages with `--requirements` (Lambda) or bring your own container image (Modal) for full control over the runtime environment.
      </Accordion>

      <Accordion title="Reproducible, isolated runs">
        Each run executes against the same packaged artifact — same bundle or container snapshot — so results are consistent across teammates and over time.
      </Accordion>
    </AccordionGroup>
  </Tab>
</Tabs>

## Run a remote eval

Run evals on your own infrastructure, controlled from Braintrust. Your evaluation code runs on your machine or server. The Braintrust playground triggers execution, sends parameters, and displays results.

### 1. Write your eval

A remote eval looks like a standard eval call with a `parameters` field that defines configurable options. These parameters become UI controls in the playground.

Install the SDK and dependencies:

<CodeGroup>
  ```bash TypeScript theme={"theme":{"light":"github-light","dark":"github-dark-dimmed"}}
  # pnpm
  pnpm add braintrust openai autoevals
  # npm
  npm install braintrust openai autoevals
  ```

  ```bash Python theme={"theme":{"light":"github-light","dark":"github-dark-dimmed"}}
  pip install braintrust openai autoevals
  ```

  ```bash Ruby theme={"theme":{"light":"github-light","dark":"github-dark-dimmed"}}
  # Add to your Gemfile:
  gem "braintrust"
  gem "openai"

  bundle install
  ```

  ```bash Java theme={"theme":{"light":"github-light","dark":"github-dark-dimmed"}}
  # Add to build.gradle dependencies{} block:
  implementation 'dev.braintrust:braintrust-sdk-java:<version>'
  implementation 'com.openai:openai-java-sdk:<version>'
  ```
</CodeGroup>

Create the eval code:

<CodeGroup>
  ```typescript my_eval.eval.ts theme={"theme":{"light":"github-light","dark":"github-dark-dimmed"}}
  import { Eval, wrapOpenAI } from "braintrust";
  import OpenAI from "openai";
  import { z } from "zod";

  const client = wrapOpenAI(new OpenAI());

  Eval("my-project", {
    data: [{ input: "hello", expected: "HELLO" }],
    task: async (input, { parameters }) => {
      const completion = await client.chat.completions.create(
        parameters.main.build({ input }),
      );
      return completion.choices[0].message.content ?? "";
    },
    scores: [],
    parameters: {
      main: {
        type: "prompt",
        name: "Main prompt",
        description: "The prompt used to process input",
        default: {
          messages: [{ role: "user", content: "{{input}}" }],
          model: "gpt-5-mini",
        },
      },
      prefix: z.string().describe("Optional prefix to prepend to input").default(""),
    },
  });
  ```

  ```python my_eval.py theme={"theme":{"light":"github-light","dark":"github-dark-dimmed"}}
  import openai
  from autoevals import Levenshtein
  from braintrust import Eval, init_dataset, wrap_openai
  from pydantic import BaseModel, Field

  client = wrap_openai(openai.AsyncOpenAI())


  class PrefixParam(BaseModel):
      """Pydantic model for the prefix parameter. In Python, non-prompt parameters
      must be defined as Pydantic models (not dicts) to appear in the UI."""

      value: str = Field(default="", description="Optional prefix to prepend to input")


  async def task(input, hooks):
      parameters = hooks.parameters

      prefix = parameters.get("prefix", "")
      prompt_input = f"{prefix}: {input}" if prefix else input

      completion = await client.chat.completions.create(
          **parameters["main"].build(input=prompt_input)
      )

      return completion.choices[0].message.content or ""


  Eval(
      "my-project",
      data=init_dataset("my-project", "my-dataset"),
      task=task,
      scores=[Levenshtein],
      parameters={
          "main": {
              "type": "prompt",
              "name": "Main prompt",
              "description": "The prompt used to process input",
              "default": {
                  "prompt": {
                      "type": "chat",
                      "messages": [{"role": "user", "content": "{{input}}"}],
                  },
                  "options": {"model": "gpt-5-mini"},
              },
          },
          "prefix": PrefixParam,
      },
  )
  ```

  ```ruby eval_server.ru theme={"theme":{"light":"github-light","dark":"github-dark-dimmed"}}
  # Requires Braintrust Ruby SDK v0.2.1+
  require "braintrust"
  require "braintrust/server"
  require "openai"

  Braintrust.init(blocking_login: true)
  Braintrust.instrument!(:openai)

  client = OpenAI::Client.new(api_key: ENV.fetch("OPENAI_API_KEY"))

  simple_eval = Braintrust::Eval::Evaluator.new(
    task: ->(input:) {
      response = client.chat.completions.create(
        model: "gpt-5-mini",
        messages: [{role: "user", content: input}]
      )
      response.choices.first.message.content
    },
    scorers: [
      Braintrust::Scorer.new("exact_match") { |expected:, output:| output == expected ? 1.0 : 0.0 }
    ],
    parameters: {
      prefix: {type: "string", description: "Optional prefix to prepend to input", default: ""}
    }
  )

  run Braintrust::Server::Rack.app(
    evaluators: {"simple-eval" => simple_eval}
  )
  ```

  ```java SimpleRemoteEval.java theme={"theme":{"light":"github-light","dark":"github-dark-dimmed"}}
  // Requires Braintrust Java SDK v0.2.1+
  import com.openai.client.OpenAIClient;
  import com.openai.client.okhttp.OpenAIOkHttpClient;
  import com.openai.models.ChatModel;
  import com.openai.models.chat.completions.ChatCompletionCreateParams;
  import dev.braintrust.Braintrust;
  import dev.braintrust.devserver.Devserver;
  import dev.braintrust.devserver.RemoteEval;
  import dev.braintrust.eval.Scorer;
  import dev.braintrust.instrumentation.openai.BraintrustOpenAI;
  import java.util.List;

  class SimpleRemoteEval {
      public static void main(String[] args) throws Exception {
          var braintrust = Braintrust.get();
          var openTelemetry = braintrust.openTelemetryCreate();
          OpenAIClient client = BraintrustOpenAI.wrapOpenAI(openTelemetry, OpenAIOkHttpClient.fromEnv());

          dev.braintrust.devserver.RemoteEval<String, String> eval =
                  dev.braintrust.devserver.RemoteEval.<String, String>builder()
                          .name("Simple eval")
                          .taskFunction(
                                  input -> {
                                      var request =
                                              ChatCompletionCreateParams.builder()
                                                      .model(ChatModel.GPT_4O)
                                                      .addUserMessage(input)
                                                      .build();

                                      var response = client.chat().completions().create(request);
                                      return response.choices()
                                              .get(0)
                                              .message()
                                              .content()
                                              .orElse("");
                                  })
                          .scorers(
                                  List.of(
                                          Scorer.of(
                                                  "accuracy",
                                                  (expected, output) ->
                                                          output.equals(expected) ? 1.0 : 0.0)))
                          .build();

          Devserver devserver =
                  Devserver.builder()
                          .config(braintrust.config())
                          .registerEval(eval)
                          .host("localhost")
                          .port(8300)
                          .build();

          Runtime.getRuntime()
                  .addShutdownHook(
                          new Thread(
                                  () -> {
                                      System.out.println("Shutting down...");
                                      devserver.stop();
                                  }));

          System.out.println("Starting Braintrust dev server on http://localhost:8300");
          devserver.start();
      }
  }
  ```
</CodeGroup>

The parameter system uses different syntax across languages:

| Feature               | TypeScript                                                                | Python                                                       | Java                                                         | Ruby                                          |
| --------------------- | ------------------------------------------------------------------------- | ------------------------------------------------------------ | ------------------------------------------------------------ | --------------------------------------------- |
| **Prompt parameters** | `type: "prompt"` with `messages` array in `default`                       | `type: "prompt"` with nested `prompt.messages` and `options` | `type: "prompt"` with nested `prompt.messages` and `options` | Not supported                                 |
| **Scalar types**      | Zod schemas: `z.string()`, `z.boolean()`, `z.number()` with `.describe()` | Pydantic models with `Field(description=...)`                | `Map` with `type`, `description`, `default`                  | Hash with `type:`, `description:`, `default:` |
| **Parameter access**  | `parameters.prefix`                                                       | `parameters.get("prefix")`                                   | `parameters.get("prefix")`                                   | `parameters["prefix"]` (via keyword arg)      |
| **Prompt usage**      | `parameters.main.build({ input: value })`                                 | `**parameters["main"].build(input=value)`                    | `parameters.get("main").build(Map.of("input", value))`       | Not applicable                                |
| **Async**             | `async`/`await`                                                           | `async`/`await`                                              | Synchronous or `CompletableFuture`                           | Synchronous                                   |

<Tip>
  To reference saved parameter configurations instead of defining them inline, use `loadParameters()` (TypeScript) or `load_parameters()` (Python). See [Parameters](/evaluate/write-parameters) for details.
</Tip>

### 2. Expose the eval server

Run your eval with the [`bt eval`](/reference/cli/eval) `--dev` flag to start a local server:

<Tabs>
  <Tab title="TypeScript">
    ```bash theme={"theme":{"light":"github-light","dark":"github-dark-dimmed"}}
    bt eval path/to/eval.ts --dev
    ```

    Dev server starts at `http://localhost:8300`. Configure the host and port:

    * `--dev-host DEV_HOST`: The host to bind to. Defaults to `localhost`. Set to `0.0.0.0` to bind to all interfaces (be cautious about security when exposing beyond localhost).
    * `--dev-port DEV_PORT`: The port to bind to. Defaults to `8300`.
  </Tab>

  <Tab title="Python">
    ```bash theme={"theme":{"light":"github-light","dark":"github-dark-dimmed"}}
    bt eval path/to/eval.py --dev
    ```

    Dev server starts at `http://localhost:8300`. Configure the host and port:

    * `--dev-host DEV_HOST`: The host to bind to. Defaults to `localhost`. Set to `0.0.0.0` to bind to all interfaces (be cautious about security when exposing beyond localhost).
    * `--dev-port DEV_PORT`: The port to bind to. Defaults to `8300`.
  </Tab>

  <Tab title="Java">
    The Java SDK does not have a CLI command. Start the dev server programmatically using `Devserver.builder()...build()` followed by `devserver.start()`, as shown in the code example above.
  </Tab>

  <Tab title="Ruby">
    #### Run as a Rack app

    The dev server requires a Rack-compatible web server that supports streaming:

    | Server                                         | Version                                    |
    | ---------------------------------------------- | ------------------------------------------ |
    | [Puma](https://puma.io/) (recommended)         | 6.x                                        |
    | [Falcon](https://socketry.github.io/falcon/)   | 0.x                                        |
    | [Passenger](https://www.phusionpassenger.com/) | 6.x                                        |
    | [WEBrick](https://github.com/ruby/webrick)     | Not supported — does not support streaming |

    Create your eval server file:

    ```ruby eval_server.ru theme={"theme":{"light":"github-light","dark":"github-dark-dimmed"}}
    # Requires Braintrust Ruby SDK v0.2.1+
    require "braintrust"
    require "braintrust/server"
    require "openai"

    Braintrust.init(blocking_login: true)
    Braintrust.instrument!(:openai)

    client = OpenAI::Client.new(api_key: ENV.fetch("OPENAI_API_KEY"))

    simple_eval = Braintrust::Eval::Evaluator.new(
      task: ->(input:) {
        response = client.chat.completions.create(
          model: "gpt-5-mini",
          messages: [{role: "user", content: input}]
        )
        response.choices.first.message.content
      },
      scorers: [
        Braintrust::Scorer.new("exact_match") { |expected:, output:| output == expected ? 1.0 : 0.0 }
      ]
    )

    run Braintrust::Server::Rack.app(
      evaluators: {"simple-eval" => simple_eval}
    )
    ```

    Add dependencies and start the server:

    ```ruby theme={"theme":{"light":"github-light","dark":"github-dark-dimmed"}}
    # Gemfile
    gem "rack"
    gem "puma"
    ```

    ```bash theme={"theme":{"light":"github-light","dark":"github-dark-dimmed"}}
    bundle install
    bundle exec rackup eval_server.ru -p 8300 -o 0.0.0.0
    ```

    #### Run as a Rails engine

    If you have an existing Rails application, you can mount the Braintrust eval server as a Rails engine instead of running a separate Rack process.

    1. Requires Rails 8.x. Add to your Gemfile:

       ```ruby theme={"theme":{"light":"github-light","dark":"github-dark-dimmed"}}
       gem "actionpack", "~> 8.0"
       gem "railties", "~> 8.0"
       gem "activesupport", "~> 8.0"
       ```

    2. Place evaluator classes under `app/evaluators/` as subclasses of `Braintrust::Eval::Evaluator`:

       ```ruby theme={"theme":{"light":"github-light","dark":"github-dark-dimmed"}}
       # app/evaluators/food_classifier.rb
       class FoodClassifier < Braintrust::Eval::Evaluator
         def task
           ->(input:) { classify(input) }
         end

         def scorers
           [Braintrust::Scorer.new("exact_match") { |expected:, output:| output == expected ? 1.0 : 0.0 }]
         end
       end
       ```

    3. Generate the initializer:

       ```bash theme={"theme":{"light":"github-light","dark":"github-dark-dimmed"}}
       bin/rails generate braintrust:server
       ```

       This creates `config/initializers/braintrust_server.rb` with a slug-to-evaluator mapping auto-discovered from `app/evaluators/`.

    4. Mount the engine:

       ```ruby theme={"theme":{"light":"github-light","dark":"github-dark-dimmed"}}
       # config/routes.rb
       Rails.application.routes.draw do
         mount Braintrust::Contrib::Rails::Server::Engine, at: "/braintrust"
       end
       ```

    **Auth configuration**

    The engine defaults to `:clerk_token` authentication. For local development, set auth to `:none` in the generated initializer:

    ```ruby theme={"theme":{"light":"github-light","dark":"github-dark-dimmed"}}
    # config/initializers/braintrust_server.rb
    Braintrust::Contrib::Rails::Server::Engine.configure do |config|
      config.auth = :none
    end
    ```

    <Warning>
      `auth: :none` disables authentication on incoming requests. Only use this for local development. `BRAINTRUST_API_KEY` must still be set on the server — it's required to fetch resources from your project.
    </Warning>
  </Tab>
</Tabs>

### 3. Configure in your project

To make your eval accessible beyond localhost, add the endpoint to your project:

1. In your project, go to **<Icon icon="settings-2" /> Settings**.
2. Under **Project**, select **<Icon icon="unplug" /> Remote evals**.
3. Select **<Icon icon="plus" /> Create remote eval source**.
4. Enter the name and URL of your remote eval server.
5. Select **Create remote eval source**.

All team members with access to the project can now use this remote eval in their playgrounds. Keep the process running while using the remote eval.

### 4. Run from a playground

1. Open a playground in your project.
2. Select **+ Task**.
3. Choose **Remote eval** from the task type list.
4. Select your eval and configure parameters using the UI controls.
5. Provide data inline or select a dataset, optionally add scorers, and click **Run**.

Results stream back as the eval executes. You can run multiple instances side-by-side with different parameters to compare results.

### Demo

<video controls className="w-full aspect-video rounded-xl" poster="/docs/images/guides/remote-evals/remote-evals-tutorial.png" src="https://mintcdn.com/braintrust/ra--46HEM6v2rXpA/images/guides/remote-evals/remote-evals-tutorial.mp4?fit=max&auto=format&n=ra--46HEM6v2rXpA&q=85&s=612eb61cd9634ba9263c5a7b7ffb54e0" data-path="images/guides/remote-evals/remote-evals-tutorial.mp4" />

## Run a sandbox eval

<Note>
  Sandboxes are in beta and require a [Pro or Enterprise plan](/plans-and-limits). Self-hosted deployments require data plane version v2.0.
</Note>

Run evals in an isolated cloud sandbox, controlled from Braintrust. Push an execution artifact once and Braintrust invokes it on demand from the playground — no server to keep running.

Braintrust supports two sandbox providers:

* **Lambda** — AWS Lambda-based. The default for `braintrust push`. Supports both Python and TypeScript. No extra configuration needed.
* **Modal** — Container-based via [Modal](https://modal.com). Requires a snapshotted Modal container image. Executes TypeScript evals only.

### 1. Write your eval

A sandbox eval looks like a standard eval call with a `parameters` field that defines configurable options. These parameters become UI controls in the playground.

Install the SDK and dependencies:

<CodeGroup>
  ```bash TypeScript theme={"theme":{"light":"github-light","dark":"github-dark-dimmed"}}
  # pnpm
  pnpm add braintrust openai autoevals
  # npm
  npm install braintrust openai autoevals
  ```

  ```bash Python theme={"theme":{"light":"github-light","dark":"github-dark-dimmed"}}
  pip install braintrust openai autoevals
  ```
</CodeGroup>

<Note>
  Sandboxes require TypeScript SDK v3.7.1+ or Python SDK v0.12.1+.
</Note>

Create the eval code:

<CodeGroup dropdown>
  ```typescript my_eval.eval.ts theme={"theme":{"light":"github-light","dark":"github-dark-dimmed"}}
  import { Eval, wrapOpenAI } from "braintrust";
  import OpenAI from "openai";
  import { z } from "zod";

  const client = wrapOpenAI(new OpenAI());

  Eval("my-project", {
    data: [{ input: "hello", expected: "HELLO" }],
    task: async (input, { parameters }) => {
      const completion = await client.chat.completions.create(
        parameters.main.build({ input }),
      );
      return completion.choices[0].message.content ?? "";
    },
    scores: [],
    parameters: {
      main: {
        type: "prompt",
        name: "Main prompt",
        description: "The prompt used to process input",
        default: {
          messages: [{ role: "user", content: "{{input}}" }],
          model: "gpt-5-mini",
        },
      },
      prefix: z.string().describe("Optional prefix to prepend to input").default(""),
    },
  });
  ```

  ```python my_eval.py theme={"theme":{"light":"github-light","dark":"github-dark-dimmed"}}
  import openai
  from autoevals import Levenshtein
  from braintrust import Eval, init_dataset, wrap_openai
  from pydantic import BaseModel, Field

  client = wrap_openai(openai.AsyncOpenAI())


  class PrefixParam(BaseModel):
      value: str = Field(default="", description="Optional prefix to prepend to input")


  async def task(input, hooks):
      parameters = hooks.parameters

      prefix = parameters.get("prefix", "")
      prompt_input = f"{prefix}: {input}" if prefix else input

      completion = await client.chat.completions.create(
          **parameters["main"].build(input=prompt_input)
      )

      return completion.choices[0].message.content or ""


  Eval(
      "my-project",
      data=init_dataset("my-project", "my-dataset"),
      task=task,
      scores=[Levenshtein],
      parameters={
          "main": {
              "type": "prompt",
              "name": "Main prompt",
              "description": "The prompt used to process input",
              "default": {
                  "prompt": {
                      "type": "chat",
                      "messages": [{"role": "user", "content": "{{input}}"}],
                  },
                  "options": {"model": "gpt-5-mini"},
              },
          },
          "prefix": PrefixParam,
      },
  )
  ```
</CodeGroup>

The parameter system uses different syntax across languages:

| Feature               | TypeScript                                                                | Python                                                       |
| --------------------- | ------------------------------------------------------------------------- | ------------------------------------------------------------ |
| **Prompt parameters** | `type: "prompt"` with `messages` array in `default`                       | `type: "prompt"` with nested `prompt.messages` and `options` |
| **Scalar types**      | Zod schemas: `z.string()`, `z.boolean()`, `z.number()` with `.describe()` | Pydantic models with `Field(description=...)`                |
| **Parameter access**  | `parameters.prefix`                                                       | `parameters.get("prefix")`                                   |
| **Prompt usage**      | `parameters.main.build({ input: value })`                                 | `**parameters["main"].build(input=value)`                    |
| **Async**             | `async`/`await`                                                           | `async`/`await`                                              |

<Tip>
  To reference saved parameter configurations instead of defining them inline, use `loadParameters()` (TypeScript) or `load_parameters()` (Python). See [Parameters](/evaluate/write-parameters) for details.
</Tip>

### 2. Register your sandbox

<Note>
  Sandbox registration uses the Braintrust SDK CLI (`braintrust push` / `npx braintrust push`). The `bt` CLI does not yet support sandbox evals.
</Note>

<Tabs>
  <Tab title="Lambda">
    ```bash theme={"theme":{"light":"github-light","dark":"github-dark-dimmed"}}
    braintrust push my_eval.py           # Python
    npx braintrust push my_eval.eval.ts  # TypeScript
    ```

    To include pip dependencies:

    ```bash theme={"theme":{"light":"github-light","dark":"github-dark-dimmed"}}
    braintrust push my_eval.py --requirements requirements.txt
    ```

    To run locally and register the sandbox in one step (TypeScript):

    ```bash theme={"theme":{"light":"github-light","dark":"github-dark-dimmed"}}
    npx braintrust eval my_eval.eval.ts --push
    ```

    To update an existing sandbox:

    ```bash theme={"theme":{"light":"github-light","dark":"github-dark-dimmed"}}
    braintrust push my_eval.py --if-exists replace           # Python
    npx braintrust push my_eval.eval.ts --if-exists replace  # TypeScript
    ```
  </Tab>

  <Tab title="Modal">
    Modal sandboxes run your eval in a custom container image. The container must include Node.js and your eval code.

    1. Add your Modal credentials under **<Icon icon="settings-2" /> Settings** > **<Icon icon="organization" /> Organization** > **<Icon icon="container" /> Sandbox providers**.

    2. Build and snapshot the container:

       ```python theme={"theme":{"light":"github-light","dark":"github-dark-dimmed"}}
       import modal

       app = modal.App.lookup("my-braintrust-sandbox", create_if_missing=True)
       image = modal.Image.from_dockerfile("./Dockerfile")

       sb = modal.Sandbox.create(app=app, image=image, workdir="/app", timeout=60 * 5)
       snapshot_image = sb.snapshot_filesystem()
       snapshot_ref = snapshot_image.object_id  # e.g. "im-icRxmsk1Sz9XPP2f8OblVU"
       sb.terminate()
       ```

       The `object_id` returned by `snapshot_filesystem()` is your `snapshot_ref`.

    3. Register your sandbox:

           <CodeGroup>
             ```typescript TypeScript theme={"theme":{"light":"github-light","dark":"github-dark-dimmed"}}
             import { registerSandbox } from "braintrust";

             const result = await registerSandbox({
               name: "My Eval Sandbox",
               project: "my-project",
               sandbox: { provider: "modal", snapshotRef: "im-icRxmsk1Sz9XPP2f8OblVU" },
               entrypoints: ["./my_eval.eval.ts"],
             });
             ```

             ```python Python theme={"theme":{"light":"github-light","dark":"github-dark-dimmed"}}
             from braintrust import register_sandbox, SandboxConfig

             result = register_sandbox(
                 name="My Eval Sandbox",
                 project="my-project",
                 sandbox=SandboxConfig(provider="modal", snapshot_ref="im-icRxmsk1Sz9XPP2f8OblVU"),
                 entrypoints=["./my_eval.eval.ts"],
             )
             ```
           </CodeGroup>

       `entrypoints` lists the eval files available in the snapshot. Re-registering with a new `snapshot_ref` updates the sandbox in place.
  </Tab>
</Tabs>

### 3. Run from a playground

1. Open a playground in your project.
2. Select **+ Task**.
3. Open the **Remote eval** submenu and select your sandbox.
4. Select your eval and configure parameters using the UI controls.
5. Provide data inline or select a dataset, optionally add scorers, and click **Run**.

Results stream back as the eval executes. You can run multiple instances side-by-side with different parameters to compare results.

## Limitations

* The dataset defined in your eval is ignored when running from the playground. Datasets are managed through the playground.
* Scorers defined in your eval are concatenated with scorers added in the playground.
* For sandboxes, each eval run triggered from the playground is capped at 15 minutes end-to-end.

## Next steps

* [Test prompts and models](/evaluate/playgrounds) without custom code
* [Create parameters](/evaluate/write-parameters) to manage configurable settings
* [Interpret results](/evaluate/interpret-results) from your experiments
