> ## Documentation Index
> Fetch the complete documentation index at: https://braintrust.dev/docs/llms.txt
> Use this file to discover all available pages before exploring further.

# Build datasets

> Create, manage, and version test cases for systematic evaluation

Datasets are versioned collections of test cases that you use to run evaluations and track improvements over time. Build datasets from production logs, user feedback, manual curation, or generate them with Loop.

Key advantages:

* **Versioned**: Every change is tracked, so experiments can pin to specific versions
* **Integrated**: Use directly in evaluations and populate from production
* **Scalable**: Stored in a modern data warehouse without storage limits

## Dataset structure

Each record has four top-level fields:

* **input**: Data to recreate the example in your application (required).
* **expected**: Ideal output or ground truth (optional but recommended for evaluation).
* **metadata**: Key-value pairs for filtering and grouping (optional).
* **tags**: Labels for organizing and filtering records (optional).

## Create datasets

### Upload CSV/JSON

The fastest way to create a dataset is uploading a CSV or JSON file:

1. Go to <Icon icon="database" /> **Datasets**.
2. If there are existing datasets, click **+ Dataset**. Otherwise, click <Icon icon="upload" /> **Upload CSV/JSON**.
3. Drag and drop your file in the **Upload dataset** dialog.
4. Columns automatically map to the `input` field. Drag and drop them into different categories as needed:

   * **Input**: Fields used as inputs for your task.
   * **Expected**: Ground truth or ideal outputs for scoring.
   * **Metadata**: Additional context for filtering and grouping.
   * **Tags**: Labels for organizing and filtering individual records. When you categorize columns as tags, they're automatically added to your project's [tag configuration](/admin/projects#add-tags). These are per-record tags, distinct from [dataset-level tags](#tag-and-star-datasets) that organize datasets in the list.
   * **Do not import**: Exclude columns from the dataset.

   The preview table updates in real-time as you move columns between categories, showing exactly how your dataset will be structured.
5. Click **Import**.

<Note>
  If your data includes an `id` field, duplicate rows will be deduplicated, with only the last occurrence of each ID kept.
</Note>

### Create via SDK

Create datasets programmatically and populate them with records. The approach varies by language:

* **TypeScript/Python**: Use the high-level `initDataset()` / `init_dataset()` method which automatically creates datasets and provides simple `insert()` operations.
* **Go/Ruby**: Use lower-level API methods that require initializing an API client and explicitly managing dataset creation and record insertion.

<CodeGroup dropdown>
  ```typescript theme={"theme":{"light":"github-light","dark":"github-dark-dimmed"}}
  import { initDataset } from "braintrust";

  async function main() {
    // Initialize dataset (creates it if it doesn't exist)
    const dataset = initDataset("My App", { dataset: "Customer Support" });

    // Insert records with input, expected output, and metadata
    dataset.insert({
      input: { question: "How do I reset my password?" },
      expected: { answer: "Click 'Forgot Password' on the login page." },
      metadata: { category: "authentication", difficulty: "easy" },
    });

    dataset.insert({
      input: { question: "What's your refund policy?" },
      expected: { answer: "Full refunds within 30 days of purchase." },
      metadata: { category: "billing", difficulty: "easy" },
    });

    dataset.insert({
      input: { question: "How do I integrate your API with NextJS?" },
      expected: { answer: "Install the SDK and use our React hooks." },
      metadata: { category: "technical", difficulty: "medium" },
    });

    // Flush to ensure all records are saved
    await dataset.flush();
    console.log("Dataset created with 3 records");
  }

  main();
  ```

  ```python theme={"theme":{"light":"github-light","dark":"github-dark-dimmed"}}
  import braintrust

  # Initialize dataset (creates it if it doesn't exist)
  dataset = braintrust.init_dataset(project="My App", name="Customer Support")

  # Insert records with input, expected output, and metadata
  dataset.insert(
      input={"question": "How do I reset my password?"},
      expected={"answer": "Click 'Forgot Password' on the login page."},
      metadata={"category": "authentication", "difficulty": "easy"},
  )

  dataset.insert(
      input={"question": "What's your refund policy?"},
      expected={"answer": "Full refunds within 30 days of purchase."},
      metadata={"category": "billing", "difficulty": "easy"},
  )

  dataset.insert(
      input={"question": "How do I integrate your API with NextJS?"},
      expected={"answer": "Install the SDK and use our React hooks."},
      metadata={"category": "technical", "difficulty": "medium"},
  )

  # Flush to ensure all records are saved
  dataset.flush()
  print("Dataset created with 3 records")
  ```

  ```go theme={"theme":{"light":"github-light","dark":"github-dark-dimmed"}}
  package main

  import (
  	"context"
  	"fmt"
  	"log"
  	"os"

  	"github.com/braintrustdata/braintrust-sdk-go/api"
  	"github.com/braintrustdata/braintrust-sdk-go/api/datasets"
  	"github.com/braintrustdata/braintrust-sdk-go/api/projects"
  )

  func main() {
  	ctx := context.Background()

  	// Initialize API client
  	client := api.NewClient(os.Getenv("BRAINTRUST_API_KEY"))

  	// Get or create project
  	project, err := client.Projects().Create(ctx, projects.CreateParams{
  		Name: "My App",
  	})
  	if err != nil {
  		log.Fatal(err)
  	}

  	// Create dataset
  	dataset, err := client.Datasets().Create(ctx, datasets.CreateParams{
  		ProjectID: project.ID,
  		Name:      "Customer Support",
  	})
  	if err != nil {
  		log.Fatal(err)
  	}

  	// Insert records with input, expected output, and metadata
  	events := []datasets.Event{
  		{
  			Input: map[string]interface{}{
  				"question": "How do I reset my password?",
  			},
  			Expected: map[string]interface{}{
  				"answer": "Click 'Forgot Password' on the login page.",
  			},
  			Metadata: map[string]interface{}{
  				"category":   "authentication",
  				"difficulty": "easy",
  			},
  		},
  		{
  			Input: map[string]interface{}{
  				"question": "What's your refund policy?",
  			},
  			Expected: map[string]interface{}{
  				"answer": "Full refunds within 30 days of purchase.",
  			},
  			Metadata: map[string]interface{}{
  				"category":   "billing",
  				"difficulty": "easy",
  			},
  		},
  		{
  			Input: map[string]interface{}{
  				"question": "How do I integrate your API with NextJS?",
  			},
  			Expected: map[string]interface{}{
  				"answer": "Install the SDK and use our React hooks.",
  			},
  			Metadata: map[string]interface{}{
  				"category":   "technical",
  				"difficulty": "medium",
  			},
  		},
  	}

  	err = client.Datasets().InsertEvents(ctx, dataset.ID, events)
  	if err != nil {
  		log.Fatal(err)
  	}

  	fmt.Println("Dataset created with 3 records")
  }
  ```

  ```ruby theme={"theme":{"light":"github-light","dark":"github-dark-dimmed"}}
  require "braintrust"

  # Initialize Braintrust
  Braintrust.init
  api = Braintrust::API.new

  # Get or create project
  project = api.projects.list.find { |p| p["name"] == "My App" }
  if project.nil?
    project = api.projects.create(name: "My App")
  end

  # Create dataset
  response = api.datasets.create(
    project_name: "My App",
    name: "Customer Support",
    description: "Customer support Q&A dataset"
  )
  dataset_id = response["dataset"]["id"]

  # Insert records with input, expected output, and metadata
  events = [
    {
      input: {question: "How do I reset my password?"},
      expected: {answer: "Click 'Forgot Password' on the login page."},
      metadata: {category: "authentication", difficulty: "easy"}
    },
    {
      input: {question: "What's your refund policy?"},
      expected: {answer: "Full refunds within 30 days of purchase."},
      metadata: {category: "billing", difficulty: "easy"}
    },
    {
      input: {question: "How do I integrate your API with NextJS?"},
      expected: {answer: "Install the SDK and use our React hooks."},
      metadata: {category: "technical", difficulty: "medium"}
    }
  ]

  api.datasets.insert(id: dataset_id, events: events)

  puts "Dataset created with 3 records"
  ```
</CodeGroup>

### Generate with Loop

Ask Loop to create a dataset based on your logs or specific criteria:

<img src="https://mintcdn.com/braintrust/UtXR-Wt0mdWPIYU5/images/core/loop/generate-dataset-from-logs.png?fit=max&auto=format&n=UtXR-Wt0mdWPIYU5&q=85&s=37336e72e52b9f902fd94930efe6c3b2" alt="Generate dataset from logs" width="2196" height="1440" data-path="images/core/loop/generate-dataset-from-logs.png" />

Example queries:

* "Generate a dataset from the highest-scoring examples in this experiment"
* "Create a dataset with the most common inputs in the logs"

### From user feedback

User feedback from production provides valuable test cases that reflect real user interactions. Use feedback to create datasets from highly-rated examples or problematic cases.

See [Capture user feedback](/instrument/user-feedback) for implementation details on logging feedback programmatically.

To build datasets from feedback:

1. Filter logs by feedback scores using the <Icon icon="list-filter" /> **Filter** menu:
   * `scores.user_rating > 0.8` (SQL) or `filter: scores.user_rating > 0.8` (BTQL) for highly-rated examples
   * `metadata.thumbs_up = false` for negative feedback
   * `comment IS NOT NULL and scores.correctness < 0.5` for low-scoring feedback with comments
2. Select the traces you want to include.
3. Select **Add to dataset**.
4. Choose an existing dataset or create a new one.

You can also ask Loop to create datasets based on feedback patterns, such as "Create a dataset from logs with positive feedback" or "Build a dataset from cases where users clicked thumbs down."

### Log from production

Track user feedback from your application:

<CodeGroup dropdown>
  ```typescript theme={"theme":{"light":"github-light","dark":"github-dark-dimmed"}}
  import { initDataset, Dataset } from "braintrust";

  class MyApplication {
    private dataset: Dataset | undefined = undefined;

    async initApp() {
      this.dataset = await initDataset("My App", { dataset: "logs" });
    }

    async logUserExample(
      input: any,
      expected: any,
      userId: string,
      thumbsUp: boolean,
    ) {
      if (this.dataset) {
        this.dataset.insert({
          input,
          expected,
          metadata: { userId, thumbsUp },
        });
      }
    }
  }
  ```

  ```python theme={"theme":{"light":"github-light","dark":"github-dark-dimmed"}}
  import braintrust

  class MyApplication:
      def init_app(self):
          self.dataset = braintrust.init_dataset(project="My App", name="logs")

      def log_user_example(self, input, expected, user_id, thumbs_up):
          if self.dataset:
              self.dataset.insert(
                  input=input,
                  expected=expected,
                  metadata={"user_id": user_id, "thumbs_up": thumbs_up},
              )
  ```
</CodeGroup>

### Add traces to a dataset

You can add a trace to a dataset by mapping fields from a production log span into dataset row format. The span's `input` maps to the dataset row's `input`, and the span's `output` typically becomes the row's `expected` value. This is useful when you see a notably good or bad response in production and want to capture it as a test case. You can add traces to datasets with the Braintrust UI or programmatically with the Braintrust API.

<Tabs>
  <Tab title="UI" icon="mouse-pointer-2">
    Add traces to a dataset using the Braintrust UI:

    1. Go to [<Icon icon="activity" /> **Logs**](https://www.braintrust.dev/app/~/logs).
    2. Select the traces you want to add.
    3. Select **+ Dataset** and then the dataset you want to add to.
  </Tab>

  <Tab title="API" icon="code">
    Use the [BTQL endpoint](/api-reference#query-logs-and-experiments) to fetch an existing span from your production logs, then insert it into a dataset using the [dataset insert API](/api-reference/datasets/insert-dataset-events). The `origin` field links the dataset row back to the source span, creating a **Log** button in the Origin column.

    <CodeGroup dropdown>
      ```typescript theme={"theme":{"light":"github-light","dark":"github-dark-dimmed"}}
      import { initDataset } from "braintrust";

      const projectId = "<your-project-id>";
      const spanId = "<span-id-from-logs>";

      // Fetch the span from project logs
      const btqlResponse = await fetch("https://api.braintrust.dev/btql", {
        method: "POST",
        headers: {
          Authorization: `Bearer ${process.env.BRAINTRUST_API_KEY}`,
          "Content-Type": "application/json",
        },
        body: JSON.stringify({
          query: `SELECT id, input, output FROM project_logs('${projectId}') WHERE span_id = '${spanId}' LIMIT 1`,
        }),
      });
      const { data } = await btqlResponse.json();
      const span = data[0];

      // Insert into the dataset, mapping span fields to dataset row format
      const dataset = initDataset("My App", { dataset: "Customer Support" });
      const datasetId = await dataset.id;

      await fetch(`https://api.braintrust.dev/v1/dataset/${datasetId}/insert`, {
        method: "POST",
        headers: {
          Authorization: `Bearer ${process.env.BRAINTRUST_API_KEY}`,
          "Content-Type": "application/json",
        },
        body: JSON.stringify({
          events: [
            {
              input: span.input,
              // span.output is the raw output from your app — extract the relevant
              // value for your use case (e.g. span.output[0].message.content for
              // OpenAI chat completions)
              expected: span.output,
              origin: {
                object_type: "project_logs",
                object_id: projectId,
                // span.id is the row UUID from the SELECT above — what the Log button expects.
                id: span.id,
              },
            },
          ],
        }),
      });
      ```

      ```python theme={"theme":{"light":"github-light","dark":"github-dark-dimmed"}}
      import os
      import httpx
      import braintrust

      project_id = "<your-project-id>"
      span_id = "<span-id-from-logs>"

      # Fetch the span from project logs
      btql_response = httpx.post(
          "https://api.braintrust.dev/btql",
          headers={
              "Authorization": f"Bearer {os.environ['BRAINTRUST_API_KEY']}",
              "Content-Type": "application/json",
          },
          json={
              "query": f"SELECT id, input, output FROM project_logs('{project_id}') WHERE span_id = '{span_id}' LIMIT 1",
          },
      )
      span = btql_response.json()["data"][0]

      # Insert into the dataset, mapping span fields to dataset row format
      dataset = braintrust.init_dataset(project="My App", name="Customer Support")
      dataset_id = dataset.id

      httpx.post(
          f"https://api.braintrust.dev/v1/dataset/{dataset_id}/insert",
          headers={
              "Authorization": f"Bearer {os.environ['BRAINTRUST_API_KEY']}",
              "Content-Type": "application/json",
          },
          json={
              "events": [
                  {
                      "input": span["input"],
                      # span["output"] is the raw output from your app — extract the relevant
                      # value for your use case (e.g. span["output"][0]["message"]["content"]
                      # for OpenAI chat completions)
                      "expected": span["output"],
                      "origin": {
                          "object_type": "project_logs",
                          "object_id": project_id,
                          # span["id"] is the row UUID from the SELECT above — what the Log button expects.
                          "id": span["id"],
                      },
                  },
              ],
          },
      )
      ```
    </CodeGroup>
  </Tab>
</Tabs>

## Manage datasets

### Tag and star datasets

You can tag and star datasets to organize and find them in the datasets list. Tagging a dataset adds metadata that can be used to filter and group records, while starring a dataset causes it to sort first in the datasets table and dataset picker dropdowns.

To tag datasets:

1. Go to **<Icon icon="database" /> Datasets**.
2. Select one or more datasets.
3. Click **<Icon icon="tag" /> Tag** in the toolbar.
4. Select or create tags to apply.

<Note>
  Tags are configured at the project level and shared across all objects — logs, experiments, dataset records, and entire datasets. See [project tag settings](/admin/projects#add-tags).
</Note>

To star a dataset, click the <Icon icon="star" /> **star icon** next to the dataset's name in the datasets list.

### Filter records

Read and filter datasets using `_internal_btql` to control which records are returned:

<CodeGroup dropdown>
  ```typescript theme={"theme":{"light":"github-light","dark":"github-dark-dimmed"}}
  import { initDataset } from "braintrust";

  // Read all records
  const dataset = initDataset("My App", { dataset: "Customer Support" });

  for await (const row of dataset) {
    console.log(row);
  }

  // Filter by metadata
  const premiumDataset = initDataset("My App", {
    dataset: "Customer Support",
    _internal_btql: {
      filter: { btql: "metadata.category = 'premium'" },
      limit: 100,
    },
  });

  for await (const row of premiumDataset) {
    console.log(row);
  }

  // Sort by creation date
  const sortedDataset = initDataset("My App", {
    dataset: "Customer Support",
    _internal_btql: {
      sort: [{ expr: { btql: "created" }, dir: "desc" }],
      limit: 50,
    },
  });

  // Combine filters and sorts
  const recentSupport = initDataset("My App", {
    dataset: "Customer Support",
    _internal_btql: {
      filter: {
        btql: "metadata.category = 'support' and created > now() - interval 7 day",
      },
      sort: [{ expr: { btql: "created" }, dir: "desc" }],
      limit: 1000,
    },
  });
  ```

  ```python theme={"theme":{"light":"github-light","dark":"github-dark-dimmed"}}
  import braintrust

  # Read all records
  dataset = braintrust.init_dataset(project="My App", name="Customer Support")

  for row in dataset:
      print(row)

  # Filter by metadata
  premium_dataset = braintrust.init_dataset(
      project="My App",
      name="Customer Support",
      _internal_btql={
          "filter": {"btql": "metadata.category = 'premium'"},
          "limit": 100,
      },
  )

  for row in premium_dataset:
      print(row)

  # Sort by creation date
  sorted_dataset = braintrust.init_dataset(
      project="My App",
      name="Customer Support",
      _internal_btql={
          "sort": [{"expr": {"btql": "created"}, "dir": "desc"}],
          "limit": 50,
      },
  )

  # Combine filters and sorts
  recent_support = braintrust.init_dataset(
      project="My App",
      name="Customer Support",
      _internal_btql={
          "filter": {"btql": "metadata.category = 'support' and created > now() - interval 7 day"},
          "sort": [{"expr": {"btql": "created"}, "dir": "desc"}],
          "limit": 1000,
      },
  )
  ```
</CodeGroup>

For more information on SQL syntax and available operators, see the [SQL reference documentation](/reference/sql).

### Update records

Update existing records by `id`:

<CodeGroup dropdown>
  ```typescript theme={"theme":{"light":"github-light","dark":"github-dark-dimmed"}}
  import { initDataset } from "braintrust";

  const dataset = initDataset("My App", { dataset: "Customer Support" });

  // Insert a record
  const id = dataset.insert({
    input: { question: "How do I reset my password?" },
    expected: { answer: "Click 'Forgot Password' on the login page." },
  });

  // Update the record
  dataset.update({
    id,
    metadata: { reviewed: true, difficulty: "easy" },
  });

  await dataset.flush();
  ```

  ```python theme={"theme":{"light":"github-light","dark":"github-dark-dimmed"}}
  import braintrust

  dataset = braintrust.init_dataset(project="My App", name="Customer Support")

  # Insert a record
  id = dataset.insert(
      input={"question": "How do I reset my password?"},
      expected={"answer": "Click 'Forgot Password' on the login page."},
  )

  # Update the record
  dataset.update(
      id=id,
      metadata={"reviewed": True, "difficulty": "easy"},
  )

  dataset.flush()
  ```
</CodeGroup>

The `update()` method applies a merge strategy: only the fields you provide will be updated, and all other existing fields in the record will remain unchanged.

### Delete records

Remove records programmatically by `id`:

<CodeGroup dropdown>
  ```typescript theme={"theme":{"light":"github-light","dark":"github-dark-dimmed"}}
  import { initDataset } from "braintrust";

  const dataset = initDataset("My App", { dataset: "Customer Support" });

  // Insert a record
  const id = dataset.insert({
    input: { question: "Test question" },
    expected: { answer: "Test answer" },
  });

  // Delete the record
  await dataset.delete(id);
  await dataset.flush();
  ```

  ```python theme={"theme":{"light":"github-light","dark":"github-dark-dimmed"}}
  import braintrust

  dataset = braintrust.init_dataset(project="My App", name="Customer Support")

  # Insert a record
  id = dataset.insert(
      input={"question": "Test question"},
      expected={"answer": "Test answer"},
  )

  # Delete the record
  dataset.delete(id)
  dataset.flush()
  ```
</CodeGroup>

To delete an entire dataset, use the UI or the [API](/api-reference).

### Flush records

The Braintrust SDK flushes records asynchronously and installs exit handlers, but these hooks are not always respected (e.g., by certain runtimes or when exiting a process abruptly). Call `flush()` to ensure records are written:

<CodeGroup dropdown>
  ```typescript theme={"theme":{"light":"github-light","dark":"github-dark-dimmed"}}
  import { initDataset } from "braintrust";

  const dataset = initDataset("My App", { dataset: "Customer Support" });

  // Insert records
  dataset.insert({
    input: { question: "How do I reset my password?" },
    expected: { answer: "Click 'Forgot Password' on the login page." },
  });

  // Flush to ensure all records are saved
  await dataset.flush();
  ```

  ```python theme={"theme":{"light":"github-light","dark":"github-dark-dimmed"}}
  import braintrust

  dataset = braintrust.init_dataset(project="My App", name="Customer Support")

  # Insert records
  dataset.insert(
      input={"question": "How do I reset my password?"},
      expected={"answer": "Click 'Forgot Password' on the login page."},
  )

  # Flush to ensure all records are saved
  dataset.flush()
  ```
</CodeGroup>

### Create custom columns

Extract values from records using [custom columns](/evaluate/interpret-results#create-custom-columns). Use SQL expressions to surface important fields directly in the table.

### Create custom table views

To create or update a custom table view:

1. Apply the filters and display settings you want.
2. Open the <Icon icon="layers-2" /> menu and select **Save view\...** or **Save view as...**.

<Note>
  Custom table views are visible to all project members. Creating or editing a table view requires the **Update** project permission.
</Note>

### Set default table views

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

## Use in evaluations

Use datasets as the data source for evaluations. You can pass datasets directly or convert experiment results into dataset format.

### Pass datasets directly

Pass datasets directly to `Eval()`:

<CodeGroup dropdown>
  ```typescript theme={"theme":{"light":"github-light","dark":"github-dark-dimmed"}}
  import { initDataset, Eval } from "braintrust";
  import { Levenshtein } from "autoevals";

  Eval("Say Hi Bot", {
    data: initDataset("My App", { dataset: "My Dataset" }),
    task: async (input) => {
      return "Hi " + input;
    },
    scores: [Levenshtein],
  });
  ```

  ```python theme={"theme":{"light":"github-light","dark":"github-dark-dimmed"}}
  from autoevals import Levenshtein
  from braintrust import Eval, init_dataset

  Eval(
      "Say Hi Bot",
      data=init_dataset(project="My App", name="My Dataset"),
      task=lambda input: "Hi " + input,
      scores=[Levenshtein],
  )
  ```
</CodeGroup>

### Convert experiment results

Convert experiment results into dataset format using `asDataset()`/`as_dataset()`. This is useful for iterative improvement workflows where you want to use the results of one experiment as the baseline for future experiments:

<CodeGroup dropdown>
  ```typescript theme={"theme":{"light":"github-light","dark":"github-dark-dimmed"}}
  import { init, Eval } from "braintrust";
  import { Levenshtein } from "autoevals";

  const experiment = init("My App", {
    experiment: "my-experiment",
    open: true,
  });

  Eval<string, string>("My App", {
    data: experiment.asDataset(),
    task: async (input) => {
      return `hello ${input}`;
    },
    scores: [Levenshtein],
  });
  ```

  ```python theme={"theme":{"light":"github-light","dark":"github-dark-dimmed"}}
  from autoevals import Levenshtein
  from braintrust import Eval, init

  experiment = braintrust.init(
      project="My App",
      experiment="my-experiment",
      open=True,
  )

  Eval(
      "My App",
      data=experiment.as_dataset(),
      task=lambda input: f"hello {input}",
      scores=[Levenshtein],
  )
  ```
</CodeGroup>

## Review datasets

You can configure human review workflows to label and evaluate dataset records with your team.

### Configure review scores

Configure categorical scores to allow reviewers to rapidly label records. See [Configure review scores](/annotate/human-review#configure-review-scores) for details.

<img src="https://mintcdn.com/braintrust/ORZ9J5LROFjITLRP/images/guides/human-review/expected-fields.png?fit=max&auto=format&n=ORZ9J5LROFjITLRP&q=85&s=406359641fc9f3de4f70086d56f236dd" alt="Write to expected" width="1852" height="966" data-path="images/guides/human-review/expected-fields.png" />

### Assign rows for review

Assign dataset rows to team members for review, analysis, or follow-up action. Assignments are particularly useful for distributing review work across multiple team members.

See [Assign rows for review](/annotate/human-review#assign-rows-for-review) for details.

## Define schemas

If you want to ensure all records have the same structure or make editing easier, define JSON schemas for your dataset fields. Schemas are particularly useful when multiple team members are manually adding records or when you need strict data validation.

Dataset schemas enable:

* **Validation**: Catch structural errors when adding or editing records.
* **Form-based editing**: Edit records with intuitive forms instead of raw JSON.
* **Documentation**: Make field expectations explicit for your team.

To define a schema:

1. Go to your dataset.
2. Click <Icon icon="text-cursor-input" /> **Field schemas** in the toolbar.
3. Select the field you want to define a schema for (`input`, `expected`, or `metadata`).
4. Click <Icon icon="scan-text" /> **Infer schema** to automatically generate a schema from the first 100 records, or manually define your schema structure.
5. Toggle **Enforce** to enable validation. When enabled:
   * New records must conform or show validation errors.
   * Existing non-conforming records display warnings.
   * Form editing validates input as you type.

<Note>
  Enforcement is UI-only and doesn't affect SDK inserts or updates.
</Note>

## Track performance

Monitor how dataset rows perform across experiments.

### View experiment runs

See all experiments that used a dataset:

1. Go to your dataset page.
2. In the right panel, select <Icon icon="play" /> **Runs**.
3. Review performance metrics across experiments.

Runs display as charts that show score trends over time. The time axis flows from oldest (left) to newest (right), making it easy to track performance evolution.

### Filter experiment runs

To narrow down the list of experiment runs, you can filter by time range, tag, or use SQL.

**Filter by time range**: Click and drag across any region of the chart to select a time range. The table below updates to show only experiments in that range. To clear the filter, click **clear**. This helps you focus on specific periods, like recent experiments or historical baselines.

**Filter by tag**: Click any tag chip on an experiment row to instantly filter the list to runs with that tag. You can also add a **Tags** column via <Icon icon="columns-3" /> **Display** > **Columns** to see tags for each run at a glance. To filter by tag in a query, use BTQL's `INCLUDES` operator:

```sql theme={"theme":{"light":"github-light","dark":"github-dark-dimmed"}}
filter: tags INCLUDES 'my-tag'
```

**Filter with SQL**: Select <Icon icon="list-filter" /> **Filter** and use the **Basic** tab for common filters, or switch to **SQL** to write more precise [SQL queries](/reference/sql) based on criteria like score thresholds, time ranges, or experiment names.

Common filtering examples:

```sql theme={"theme":{"light":"github-light","dark":"github-dark-dimmed"}}
-- Filter by time range
WHERE created > '2024-01-01'

-- Filter by score threshold
WHERE scores.Accuracy > 0.8

-- Filter by experiment name pattern
WHERE name LIKE '%baseline%'

-- Combine multiple conditions
WHERE created > now() - interval 7 day
  AND scores.Factuality > 0.7
```

<Note>
  Filter states are persisted in the URL, allowing you to bookmark or share specific filtered views of experiment runs.
</Note>

### Analyze per-row performance

See how individual rows perform:

1. Select a row in the dataset table.
2. In the right panel, select <Icon icon="play" /> **Runs**.
3. Review the row's metrics across experiments.

<Note>
  This view only shows experiments that set the `origin` field in eval traces.
</Note>

<img src="https://mintlify.s3.us-west-1.amazonaws.com/braintrust/core/datasets/datasetRowRuns.png" alt="Dataset row performance" />

Look for patterns:

* Consistently low scores suggest ambiguous expectations.
* Failures across experiments indicate edge cases.
* High variance suggests instability.

## Multimodal datasets

You can store and process images and other file types in your datasets. There are several ways to use files in Braintrust:

* **Image URLs** (most performant) - Keep datasets lightweight with external image references.
* **Base64** (least performant) - Encode images directly in records.
* **Attachments** (easiest to manage) - Store files directly in Braintrust.
* **External attachments** - Reference files in your own object stores.

For large images, use image URLs to keep datasets lightweight. To keep all data within Braintrust, use attachments. Attachments support any file type including images, audio, and PDFs.

<CodeGroup dropdown>
  ```typescript theme={"theme":{"light":"github-light","dark":"github-dark-dimmed"}}
  import { Attachment, initDataset } from "braintrust";
  import path from "node:path";

  async function createPdfDataset(): Promise<void> {
    const dataset = initDataset({
      project: "Project with PDFs",
      dataset: "My PDF Dataset",
    });
    for (const filename of ["example.pdf"]) {
      dataset.insert({
        input: {
          file: new Attachment({
            filename,
            contentType: "application/pdf",
            data: path.join("files", filename),
          }),
        },
      });
    }
    await dataset.flush();
  }

  createPdfDataset();
  ```

  ```python theme={"theme":{"light":"github-light","dark":"github-dark-dimmed"}}
  import os
  from braintrust import Attachment, init_dataset

  def create_pdf_dataset() -> None:
      dataset = init_dataset("Project with PDFs", "My PDF Dataset")
      for filename in ["example.pdf"]:
          dataset.insert(
              input={
                  "file": Attachment(
                      filename=filename,
                      content_type="application/pdf",
                      data=os.path.join("files", filename),
                  )
              },
          )
      dataset.flush()

  create_pdf_dataset()
  ```
</CodeGroup>

## Next steps

* [Add human feedback](/annotate/human-review) to label datasets.
* [Run evaluations](/evaluate/run-evaluations) using your datasets.
* [Use the Loop](/loop) to generate and optimize datasets.
* Read the [SQL reference](/reference/sql) for advanced filtering.
