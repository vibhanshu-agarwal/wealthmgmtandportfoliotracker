> ## Documentation Index
> Fetch the complete documentation index at: https://braintrust.dev/docs/llms.txt
> Use this file to discover all available pages before exploring further.

# Evaluation best practices

> Evidence-based guidance for running reliable AI evaluations

These practices help you get reliable, actionable signals from your evaluations rather than noise that leads to wrong conclusions.

## Start with a small dataset

Begin with 5-10 representative examples that cover your core use cases. A small, well-chosen [dataset](/annotate/datasets) is more useful than a large dataset of easy cases.

Expand your dataset incrementally, guided by actual failures from production or previous experiments — not by synthetic construction of edge cases you imagine might exist. Synthetic data is fine for bootstrapping, but real-world failures reveal what actually goes wrong.

## Validate your scoring system

Your evaluation is only as reliable as your scorers. Before trusting score trends, confirm that your scorers actually measure what you intend:

* **Test obvious cases**: Run the scorer on inputs where the correct score is clear — a clearly correct output should score near 1, a clearly wrong one near 0. If it doesn't, the scorer needs work.
* **Read the reasoning**: [LLM-as-a-judge scorers](/evaluate/write-scorers#score-with-llms) include a chain-of-thought rationale in the score span. Open a few traces and read it. Is the judge reasoning about the right things? Is it consistent?
* **Watch for bias**: LLM judges tend to favor longer, more formal, or more confident-sounding outputs regardless of accuracy. Test for this explicitly by comparing a correct short answer against a wrong but verbose one.

If your scorer isn't working correctly, score trends will mislead you. Fix the scorer before drawing conclusions from experiments. See [Writing scorers](/best-practices/scorers) for a detailed guide on building and calibrating scorers.

## Run evaluations in a loop

Running an evaluation once tells you where you are. Running evaluations in a continuous loop is how you improve. Each iteration surfaces new failures, expands your dataset with real examples, and lets you measure whether a change actually helped — without breaking what already works.

<Steps>
  <Step title="Identify failures">
    Start with your production logs or the last experiment. Sort by score to find the lowest-performing cases and look for patterns — do failures cluster around a particular topic, input type, or user intent?

    Use [Loop](/loop) to analyze patterns across many cases at once:

    * "What do the low-scoring cases have in common?"
    * "Categorize the failures in this experiment"
    * "Which input types perform worst?"

    Focus on understanding *why* cases fail, not just *that* they fail. The fix depends on the root cause: a weak prompt instruction, a miscalibrated scorer, or a gap in your dataset.
  </Step>

  <Step title="Expand your dataset">
    Add the failing cases to your [dataset](/annotate/datasets). Real failures are more valuable than synthetic examples — they reflect actual user behavior and surface edge cases you wouldn't have thought to construct.

    Use [topics](/observe/topics) to find clusters of similar production logs and pull them into a dataset in bulk. This is especially useful when failures share a common pattern (e.g., refund requests, multi-step instructions, ambiguous inputs).

    <Tip>
      Build datasets from failures, not successes. Datasets that only contain easy cases give you a false sense of quality.
    </Tip>
  </Step>

  <Step title="Establish a baseline">
    Before making any changes, run an experiment against your updated dataset. This is your baseline — the number you're trying to beat.

    Record the baseline experiment name or ID so you can reference it when comparing later. Don't rely on memory or approximate comparisons.
  </Step>

  <Step title="Make a targeted change">
    Change one thing: a prompt instruction, a system message, a model, a parameter. Changing multiple things at once makes it impossible to know what caused any improvement or regression you observe.

    If your dataset reveals a pattern — for example, that the model handles refund requests poorly — write a focused fix (a new instruction or a few-shot example) rather than rewriting the whole prompt.
  </Step>

  <Step title="Verify without regression">
    Run a new experiment and [compare it](/evaluate/compare-experiments) against your baseline. Look for:

    * Improvement on the cases you targeted
    * No regressions on cases that were already passing
    * Score changes that reflect real output quality changes, not scorer noise

    Use diff mode to see exactly what changed in the outputs. If scores improved but outputs look the same, check your scorer.
  </Step>

  <Step title="Repeat">
    Merge the new dataset rows into your main dataset and update your baseline to the latest experiment. The next cycle starts with broader coverage than the last.

    Over time, your dataset grows to reflect the full distribution of real-world inputs. Your evals become more reliable. Your baselines become harder to beat — which means improvements are real.
  </Step>
</Steps>

The loop works best when online and offline evaluation are connected:

1. [Online scoring rules](/evaluate/score-online) run continuously on production traffic and surface low-quality interactions.
2. Interesting traces get added to datasets via the UI or SDK.
3. Offline experiments run against those datasets, testing fixes before they ship.
4. Deployed changes are monitored by online scoring again.

This closes the gap between what you test offline and what users actually send.

## Change one variable at a time

When you change multiple things between experiments — prompt, model, parameters, scorer — you can't tell which change caused the result. If scores improve, you don't know what to keep. If they regress, you don't know what to revert.

Make one change per experiment. This takes more runs but produces interpretable results. The only exception is when you're doing a full system overhaul and want a rough directional signal — but even then, plan to isolate variables before shipping.

## Account for nondeterminism

LLM outputs are nondeterministic. A single experiment run can make a bad change look good, or mask a real improvement. Rather than running the same experiment multiple times, use a larger dataset or increase the [trial count](/evaluate/run-evaluations#run-trials) within a single experiment — both take full advantage of concurrency and give you more signal without the overhead of repeated runs.

Compare averages over individual results. This matters most when score differences are small (under 5 percentage points). If results vary significantly, your scorer or dataset may need more signal.

## Keep your baseline current

Always compare against the version of your system that is actually in production, not an old experiment from months ago. A baseline that doesn't reflect current behavior makes comparisons meaningless.

Update your baseline whenever you make a change to the prompt, model, or scorer. If you're unsure which experiment represents the current state, check your deployment history or run a fresh baseline before making changes.

Stale baselines are one of the most common sources of misleading eval results.

## Segment results by metadata

Aggregate scores hide problems. An overall score of 0.85 can mask a score of 0.40 on a specific input type that matters to your users.

Add metadata to your test cases (topic, input category, user intent, etc.) and use [group by](/evaluate/interpret-results#group-results) in the experiments table to break down results by category. Sort by regressions to find which segments got worse.

<Tip>
  If you only ever look at aggregate scores, you will miss the most actionable signal in your evaluations.
</Tip>

## Next steps

* [Interpret results](/evaluate/interpret-results) from experiments
* [Compare experiments](/evaluate/compare-experiments) systematically
* [Write scorers](/evaluate/write-scorers) that measure what matters
* [Score production traces](/evaluate/score-online) to surface failures automatically
