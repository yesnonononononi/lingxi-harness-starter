# Framework capability audit (revised)

This is a static audit of the framework and LingXi business sources on 2026-10-09.
It does not establish delivery or successful runtime validation. The original audit
overstated framework gaps by treating existing extension points and business responsibilities
as missing framework capabilities.

## Corrections to the original findings

| Original finding | Source evidence |
| --- | --- |
| Events contain only execution identity, response identity and content. | `AgentEvent.eventMetaData()` already exists. Business events carry `sessionId` and `turnId`, and `ResponseStreamState` consumes them. |
| The framework cannot persist conversation messages. | `ConversationTranscriptSink` already exists, including response identity overloads. LingXi implements it in `DatabaseConversationTranscriptSink`. |
| MCP support consists only of abstractions. | `McpClientFactory`, `McpValidator` and `AgentScopeMcpProvider` exist. Business `McpManager` uses the framework client factory. |

An unused or incorrectly used extension point is different from a missing capability.
Business snapshot listeners publish on execution/context changes and publish tool blocks
at completion; they do not project every text fragment. This describes the coverage of
the business projection, rather than proving that a framework block view is broken.

## Reclassification of proposed changes

| Proposal | Responsibility |
| --- | --- |
| Move `ResponseStreamState` into the framework. | Runtime text offsets and model invocation order may belong in the framework. Database ordering within a business turn needs its own contract. |
| Move `BlockOrder` into the framework. | `responseOrder * 1000 + slot` combines runtime facts with product presentation rules and should remain business-owned. |
| Add offset/order fields to listeners. | Event facts belong in the framework; JSON conversion and SSE broadcast remain business adapters. |
| Add `ResponseStreamLoopInterceptor`. | The framework should expose positions from the model's tool request list directly. |
| Move the entire `TurnView` into the framework. | This is a product projection; the absence of a same-named framework concept is not a defect. |
| Remove frontend incremental merging. | Better event fields reduce inference, but snapshots and deltas still require reconciliation. |
| Resolve `executionId` to `turnId` in the framework. | First inspect use of the existing metadata channel and compatibility with historical data. |
| Move all business MCP code into the framework. | Configuration CRUD, validation and credential handling remain business concerns. Generic pooling and reload mechanisms can be assessed separately. |

## Candidate framework contracts

The goal is to avoid deriving runtime facts from event arrival order.

1. **Text offset:** count text and thinking separately when the framework emits each delta.
   Define offsets in UTF-16 code units to match Java and JavaScript string length.
2. **Model invocation order:** define stable order within an execution and its behavior across
   suspension/resume, failed calls and new executions. Invocation order must not be substituted
   directly for business `responseOrder`, which counts accepted, persisted AI messages in a turn.
   A framework invocation may never be accepted; mappings must be explicit and persisted.
3. **Tool request position:** allocate positions from the model's tool request list. Start and
   end events must reuse the same position regardless of concurrent start/completion order.
   `DefaultToolExecutionManager` already collects results in request order; the missing fact
   in the audited implementation was the position on live events.

The audited `RuntimeEventPublisher` wrapped an entire listener loop in one `try/catch`.
One failing listener therefore skipped later listeners. Isolate failures for optional display
listeners and give critical business actions explicit failure policies instead of swallowing all errors.

## Completion and persistence are separate boundaries

`COMPLETE_TEXT` and `AI_MESSAGE` were emitted before later acceptance and transcript writes
in the audited `StreamingModelResponseBehaveDecider` and `AgentLoopStepRunner`.
Model output completion does not establish successful business persistence. Document this
boundary; introduce an acceptance confirmation event only when a consumer needs it.

## Business ownership remains explicit

`TurnView`, product presentation, database tables/transactions/queries and persisted ordering,
SSE protocol/broadcast, frontend snapshot/delta reconciliation, and MCP configuration CRUD,
name validation, credentials and connection testing remain business responsibilities.

Adding event facts can reduce compensating logic. It does not automatically provide replay,
reconnection recovery, or a frontend that requires no reconciliation.
