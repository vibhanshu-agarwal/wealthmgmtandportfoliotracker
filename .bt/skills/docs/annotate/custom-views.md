> ## Documentation Index
> Fetch the complete documentation index at: https://braintrust.dev/docs/llms.txt
> Use this file to discover all available pages before exploring further.

# Create custom trace views

> Transform complex traces into tailored interfaces

Custom trace views transform complex traces into interfaces anyone on your team can use. Describe what you want in natural language and [<Icon icon="blend" /> **Loop**](/loop) generates an interactive React component you can customize or embed anywhere.

## Common use cases

<AccordionGroup>
  <Accordion title="Human review and annotation">
    Build custom annotation interfaces for large-scale human review tasks, surfacing only relevant information for annotators and subject matter experts.
  </Accordion>

  <Accordion title="Simplifying traces for non-engineers">
    Replace JSON with intuitive UI components like carousels, playlists, or structured summaries to make traces accessible to PMs, legal reviewers, and domain experts.
  </Accordion>

  <Accordion title="Industry-specific visualizations">
    Create views that mirror your product experience:

    * Playlist-style views for music applications
    * Interactive source-and-answer layouts
    * Custom dashboards for internal evaluations
  </Accordion>

  <Accordion title="Multi-turn conversation analysis">
    Aggregate and display data across conversation turns to analyze dialogue flow and long-running interactions.
  </Accordion>
</AccordionGroup>

## Create trace views

To create a custom trace view using [<Icon icon="blend" /> **Loop**](/loop):

1. Select a trace in your logs, experiments, or during human review.
2. Select <Icon icon="layers-2" /> **Views**.
3. Describe how you want to view your trace data.

