# Response identity

`responseId` is a positive decimal 64-bit ID carried as a `String` throughout model responses,
runtime events, tool commands/executions and `ConversationTranscriptSink`. Compare IDs numerically
(for example with Java `Long.parseLong` or JavaScript `BigInt`), not lexicographically or as a
JavaScript `Number`.

The runtime allocates an ID before every model invocation. IDs strictly increase within an
execution, including interrupted, failed and compaction rounds. `Execution.lastResponseId` is part
of execution snapshots; restoring a snapshot supplies its ID as the generator's lower bound.
Identity is allocated once and reused by every event and transcript record of that invocation.
Provider response IDs remain separate in `ChatResponseEntity.Meta.id`.

The default generator uses a 2020 UTC epoch, 41 timestamp bits, 10 worker bits and 12 sequence bits.
It advances logical time on clock rollback or sequence overflow. Spring uses worker 0 by default:

```properties
lingxi.agent.runtime.response-id.worker-id=0
```

Use distinct worker IDs (0 through 1023) for instances sharing response storage. Do not reuse a
worker ID while its previous generator can still run or until its last logical timestamp has
passed. This is a deployment responsibility; the framework does not allocate distributed worker
leases. Worker 0 is shared by direct runtime builders within a JVM.

Applications can supply a `ResponseIdGenerator` bean, or set it on `DefaultRuntimeFactory` or
`RuntimeContext`. Its output must be unique, canonical positive decimal IDs fitting in a signed
`long`, and strictly greater than the supplied previous ID; `Execution` rejects regressions.

Ordering is response creation order within an execution, not delivery order, completion order,
or a global causal order across workers. It does not describe text offsets or the order of tools
within a response. Durable recovery still depends on persisting and restoring execution snapshots.

This iteration changes the Java response identity type from `UUID` to `String`. Consuming transcript
sinks, tool integrations and storage mappings must use the new type; existing UUID identities have
no numeric ordering contract and are not converted automatically. No `responseIndex` field is
introduced.

Partial text and thinking events carry an `int offset`: the zero-based start position in UTF-16
code units. Each channel counts independently from zero for each response.

`ToolCallRequest.requestIndex` is the zero-based position in the model response's original tool-call
list. The LangChain4j adapter assigns it; other adapters and manually built requests must supply it.
Tool start/end events and execution results retain this position, including rejection, failure,
promise and timeout outcomes. Concurrent completion order does not change the request position.
