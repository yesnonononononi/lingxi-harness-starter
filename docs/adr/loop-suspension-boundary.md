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
3. The runtime appends a generic system message recording that the previous execution was
   suspended before completion. It contains no business reason.
4. The active-execution registry addresses cooperative suspend/cancel signals and prevents two
   loops with the same execution id from running concurrently in one process. It stores no
   suspended or resumable record.
5. Resume starts a fresh model request from the caller-provided execution snapshot. Durable
   conversation storage, session-level distributed locking, business notifications, decisions and
   response endpoints belong to the consuming application.

## Consequences

- No runtime thread waits for a later response and no in-memory decision queue is required.
- A persisted execution can be resumed on another node when the application's repository and
  locking strategy support it; every run registers a fresh control signal.
- The framework deliberately cannot list pending questions, approve commands, apply timeouts to
  business decisions, or turn a response into a user message. Applications implement those
  workflows and then call the normal resume path.
- `CompletableFuture` may still be used internally to execute an already admitted batch of tools
  concurrently; it is not suspension state.
