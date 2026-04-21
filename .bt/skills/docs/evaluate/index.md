> ## Documentation Index
> Fetch the complete documentation index at: https://braintrust.dev/docs/llms.txt
> Use this file to discover all available pages before exploring further.

# Evaluate systematically

> Test and monitor AI application quality at every stage of development

AI systems behave differently from traditional software: the same input can produce different outputs, there's rarely a single correct answer, and a change that improves one metric can silently degrade another. Systematic evaluation is how you measure quality, detect regressions before they reach users, and build confidence that your system is actually improving over time.

Braintrust supports evaluation at every stage of AI development — from rapid iteration in the browser to systematic experiments to continuous production monitoring. The full evaluation cycle:

1. **Iterate in playgrounds** — Prototype prompts, models, scorers, or custom agent code
2. **Promote to an experiment** — Lock in an immutable snapshot when you find a good configuration
3. **Automate in CI/CD** — Run evals on every pull request to catch regressions
4. **Score in production** — Monitor live traffic continuously with online scoring rules
5. **Feed back** — Pull interesting production traces into datasets to improve offline test coverage

## Offline evaluation

Offline evaluation runs against known datasets before deployment. Because you control the inputs and can define expected outputs, you can use code-based scorers or LLM-as-a-judge — and results are reproducible and comparable over time.

### Iterate in playgrounds

[Playgrounds](/evaluate/playgrounds) are a browser-based environment for rapid iteration. Run evaluations in real time, compare configurations side by side, and share results with teammates via URL. Results are mutable — re-running a playground overwrites previous generations, which is ideal for fast iteration.

When your task can't be expressed as a prompt, connect custom agent code to the playground via [remote evals or sandboxes](/evaluate/remote-evals). The iteration workflow stays the same.

When you've found a good configuration, promote it to an experiment to capture an immutable snapshot.

### Run experiments

Experiments are the immutable, comparable record of your eval runs. [Run them](/evaluate/run-evaluations) from code or in the UI, track progress over time, and integrate into CI/CD to catch regressions before they reach production.

## Online evaluation

[Online scoring](/evaluate/score-online) evaluates production traces automatically as they're logged, running asynchronously with no impact on latency. Because there's no ground truth for live requests, it relies on LLM-as-a-judge scorers to assess quality. Use it to monitor for regressions, catch edge cases you haven't seen before, and surface real user interactions that become new test cases.

## Anatomy of an evaluation

Every evaluation has three parts:

**Data** — a dataset of test cases with inputs, optional expected outputs, and metadata. Build [datasets](/annotate/datasets) from production logs, user feedback, or manual curation.

**Task** — the function being evaluated. Typically an LLM call, but can be any logic: a multi-step agent, a retrieval pipeline, or a custom workflow.

**Scores** — functions that measure quality by comparing inputs, outputs, and expected values. Use built-in autoevals, [LLM-as-a-judge scorers](/evaluate/write-scorers), or custom code.

## Next steps

* [Test prompts and models](/evaluate/playgrounds) in the playground
* [Test complex agents](/evaluate/remote-evals) in the playground via remote evals or sandboxes
* [Run experiments](/evaluate/run-evaluations) with the SDK or in the UI
* [Run in CI/CD](/evaluate/run-evaluations#run-in-cicd) to catch regressions automatically
* [Score production traces](/evaluate/score-online) with online scoring rules
* [Best practices](/evaluate/best-practices) for reliable evaluations
