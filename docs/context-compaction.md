# Context compaction

Model summaries remain the preferred path. Local pruning is the fallback when no summary model is
configured, summarization fails, or its attempt budget is exhausted. A failed summary closes the
summary path for that execution. A successful summary resets the attempt window only after the
context falls below the configured summary threshold.

## Two fallback stages

Both stages preserve every system message, the application attachment supplied by
`ContextAttachmentProvider`, every user message, and all nonempty assistant text. System messages
lead the rebuilt context; other retained messages keep their relative order.

The latest working group is the last assistant message and every result belonging to its tool call
IDs, including parallel calls. Its text, call names, arguments, and complete tool results survive
both stages. If the last assistant has no calls, its preceding tool groups are historical.

1. **Skeleton history:** preserve historical assistant calls and replace their result payloads with
   small `ToolMessageEntity` skeletons. IDs, tool names, error flags, and exit codes remain intact;
   assistant call arguments remain available. Skeleton text is derived from metadata, never parsed
   from untrusted output or an earlier placeholder.
2. **Text-only history:** if stage one exceeds `AgentConfig.maxTokens`, remove every historical call
   and its matching result together. Keep all historical assistant text and the latest working group.
   Empty historical assistant messages are removed after their calls disappear.

There is no local output cap, recent-group count, or percentage target. The complete candidate is
counted through the configured tokenizer. A candidate exactly at the input budget is accepted.
If stage two still exceeds that budget, `TokenBudgetExceededException` stops execution before the
main model is invoked and recommends increasing the context budget or reducing protected input.
An unsuccessful prune publishes its completion event but changes neither messages nor stage.

## Protocol and state invariants

- Pair results by call ID rather than adjacency. Orphan results are removed; duplicate call IDs,
  duplicate results, and results preceding their calls are rejected.
- An unanswered call defers the entire rewrite so its signed prefix remains unchanged. It still
  undergoes the hard budget check; an oversized pending group fails without losing its calls.
- A rewritten completed context clears assistant thinking and attributes, including stale thinking
  signatures. Source message objects and raw snapshots are not mutated.
- Pruning runs again as new groups accumulate. Stage two stays active across later passes and
  snapshot restoration until a successful summary rebuild resets it. The persisted
  `CompactionState.pruneGeneration` field carries stage 0, 1, or 2.
- Summary attempts are counted before invocation. A successful summary still above the hard input
  budget is summarized again within the same checkpoint, subject to the attempt limit, before
  falling back to pruning. An oversized request cannot escape between those attempts.
- Every prune attempt emits paired `PRUNE` start/completion events, including no-op and failure
  paths.

## Configuration and validation

`lingxi.agent.runtime.tool.context-compact.max-summary-attempts` still controls the summary attempt limit.
`prune-target-ratio`, `full-segment-count`, and `full-tool-output-cap` have been removed, along with
their Java policy fields. Applications should remove those obsolete properties.

Regression tests cover retention, parallel results, both stages, repeated passes, snapshot restore,
protected application state, pending calls, terminal overflow, and the actual loop entry boundary.
Wire tests send both stages through the real Anthropic and OpenAI adapters into local HTTP
endpoints. These tests inspect SDK payloads; they do not establish remote-provider acceptance.