After <Icon icon="blend" /> **Loop** generates your view, refine the view by describing additional changes or [edit the React component code](#edit-trace-view-react-code) directly.

Example prompts:

* "Create a view that renders a list of all tools available in this trace and their outputs"
* "Build an interface to review each trace one by one with easy switching between traces"
* "Create a conversation-style view that highlights user messages and assistant responses"
* "Render the video url from the trace's metadata field and show simple thumbs up/down buttons"

<Note>
  Self-hosted deployments: If you restrict outbound access, allowlist `https://www.braintrustsandbox.dev` to enable custom views. This domain hosts the sandboxed iframe that securely renders custom view code.
</Note>

## Share trace views

By default, a custom trace view is only visible and editable by the user who created it. To share your view with all users in the project:

1. Select **Save** in the view editor.
2. Choose **Save as new view version**.
3. Select **Update** to make it available project-wide.

All team members can then use the shared view when reviewing traces. Custom views integrate with Braintrust workflows — use them during [human review](/annotate/human-review), write annotations that flow into [datasets](/annotate/datasets), and combine with [Loop](/loop) for analysis.

## Edit trace view React code

Custom trace views are React components that run inside Braintrust. You can edit the component code directly to customize behavior beyond what Loop generates.

To edit the React code:

1. Go to the custom trace view.
2. Select <Icon icon="ellipsis" /> in the lower left of the view.
3. Select **Edit**.

Your React component receives the following props:

| Prop         | Type     | Description                                                                                                        |
| ------------ | -------- | ------------------------------------------------------------------------------------------------------------------ |
| `trace`      | object   | Contains all spans and methods for the trace. Attachment URLs in span data are automatically signed for rendering. |
| `span`       | object   | The currently selected span with full data                                                                         |
| `update`     | function | Update span metadata: `update('field', value)`                                                                     |
| `selectSpan` | function | Navigate to a different span: `selectSpan(spanId)`                                                                 |

The `trace` object includes:

* `rootSpanId`, `selectedSpanId` - Current span context
* `spanOrder` - All span IDs in execution order
* `spans` - Map of span\_id → span (IDs/relationships only)
* `fetchSpanFields` - Fetch full data for multiple spans (see [Access data from multiple spans](#access-data-from-multiple-spans))

The component can be copied and embedded in your own applications, enabling you to:

* Reuse custom views outside of Braintrust
* Integrate review interfaces into internal tools
* Build standalone annotation applications
* Create consistent review experiences across different contexts

### Add interactive controls

Custom views support interactive elements that write data back to traces. Add buttons, inputs, or custom controls to collect:

* Human review scores
* Thumbs up/down feedback
* Custom metadata fields
* Annotation notes

Use the `update` function to write metadata back to the trace. This enables annotation workflows where review and data collection happen in the same interface.

```javascript title="Example: Add thumbs up/down buttons" theme={"theme":{"light":"github-light","dark":"github-dark-dimmed"}}
function FeedbackView({ trace, span, update }) {
  const handleFeedback = (isPositive) => {
    update('metadata', {
      ...span.metadata,
      user_feedback: isPositive ? 'positive' : 'negative',
      reviewed_at: new Date().toISOString(),
    });
  };

  return (
    <div className="p-4">
      <h3 className="text-lg font-semibold mb-4">Review this output</h3>
      <pre className="bg-slate-100 p-2 rounded mb-4">
        {JSON.stringify(span.output, null, 2)}
      </pre>
      <div className="flex gap-2">
        <button onClick={() => handleFeedback(true)}>👍 Good</button>
        <button onClick={() => handleFeedback(false)}>👎 Bad</button>
      </div>
    </div>
  );
}

module.exports = FeedbackView;
```

### Access data from multiple spans

By default, only the selected span has full data (input, output, expected, metadata). To access data from other spans, use `fetchSpanFields`:

```javascript theme={"theme":{"light":"github-light","dark":"github-dark-dimmed"}}
// Fetch all fields for one span
const data = await trace.fetchSpanFields(spanId);

// Fetch specific fields for multiple spans
const data = await trace.fetchSpanFields(trace.spanOrder, ['input', 'output']);
```

```javascript title="Example: Display all span inputs" theme={"theme":{"light":"github-light","dark":"github-dark-dimmed"}}
function AllInputsView({ trace, span }) {
  const [spanData, setSpanData] = React.useState(null);
  const [loading, setLoading] = React.useState(true);
  const [error, setError] = React.useState(null);

  React.useEffect(() => {
    if (!trace?.fetchSpanFields) return;

    trace.fetchSpanFields(trace.spanOrder, ['input'])
      .then(setSpanData)
      .catch((err) => {
        console.error('Failed to fetch span data:', err.message);
        setError(err.message);
      })
      .finally(() => setLoading(false));
  }, [trace]);

  if (loading) return <div className="p-4">Loading...</div>;
  if (error) return <div className="p-4 text-red-600">Error: {error}</div>;

  return (
    <div className="p-4 space-y-2">
      {trace.spanOrder.map((id) => (
        <pre key={id} className="text-xs bg-slate-100 p-2 rounded text-wrap">
          {JSON.stringify(spanData?.[id]?.input, null, 2)}
        </pre>
      ))}
    </div>
  );
}

module.exports = AllInputsView;
```

### Render attachments

[Attachments](/instrument/attachments) (images, videos, audio, and other binary data) logged in your traces can be displayed directly in custom views. When span data is fetched, Braintrust automatically converts attachment references to `inline_attachment` objects with pre-signed URLs ready for rendering.

Attachments in span data are automatically transformed into objects with this structure:

```typescript theme={"theme":{"light":"github-light","dark":"github-dark-dimmed"}}
{
  type: "inline_attachment",
  src: "https://signed-url...",      // Pre-signed URL ready to use
  content_type: "image/jpeg",        // MIME type
  filename: "example.jpg",           // Optional filename
  data?: string                      // Pre-fetched text content (JSON, text, CSV, XML, Markdown attachments)
}
```

The `type` field identifies the object as an attachment, `src` contains a pre-signed URL that works directly in image, video, or audio tags, and `content_type` indicates the media type.

For text-based attachment references (JSON, plain text, CSV, XML, and Markdown) that Braintrust signs on the viewer's behalf, Braintrust pre-fetches the content and populates the `data` field with the text string. Structured `inline_attachment` objects you log directly with an `http` or `data:` URL in `src` are passed through unchanged and will not have `data` populated. When `data` is present, render it directly instead of loading from `src`.

<Note>
  Size limits apply to pre-fetching: 1MB per individual attachment and 5MB in aggregate across the entire payload being processed. The initial custom view load signs the full `{trace, span}` object (which includes all spans in the trace), and each `fetchSpanFields` call signs all spans in that response together. Once the 5MB aggregate is reached within a single payload, remaining text attachments will have `data` omitted even if each individual attachment is under 1MB. The content is still accessible via `src` in all cases.
</Note>

For example, the following code creates an input/output verification view that automatically detects and renders attachments alongside regular data:

```javascript title="Example: Input/output verification" expandable theme={"theme":{"light":"github-light","dark":"github-dark-dimmed"}}
function InputOutputVerification({ trace, span }) {
  // Helper to check if a value is an attachment
  const isAttachment = (value) => {
    return value && typeof value === 'object' && value.type === 'inline_attachment' && value.src;
  };

  // Helper to render a value, handling attachments and regular data
  const renderValue = (value, label) => {
    if (!value && value !== 0 && value !== false) {
      return (
        <div className="text-slate-400 italic">No {label.toLowerCase()}</div>
      );
    }

    // Check if it's an attachment
    if (isAttachment(value)) {
      const { src, filename, content_type, data } = value;
      if (content_type?.startsWith('image/')) {
        return (
          <div className="space-y-2">
            <img src={src} alt={filename || 'attachment'} className="max-w-full rounded-lg border border-slate-200 shadow-sm" />
            {filename && <p className="text-xs text-slate-500">{filename}</p>}
          </div>
        );
      }
      if (content_type?.startsWith('video/')) {
        return <video src={src} controls className="max-w-full rounded-lg" />;
      }
      if (content_type?.startsWith('audio/')) {
        return <audio src={src} controls className="w-full" />;
      }
      // For text-based attachments, use pre-fetched data when available
      if (data !== undefined) {
        const isJson = content_type === 'application/json' || filename?.endsWith('.json');
        let displayText = data;
        if (isJson) {
          try { displayText = JSON.stringify(JSON.parse(data), null, 2); } catch (_) {}
        }
        return (
          <div className="space-y-1">
            {filename && <p className="text-xs text-slate-500">{filename}</p>}
            <pre className="text-sm bg-slate-50 p-3 rounded border border-slate-200 overflow-auto whitespace-pre-wrap">{displayText}</pre>
          </div>
        );
      }
      return <a href={src} download={filename} className="text-blue-600 underline">{filename || 'Download attachment'}</a>;
    }

    // Handle objects that might contain attachments
    if (typeof value === 'object') {
      const entries = Object.entries(value);
      return (
        <div className="space-y-3">
          {entries.map(([key, val]) => (
            <div key={key}>
              <div className="text-xs font-semibold text-slate-600 uppercase mb-1">{key}</div>
              <div className="pl-2 border-l-2 border-slate-200">
                {renderValue(val, key)}
              </div>
            </div>
          ))}
        </div>
      );
    }

    // Handle strings and primitives
    if (typeof value === 'string') {
      return <div className="whitespace-pre-wrap text-slate-800">{value}</div>;
    }

    return <pre className="text-sm bg-slate-50 p-3 rounded border border-slate-200 overflow-auto">{JSON.stringify(value, null, 2)}</pre>;
  };

  const input = span?.data?.input;
  const output = span?.data?.output;
  const expected = span?.data?.expected;

  return (
    <div className="w-[100vw] min-h-screen bg-slate-50 p-6">
      <div className="max-w-7xl mx-auto">
        <h1 className="text-2xl font-bold text-slate-900 mb-6">Input & Output Verification</h1>

        <div className="grid grid-cols-1 lg:grid-cols-2 gap-6">
          {/* Input Section */}
          <div className="bg-white rounded-lg shadow-sm border border-slate-200 p-6">
            <div className="flex items-center gap-2 mb-4">
              <div className="w-2 h-2 bg-blue-500 rounded-full"></div>
              <h2 className="text-lg font-semibold text-slate-900">Input</h2>
            </div>
            <div className="space-y-4">
              {renderValue(input, 'Input')}
            </div>
          </div>

          {/* Output Section */}
          <div className="bg-white rounded-lg shadow-sm border border-slate-200 p-6">
            <div className="flex items-center gap-2 mb-4">
              <div className="w-2 h-2 bg-green-500 rounded-full"></div>
              <h2 className="text-lg font-semibold text-slate-900">Output</h2>
            </div>
            <div className="space-y-4">
              {renderValue(output, 'Output')}
            </div>
          </div>
        </div>

        {/* Expected Section (if exists) */}
        {expected && (
          <div className="mt-6 bg-white rounded-lg shadow-sm border border-slate-200 p-6">
            <div className="flex items-center gap-2 mb-4">
              <div className="w-2 h-2 bg-amber-500 rounded-full"></div>
              <h2 className="text-lg font-semibold text-slate-900">Expected</h2>
            </div>
            <div className="space-y-4">
              {renderValue(expected, 'Expected')}
            </div>
          </div>
        )}

        {/* Metadata Section */}
        {span?.data?.metadata && Object.keys(span.data.metadata).length > 0 && (
          <div className="mt-6 bg-white rounded-lg shadow-sm border border-slate-200 p-6">
            <div className="flex items-center gap-2 mb-4">
              <div className="w-2 h-2 bg-purple-500 rounded-full"></div>
              <h2 className="text-lg font-semibold text-slate-900">Metadata</h2>
            </div>
            <div className="space-y-4">
              {renderValue(span.data.metadata, 'Metadata')}
            </div>
          </div>
        )}

        {/* Scores Section */}
        {span?.data?.scores && Object.keys(span.data.scores).length > 0 && (
          <div className="mt-6 bg-white rounded-lg shadow-sm border border-slate-200 p-6">
            <div className="flex items-center gap-2 mb-4">
              <div className="w-2 h-2 bg-rose-500 rounded-full"></div>
              <h2 className="text-lg font-semibold text-slate-900">Scores</h2>
            </div>
            <div className="grid grid-cols-2 md:grid-cols-4 gap-4">
              {Object.entries(span.data.scores).map(([name, score]) => (
                <div key={name} className="bg-slate-50 rounded p-3 border border-slate-200">
                  <div className="text-xs text-slate-600 mb-1">{name}</div>
                  <div className="text-2xl font-bold text-slate-900">
                    {typeof score === 'number' ? score.toFixed(2) : score}
                  </div>
                </div>
              ))}
            </div>
          </div>
        )}
      </div>
    </div>
  );
}

module.exports = InputOutputVerification;
```

<img src="https://mintcdn.com/braintrust/VHJCPJaM4wE4c5d4/images/annotate/render-attachments.png?fit=max&auto=format&n=VHJCPJaM4wE4c5d4&q=85&s=634561733af09547eb8b2bafa9caaff9" alt="Input/output verification example" width="2056" height="1300" data-path="images/annotate/render-attachments.png" />

<Warning>
  **Custom view PDF limitation:** PDF files referenced as `ExternalAttachment` objects cannot be rendered in custom views due to browser-level security restrictions in the sandboxed iframe environment. While images and videos work correctly, browsers block PDF rendering in this context.

  To display PDFs in custom views, upload them as standard `Attachment` objects or host them externally and link to them instead of attempting inline rendering.
</Warning>

## Duplicate trace views

Duplicate custom views to reuse them across different projects or organizations. This lets you create a view once and apply the same interface to traces in multiple projects without rebuilding from scratch.

To duplicate a custom view:

1. Open the custom view you want to duplicate.
2. Select <Icon icon="ellipsis" /> in the lower left of the view.
3. Select **Duplicate**.
4. Give the new view a name.
5. Choose the target organization and project.
6. Select **Duplicate** to create a copy in the selected project.

The duplicated view includes all React component code and configuration, creating an independent copy you can modify without affecting the original.

## Rename trace views

To rename a custom trace view:

1. Go to the custom trace view.
2. Select <Icon icon="ellipsis" /> in the lower left of the view.
3. Select **Rename**.
4. Enter a new name for the view.
5. Select **Save** to save the changes.

## Access version history

Every time you save changes to a custom trace view, Braintrust creates a new version with a timestamp. To view or switch between previous versions:

1. Go to the custom trace view.
2. Select <Icon icon="ellipsis" /> in the lower left of the view.
3. Select **Version** to see all past versions.
4. Choose any previous version to switch to it.

The current version is marked with a **Latest** label.

## Next steps

* [Add human feedback](/annotate/human-review)
* [Build datasets](/annotate/datasets) from annotated traces
* [Learn about Loop](/loop) for AI-assisted development
