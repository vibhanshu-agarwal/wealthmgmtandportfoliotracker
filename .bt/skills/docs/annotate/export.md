> ## Documentation Index
> Fetch the complete documentation index at: https://braintrust.dev/docs/llms.txt
> Use this file to discover all available pages before exploring further.

# Export annotated data

> Extract data for external tools and analysis

Export your annotated data to use in external evaluation frameworks, custom analysis pipelines, reporting, or training workflows. Braintrust provides multiple export methods to fit your needs.

<Tabs>
  <Tab title="UI" icon="mouse-pointer-2">
    Download data directly from the Braintrust dashboard:

    ### Export logs

    1. Navigate to the **Logs** page.
    2. Apply filters to select the data you want.
    3. Select the export icon.
    4. Choose format (JSON or CSV).
    5. Download the file.

    ### Export datasets

    1. Navigate to your dataset.
    2. Apply filters if needed.
    3. Select the export icon.
    4. Choose format.
    5. Download the file.

    ### Export experiments

    1. Open an experiment.
    2. Select the export icon.
    3. Choose format.
    4. Download results including inputs, outputs, scores, and metadata.
  </Tab>

  <Tab title="SDK" icon="terminal">
    Read datasets or logs programmatically and export to your preferred format:

    <CodeGroup dropdown>
      ```typescript theme={"theme":{"light":"github-light","dark":"github-dark-dimmed"}}
      import { initDataset } from "braintrust";
      import fs from "fs";

      async function exportDataset() {
        const dataset = initDataset("My App", { dataset: "My Dataset" });
        const records = [];

        for await (const row of dataset) {
          records.push({
            input: row.input,
            expected: row.expected,
            metadata: row.metadata,
          });
        }

        // Export as JSON
        fs.writeFileSync(
          "dataset-export.json",
          JSON.stringify(records, null, 2)
        );

        // Or convert to CSV, Parquet, etc.
      }

      exportDataset();
      ```

      ```python theme={"theme":{"light":"github-light","dark":"github-dark-dimmed"}}
      import braintrust
      import json

      def export_dataset():
          dataset = braintrust.init_dataset(project="My App", name="My Dataset")
          records = []

          for row in dataset:
              records.append({
                  "input": row["input"],
                  "expected": row["expected"],
                  "metadata": row["metadata"],
              })

          # Export as JSON
          with open("dataset-export.json", "w") as f:
              json.dump(records, f, indent=2)

          # Or convert to pandas DataFrame
          # import pandas as pd
          # df = pd.DataFrame(records)
          # df.to_csv("dataset-export.csv", index=False)

      export_dataset()
      ```
    </CodeGroup>

    ### Filter before export

    Use BTQL filtering to export specific subsets:

    <CodeGroup dropdown>
      ```typescript theme={"theme":{"light":"github-light","dark":"github-dark-dimmed"}}
      const dataset = initDataset("My App", {
        dataset: "My Dataset",
        _internal_btql: {
          filter: { btql: "metadata.annotated = true" },
          sort: [{ expr: { btql: "created" }, dir: "desc" }],
          limit: 1000,
        },
      });

      const records = [];
      for await (const row of dataset) {
        records.push(row);
      }
      ```

      ```python theme={"theme":{"light":"github-light","dark":"github-dark-dimmed"}}
      dataset = braintrust.init_dataset(
          project="My App",
          name="My Dataset",
          _internal_btql={
              "filter": {"btql": "metadata.annotated = true"},
              "sort": [{"expr": {"btql": "created"}, "dir": "desc"}],
              "limit": 1000,
          },
      )

      records = [row for row in dataset]
      ```
    </CodeGroup>
  </Tab>

  <Tab title="API" icon="code">
    Query and export data programmatically using the SQL query API endpoint:

    <CodeGroup>
      ```bash SQL syntax theme={"theme":{"light":"github-light","dark":"github-dark-dimmed"}}
      curl -X POST https://api.braintrust.dev/btql \
        -H "Authorization: Bearer <YOUR_API_KEY>" \
        -H "Content-Type: application/json" \
        -d '{
          "query": "SELECT * FROM project_logs('"'<PROJECT_ID>'"') WHERE tags INCLUDES '"'annotated'"'",
          "fmt": "json"
        }'
      ```

      ```bash BTQL syntax theme={"theme":{"light":"github-light","dark":"github-dark-dimmed"}}
      curl -X POST https://api.braintrust.dev/btql \
        -H "Authorization: Bearer <YOUR_API_KEY>" \
        -H "Content-Type: application/json" \
        -d '{
          "query": "select: * | from: project_logs('"'<PROJECT_ID>'"') | filter: tags includes '"'annotated'"'",
          "fmt": "json"
        }'
      ```
    </CodeGroup>

    ### Export formats

    The API supports two formats via the `fmt` parameter:

    * **`json`** (default): Returns data as JSON for easy processing
    * **`parquet`**: Returns data in Parquet format for data warehouses and analytics tools

    ### Export parameters

    * `query` (required): Your SQL query selecting the data to export
    * `fmt`: Response format (`json` or `parquet`)
    * `tz_offset`: Timezone offset in minutes for correct timestamps
    * `audit_log`: Include audit trail data showing who made changes

    ### Export examples

    Export logs with user feedback:

    <CodeGroup>
      ```bash SQL syntax theme={"theme":{"light":"github-light","dark":"github-dark-dimmed"}}
      curl -X POST https://api.braintrust.dev/btql \
        -H "Authorization: Bearer <YOUR_API_KEY>" \
        -H "Content-Type: application/json" \
        -d '{
          "query": "SELECT * FROM project_logs('"'<PROJECT_ID>'"') WHERE scores.user_rating > 0.8 LIMIT 1000",
          "fmt": "parquet"
        }'
      ```

      ```bash BTQL syntax theme={"theme":{"light":"github-light","dark":"github-dark-dimmed"}}
      curl -X POST https://api.braintrust.dev/btql \
        -H "Authorization: Bearer <YOUR_API_KEY>" \
        -H "Content-Type: application/json" \
        -d '{
          "query": "select: * | from: project_logs('"'<PROJECT_ID>'"') | filter: scores.user_rating > 0.8 | limit: 1000",
          "fmt": "parquet"
        }'
      ```
    </CodeGroup>

    Export dataset records:

    <CodeGroup>
      ```bash SQL syntax theme={"theme":{"light":"github-light","dark":"github-dark-dimmed"}}
      curl -X POST https://api.braintrust.dev/btql \
        -H "Authorization: Bearer <YOUR_API_KEY>" \
        -H "Content-Type: application/json" \
        -d '{
          "query": "SELECT input, expected, metadata FROM project_dataset('"'<PROJECT_ID>'"', '"'<DATASET_ID>'"')",
          "fmt": "json"
        }'
      ```

      ```bash BTQL syntax theme={"theme":{"light":"github-light","dark":"github-dark-dimmed"}}
      curl -X POST https://api.braintrust.dev/btql \
        -H "Authorization: Bearer <YOUR_API_KEY>" \
        -H "Content-Type: application/json" \
        -d '{
          "query": "select: input, expected, metadata | from: project_dataset('"'<PROJECT_ID>'"', '"'<DATASET_ID>'"')",
          "fmt": "json"
        }'
      ```
    </CodeGroup>
  </Tab>
