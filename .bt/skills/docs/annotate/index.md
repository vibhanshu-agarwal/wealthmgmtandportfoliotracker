> ## Documentation Index
> Fetch the complete documentation index at: https://braintrust.dev/docs/llms.txt
> Use this file to discover all available pages before exploring further.

# Annotate and curate data

> Build datasets and gather feedback to improve your application

After observing your application in production, the next step is annotating and curating data to build evaluation datasets. This process transforms raw production logs into high-quality test cases that help you systematically improve your application.

## Why annotate

Annotation creates the ground truth data needed for evaluation. By collecting feedback, adding labels, and curating examples from production, you build datasets that:

* Represent real user interactions and edge cases
* Include expected outputs and quality assessments
* Enable systematic testing and comparison
* Support automated and human evaluation

Braintrust integrates annotation seamlessly with logs and experiments, making it easy to capture feedback and build datasets without context switching.

## Gather human feedback

[Human review](/annotate/human-review) provides qualitative assessments that complement automated scoring. Configure review scores in your project to collect:

* **Continuous scores**: Numeric ratings with slider controls (0-100%)
* **Categorical scores**: Predefined options with assigned values
* **Expected values**: Corrections showing what the output should be
* **Comments**: Free-form feedback and context

Review traces and provide structured scores to begin the annotation process. You can efficiently evaluate large batches with keyboard navigation, or use the kanban layout to visualize review progress across backlog, pending, and complete states.

For a lighter-weight alternative to the full review workflow, you can [annotate outputs directly in playgrounds](/evaluate/playgrounds#annotate-outputs) and then get prompt improvement suggestions based on your annotations.

## Create custom trace views

[Custom trace views](/annotate/custom-views) transform complex traces into interfaces anyone on your team can use. Describe what you want in natural language and Loop generates an interactive React component you can customize or embed anywhere.

Build custom views to:

* Create annotation interfaces for large-scale human review tasks
* Replace JSON with intuitive UI components for non-technical reviewers
* Display data in domain-specific formats (carousels, conversation threads, dashboards)
* Aggregate information across multiple spans in a trace

Custom views integrate with human review workflows, enable interactive annotation controls, and can be shared across your team or embedded in your own applications.

## Add labels and corrections

Beyond scores, you can [annotate spans](/annotate/labels) with:

* **Tags**: Categorize traces for organization and filtering
* **Comments**: Provide context or explain issues
* **Expected values**: Specify correct outputs
* **Metadata**: Add custom fields for analysis

These annotations flow between logs, datasets, and experiments, maintaining context throughout your workflow.

## Build datasets

[Datasets](/annotate/datasets) are versioned collections of test cases that you use to run evaluations. Each record contains:

* **Input**: The data sent to your application
* **Expected**: The ideal output (optional but recommended)
* **Metadata**: Tags, user IDs, or other contextual information

Create datasets from:

* Production logs with interesting patterns
* User feedback (thumbs up/down, corrections)
* Manual curation by subject matter experts
* Generated examples from Loop

## Export data

[Extract annotated data](/annotate/export) for use in:

* External evaluation frameworks
* Custom analysis pipelines
* Reporting and documentation
* Training data for fine-tuning

Export via the UI or programmatically through the API to integrate with your existing tools and workflows.

## Next steps

* [Add human feedback](/annotate/human-review) for your project
* [Create custom trace views](/annotate/custom-views) for tailored review workflows
* [Add labels and corrections](/annotate/labels) to traces
* [Build datasets](/annotate/datasets) from production logs
* [Capture user feedback](/instrument/user-feedback) in production
