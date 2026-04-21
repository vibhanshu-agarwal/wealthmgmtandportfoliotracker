> ## Documentation Index
> Fetch the complete documentation index at: https://braintrust.dev/docs/llms.txt
> Use this file to discover all available pages before exploring further.

# Manage environments

> Separate dev, staging, and production configurations

export const feature_0 = "Environments"

export const verb_0 = "are"

Environments let you maintain different versions of prompts and parameters across your development lifecycle. This enables you to:

* **Maintain version control**: Pin stable versions to production while testing new versions in development
* **Enable staged deployments**: Promote versions through dev/staging/production pipelines
* **Support A/B testing**: Compare different versions across environments
* **Isolate changes**: Test modifications without affecting production systems

<Note>
  {feature_0} {verb_0} only available on [Pro and Enterprise plans](/plans-and-limits#plans).
</Note>

## Create an environment

Environments are defined at the organization level:

1. Go to <Icon icon="settings-2" /> **Settings**.
2. Click <Icon icon="layers" /> **Environments**.
3. Click **+ Environment**.
4. Enter a name (e.g., "production", "staging", "dev").
5. Click **Create environment**.

## Assign to environments

<Tabs>
  <Tab title="Prompts">
    To assign a prompt to an environment:

    <Tabs>
      <Tab title="UI" icon="mouse-pointer-2">
        1. Go to **<Icon icon="message-circle" /> Prompts**.
        2. Open the prompt.
        3. Click the <Icon icon="layers" /> icon.
        4. Select an environment.
      </Tab>

      <Tab title="API" icon="code">
        Use [`POST /v1/prompt`](/api-reference/prompts/create-prompt) or [`PUT /v1/prompt`](/api-reference/prompts/create-or-replace-prompt) and pass `environment_slugs` to assign the prompt to one or more environments in a single atomic request. If any slug doesn't exist, the entire request fails and no prompt is created.

        ```bash theme={"theme":{"light":"github-light","dark":"github-dark-dimmed"}}
        curl -X POST https://api.braintrust.dev/v1/prompt \
          -H "Authorization: Bearer $BRAINTRUST_API_KEY" \
          -H "Content-Type: application/json" \
          -d '{
            "project_id": "your-project-id",
            "name": "My prompt",
            "slug": "my-prompt-slug",
            "environment_slugs": ["dev", "staging"],
            "prompt_data": {
              "prompt": {
                "type": "chat",
                "messages": [{"role": "system", "content": "You are a helpful assistant"}]
              },
              "options": {
                "model": "gpt-5-mini"
              }
            }
          }'
        ```
      </Tab>
    </Tabs>

    Once assigned, load prompts for that environment in your code:

    <Tabs>
      <Tab title="SDK" icon="terminal">
        <CodeGroup dropdown>
          ```typescript theme={"theme":{"light":"github-light","dark":"github-dark-dimmed"}}
          import { loadPrompt } from "braintrust";

          // Load from specific environment
          const prompt = await loadPrompt({
            projectName: "My Project",
            slug: "my-prompt",
            environment: "production",
          });

          // Use conditional versioning
          const prompt = await loadPrompt({
            projectName: "My Project",
            slug: "my-prompt",
            version: process.env.NODE_ENV === "production" ? "5878bd218351fb8e" : undefined,
          });
          ```

          ```python theme={"theme":{"light":"github-light","dark":"github-dark-dimmed"}}
          from braintrust import load_prompt
          import os

          # Load from specific environment
          prompt = load_prompt(
              project="My Project",
              slug="my-prompt",
              environment="production"
          )

          # Use conditional versioning
          prompt = load_prompt(
              "My Project",
              "my-prompt",
              version="5878bd218351fb8e" if os.environ.get("NODE_ENV") == "production" else None,
          )
          ```
        </CodeGroup>
      </Tab>

      <Tab title="API" icon="code">
        ```bash theme={"theme":{"light":"github-light","dark":"github-dark-dimmed"}}
        # Load by project ID and slug
        curl "https://api.braintrust.dev/v1/prompt?slug=my-prompt-slug&project_id=PROJECT_ID&environment=production" \
          -H "Authorization: Bearer $BRAINTRUST_API_KEY"

        # Load by prompt ID
        curl "https://api.braintrust.dev/v1/prompt/PROMPT_ID?environment=production" \
          -H "Authorization: Bearer $BRAINTRUST_API_KEY"
        ```
      </Tab>
    </Tabs>
  </Tab>

  <Tab title="Parameters">
    To assign a specific parameter version to an environment:

    1. Go to **<Icon icon="square-dot" /> Parameters**.
    2. Open the parameter.
    3. Click the <Icon icon="layers" /> icon.
    4. Select an environment.

    Once assigned, load parameters for that environment in your code:

    <CodeGroup dropdown>
      ```typescript theme={"theme":{"light":"github-light","dark":"github-dark-dimmed"}}
      import { loadParameters } from "braintrust";

      const params = await loadParameters({
        projectName: "My Project",
        slug: "eval-config",
        environment: "production",
      });
      ```
    </CodeGroup>
  </Tab>
</Tabs>

## Promote versions

Move tested versions from development to production:

1. Test a new prompt or parameter version in the "dev" environment.
2. Run experiments to validate performance.
3. Once satisfied, assign the same version to "staging".
4. After final validation, assign to "production".

This workflow ensures changes are validated before reaching production users.

## Monitor environment changes

Set up [environment alerts](/admin/automations/alerts#create-an-environment-alert) to get notified via webhook or Slack when prompt or parameter versions are assigned to or removed from environments. Use these to track deployments, maintain audit trails, or trigger downstream CI/CD workflows.

## Common patterns

### Three-tier deployment

Maintain dev, staging, and production environments:

* **dev**: Latest changes, frequent updates, used by developers.
* **staging**: Pre-release testing, stable versions.
* **production**: Customer-facing, only validated versions.

### Feature flags

Use environments to control feature rollouts:

* Create an environment for each feature flag.
* Assign different prompt or parameter versions based on flag state.
* Gradually roll out by changing environment assignments.

### A/B testing

Test variations by environment:

* Create environments for each variant (e.g., "variant-a", "variant-b").
* Assign different prompt or parameter versions to each.
* Route users to different environments based on A/B test assignment.
* Compare performance using environment filters.

## Next steps

* [Write prompts](/evaluate/write-prompts)
* [Deploy prompts](/deploy/prompts) that use environments
