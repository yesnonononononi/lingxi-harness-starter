# ADR: the loop suspension boundary

- **Status:** accepted
- **Scope:** `harness-core`, `harness-runtime`
- **Related API:** `ToolResultType.PROMISE`, `ExecutionControlSignal`, `ActiveExecutionRegistry`

## Context

The runtime needs a cooperative way to stop a loop without losing an already produced tool-call
round. The reason can come from any application workflow, but that meaning must not become part of
the framework model. Blocking the loop on a `CompletableFuture` also binds pending state to one JVM
and keeps an execution thread alive unnecessarily.

## Decision

1. A tool or `ToolExecutionPolicy` returns `ToolExecuteResult.promise(output)` to request
   suspension. `PROMISE` describes loop control only; it does not mean question, approval, human
   input, or any other domain operation.
2. The runner collects the full tool batch and commits the assistant tool calls plus all matching
   tool results before returning `LoopResult.SUSPENDED`.
3. Suspension does not append a system instruction or modify the committed conversation.
   The execution state records suspension; the application supplies any later user response.
4. The active-execution registry addresses cooperative suspend/cancel signals and prevents two
   loops with the same execution id from running concurrently in one process. ExecutionRepository
   combines this control contract with isolated snapshots; the default implementation serializes
   recoverable executions in memory and removes them at a terminal state.
5. Resume starts a fresh model request from the caller-provided or repository-loaded snapshot,
   copying its messages into a mutable list while preserving model-attempt budget. Durable
   conversation storage, session-level distributed locking, business notifications, decisions and
   response endpoints belong to the consuming application. The runtime freezes
   `execution.messages` with `List.copyOf` on every exit from the processor (normal, cancelled,
   failed and suspended alike); `SUSPENDED` is not a terminal state, but its snapshot is frozen by
   the same `finally` block and is therefore equally immutable. Feedback meant for the next attempt
   must be injected through `ConversationManager.appendUserMessage(...)`, through
   `LoopContext.appendMessage(...)` from a
   `LoopInterceptor.onBeforeModelInvoke` hook, or by building a fresh `Execution`. Mutating the
   returned `messages` list directly is unsupported and throws.

## Consequences

- No runtime thread waits for a later response and no in-memory decision queue is required.
- A persisted execution can be resumed on another node when the application's repository and
  locking strategy support it; every run registers a fresh control signal. The default
  `ExecutionRepository` is an in-process, non-persistent implementation
  (`InMemoryActiveExecutionRegistry`) that keeps snapshots in memory and drops them at a terminal
  state, so cross-node resume requires the application to supply a shared, persistent
  `ExecutionRepository`.
- The framework deliberately cannot list pending questions, approve commands, apply timeouts to
  business decisions, or turn a response into a user message. Applications implement those
  workflows and then call the normal resume path.
- `CompletableFuture` may still be used internally to execute an already admitted batch of tools
  concurrently; it is not suspension state.
