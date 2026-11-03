# Context compaction

Model summaries remain the preferred path. Local pruning is the fallback when no summary model is
configured, summarization fails, or its attempt budget is exhausted. A failed summary closes the
summary path for that execution. A successful summary resets the attempt window only after the
context falls below the configured summary threshold.

## Two fallback stages

Both stages preserve every system message, the application attachment supplied by
`ContextAttachmentProvider`, every user message, and all nonempty assistant text. System messages
lead the rebuilt context; other retained messages keep their relative order.

The latest user turn starts at the last user message and includes all subsequent assistant messages
and their tool results. Earlier turns retain their user messages and assistant text in order.

1. **Historical text:** remove historical tool calls, including their arguments, and their matching
   results together. Preserve the complete latest user turn. No tool summaries or placeholders are
   generated. Empty historical assistant messages are removed after their calls disappear.
2. **Reduced latest turn:** if the candidate still reaches the compaction threshold and the latest
   user turn accounts for **strictly more than half** its tokens, reduce that turn as well. Retain
   its user message, all earlier assistant text, the last assistant message's text and complete calls,
   and every result belonging to those calls, including parallel calls. If the last assistant has no
   calls, no earlier tool group is retained. Otherwise, when the latest turn is half or less of the
   candidate, fail without attempting this reduction.

The complete candidate is counted through the configured tokenizer, including protected application
state. It must fall **below** `AgentConfig.compactionThreshold()` (the configured model-summary
threshold, default `0.85` of `maxTokens`); equality still requires compaction. If stage two remains at
or above that threshold, `TokenBudgetExceededException` stops execution before the main model is
invoked and recommends increasing the context budget or reducing protected input. An unsuccessful
prune publishes its completion event but changes neither messages nor stage. There is no local
output cap or recent-group count, and retained call arguments and outputs are never truncated.

## Protocol and state invariants

- Pair results by call ID rather than adjacency. Orphan results are removed; duplicate call IDs,
  duplicate results, and results preceding their calls are rejected.
- An unanswered call defers the entire rewrite so its signed prefix remains unchanged. It still
  undergoes the threshold check; an oversized pending group fails without losing its calls.
- A rewritten completed context clears assistant thinking and attributes, including stale thinking
  signatures. Source message objects and raw snapshots are not mutated.
- Pruning re-evaluates the latest user boundary, token total, and latest-turn share on every pass,
  including after snapshot restoration. A previous stage-two reduction never forces tool removal
  from a new user turn. The persisted `CompactionState.pruneGeneration` records the highest stage
  reached (0, 1, or 2) until a successful summary rebuild resets it.
- Summary attempts are counted before invocation. A successful summary still above the hard input
  budget is summarized again within the same checkpoint, subject to the attempt limit, before
  falling back to pruning. An oversized request cannot escape between those attempts.
- Every prune attempt emits paired `PRUNE` start/completion events, including no-op and failure
  paths.

## Configuration and validation

Each request's resolved `ModelConfig` can select the summary connection explicitly:

```java
ModelConfig reuseCurrent = ModelConfig.builder()
        .baseUrl("https://models.example/v1").apiKey("request-key").modelName("main-model")
        .reuseModelForCompaction(true).build();
ModelConfig separate = reuseCurrent.toBuilder().reuseModelForCompaction(false)
        .compactModel(new ModelConfig.CompactModelConfig(
                "https://summaries.example/v1", "summary-key", "summary-model")).build();
```

`reuseModelForCompaction` takes precedence over `compactModel`. Reuse preserves the request's model
options and maps built-in streaming providers to their synchronous counterparts. An independent
connection requires all three fields and selects its protocol from the model name. Its timeout,
token limit and retry count follow the parent configuration; thinking is disabled. Legacy requests
with neither setting retain the application compact model. Both automatic and tool-requested
compaction resolve the exact `modelConfigSpec` revision; unavailable revisions fail rather than
substituting application credentials. Tool-requested compaction loads the originating execution
through `ExecutionRepository`. Credentials remain in the configuration provider, not its snapshot.

`lingxi.agent.runtime.tool.context-compact.max-summary-attempts` still controls the summary attempt limit.
`prune-target-ratio`, `full-segment-count`, and `full-tool-output-cap` have been removed, along with
their Java policy fields. Applications should remove those obsolete properties.

Regression tests cover retention, parallel results, both stages, repeated passes, snapshot restore,
protected application state, pending calls, terminal overflow, and the actual loop entry boundary.
Wire tests send both stages through the real Anthropic and OpenAI adapters into local HTTP
endpoints. These tests inspect SDK payloads; they do not establish remote-provider acceptance.
