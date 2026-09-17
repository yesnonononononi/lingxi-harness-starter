# ADR: the loop suspension boundary

- **Status:** accepted (rationale), with an open follow-up list — see below
- **Scope:** `harness-core`, `harness-runtime`
- **Related SPI:** `LoopSuspender`, `ToolExecutionPolicy`, `ToolInterceptor`, `AgentLoopHook`

## Context

Human-in-the-loop steps — approve a plan, approve a command, answer a question — all block the
agent loop until an external actor decides. The framework has two different places where code can
run around a tool call, and they sit on opposite sides of one line:

```
model asks for a tool
    -> ToolExecutionPolicy#beforeExecution      <- OUTSIDE the tool timeout
    -> executeTool()
         -> CompletableFuture.supplyAsync(...)
              -> ToolInterceptor / ToolExecutor <- INSIDE the tool timeout
         -> future.get(toolDefinition.timeout())
```

`DefaultToolExecutionManager` wraps the interceptor chain and the tool body in one future with the
tool's own timeout, so any blocking there consumes the tool's execution budget. Policies are
evaluated before that future is created.

The suspension timeout is per request: `SuspensionRequest#timeout` (5 minutes when omitted). The
application chooses it per topic — for example 30 minutes for a plan approval and 10 minutes for a
command approval.

## Decision

1. **Human approval is implemented with `ToolExecutionPolicy` + `LoopSuspender`.** A feature that
   needs a human decision must not be built as a `ToolInterceptor`: the interceptor runs inside the
   tool timeout, so the review time would be charged to the tool and the call would "time out"
   while a person is still reading it.
2. **`ToolExecutionPolicy` and `ToolInterceptor` stay separate.** They are orthogonal — admission
   before the timeout versus post-processing inside it. Merging them back into a single chain
   re-introduces the defect above; this was already observed once with the removed
   `CommandApprovalToolInterceptor`.
3. **The suspension timeout is a request parameter, not a framework constant.** Different topics
   legitimately need different windows, and the framework must not know which topic is which.
4. **Topics and payloads are application-defined.** `SuspensionRequest#topic` + `#payload` carry
   the business meaning; the framework never branches on them.
5. **Business notification shapes stay in the application.** The framework offers
   `RuntimeEventPublisher` for its own lifecycle events; a "plan card" or a "command approval card"
   is the application's own protocol.

## Consequences

- The framework contains no plan/choice/approval domain: migrating those concepts out stays
  possible and is enforced by `ArchitectureBoundaryTest`.
- Applications own their approval rules, payloads, endpoints and push format, and reuse the same
  `LoopSuspender` bean for every approval-like flow.
- The default `InMemoryLoopSuspender` is enough for single-node development, and it is the only
  implementation shipped today.

## Known limitations of `InMemoryLoopSuspender` (not implemented)

These are real gaps, listed here so they are not mistaken for features:

- **No pending-suspension listing.** `find(id)` exists, but there is no `list(topic)` /
  `list(sessionId)`, so an approval inbox or a page reload cannot re-hydrate what is waiting.
- **Timeouts are silent.** `suspend` returns `SuspensionDecision.Status.TIMED_OUT` to the caller
  but publishes no event, so a UI card can stay "pending" forever.
- **Duplicate ids are reused.** `suspend` keeps an existing pending entry for the same id, and both
  waiters remove it in their `finally` blocks; a repeated id is not rejected.
- **Business key and suspension id are the same string.** Callers pass e.g. a tool-execution id as
  the suspension id, so the two roles cannot be tracked independently.
- **Single node only.** Restarting the application loses every pending suspension, and a decision
  can only be delivered on the instance that is waiting.

## Planned SPI refinements (not implemented)

1. Replace the per-feature pending events with one generic notification, e.g.
   `SuspensionPendingEvent(suspensionId, executionId, sessionId, topic, payload, createdAt,
   expiresAt)`, so clients dispatch on `topic` instead of on event type.
2. Let the suspender publish that notification itself, collapsing
   `suspend(request, callback)` into `suspend(request)` at every call site.
3. Add `list(topic)` / `list(sessionId)` and publish a resolved/timed-out event.
4. Split the business key from the suspension id in `SuspensionRequest`.
5. Provide a durable implementation (Redis or database) with idempotent `resolve` / `cancel`,
   timeout recovery, multi-instance safety and post-restart query support. `InMemoryLoopSuspender`
   stays as the development default.

None of the above is required to use the framework today.