</Tabs>

## Common export workflows

### Build training datasets

Export annotated examples for fine-tuning:

<CodeGroup>
  ```bash SQL syntax theme={"theme":{"light":"github-light","dark":"github-dark-dimmed"}}
  curl -X POST https://api.braintrust.dev/btql \
    -H "Authorization: Bearer <YOUR_API_KEY>" \
    -H "Content-Type: application/json" \
    -d '{
      "query": "SELECT input, expected FROM project_dataset('"'<PROJECT_ID>'"', '"'<DATASET_ID>'"') WHERE expected IS NOT NULL",
      "fmt": "json"
    }'
  ```

  ```bash BTQL syntax theme={"theme":{"light":"github-light","dark":"github-dark-dimmed"}}
  curl -X POST https://api.braintrust.dev/btql \
    -H "Authorization: Bearer <YOUR_API_KEY>" \
    -H "Content-Type: application/json" \
    -d '{
      "query": "select: input, expected | from: project_dataset('"'<PROJECT_ID>'"', '"'<DATASET_ID>'"') | filter: expected IS NOT NULL",
      "fmt": "json"
    }'
  ```
</CodeGroup>

### Extract user corrections

Export traces where users provided corrections:

<CodeGroup>
  ```bash SQL syntax theme={"theme":{"light":"github-light","dark":"github-dark-dimmed"}}
  curl -X POST https://api.braintrust.dev/btql \
    -H "Authorization: Bearer <YOUR_API_KEY>" \
    -H "Content-Type: application/json" \
    -d '{
      "query": "SELECT input, output, expected, metadata.user_id FROM project_logs('"'<PROJECT_ID>'"') WHERE expected IS NOT NULL AND metadata.user_correction = true",
      "fmt": "json"
    }'
  ```

  ```bash BTQL syntax theme={"theme":{"light":"github-light","dark":"github-dark-dimmed"}}
  curl -X POST https://api.braintrust.dev/btql \
    -H "Authorization: Bearer <YOUR_API_KEY>" \
    -H "Content-Type: application/json" \
    -d '{
      "query": "select: input, output, expected, metadata.user_id | from: project_logs('"'<PROJECT_ID>'"') | filter: expected IS NOT NULL and metadata.user_correction = true",
      "fmt": "json"
    }'
  ```
</CodeGroup>

### Generate reports

Export performance data for reporting:

<CodeGroup>
  ```bash SQL syntax theme={"theme":{"light":"github-light","dark":"github-dark-dimmed"}}
  curl -X POST https://api.braintrust.dev/btql \
    -H "Authorization: Bearer <YOUR_API_KEY>" \
    -H "Content-Type: application/json" \
    -d '{
      "query": "SELECT created, scores.user_rating, scores.accuracy, metadata.model FROM project_logs('"'<PROJECT_ID>'"') WHERE created > now() - interval 7 day",
      "fmt": "parquet"
    }'
  ```

  ```bash BTQL syntax theme={"theme":{"light":"github-light","dark":"github-dark-dimmed"}}
  curl -X POST https://api.braintrust.dev/btql \
    -H "Authorization: Bearer <YOUR_API_KEY>" \
    -H "Content-Type: application/json" \
    -d '{
      "query": "select: created, scores.user_rating, scores.accuracy, metadata.model | from: project_logs('"'<PROJECT_ID>'"') | filter: created > now() - interval 7 day",
      "fmt": "parquet"
    }'
  ```
</CodeGroup>

## Preserve annotations

When exporting, all annotations are preserved:

* **Tags**: Included in the exported data
* **Comments**: Available in the metadata
* **Expected values**: Exported with each record
* **Scores**: All score values included
* **Metadata**: Custom fields maintained

This ensures your annotations remain useful in external tools.

## Next steps

* [Run evaluations](/evaluate/run-evaluations) with exported datasets
* [Build prompts](/evaluate/write-prompts) using annotated examples
* [Create scorers](/evaluate/write-scorers) based on labeled data
* Read the [SQL reference](/reference/sql) for advanced queries
