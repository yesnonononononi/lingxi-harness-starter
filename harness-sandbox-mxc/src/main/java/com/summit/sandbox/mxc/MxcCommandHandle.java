package com.summit.sandbox.mxc;

import com.summit.core.runtime.CommandHandler;
import com.summit.core.runtime.workspace.WorkspaceCommands.CommandResult;
import java.io.IOException;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;

/** Token cancellation settles the sandbox before publishing a stopped command. */
final class MxcCommandHandle implements CommandHandler {
    @FunctionalInterface interface Cancellation { void cancel() throws IOException, InterruptedException; }
    private final CompletableFuture<CommandResult> completion = new CompletableFuture<>();
    private final CommandHandler host;
    private final MxcOutputCollector output;
    private final Cancellation cancellation;
    private final Object stopLock = new Object();

    MxcCommandHandle(CommandHandler host, MxcOutputCollector output, Cancellation cancellation) {
        this.host = host;
        this.output = output;
        this.cancellation = cancellation;
        Thread.ofVirtual().start(() -> {
            try { completion.complete(output.finish(host.await())); }
            catch (Exception failure) {
                if (!output.settled()) {
                    try { cancellation.cancel(); }
                    catch (Exception cleanup) { failure.addSuppressed(cleanup); }
                }
                completion.completeExceptionally(failure);
            }
        });
    }

    @Override public String outputSnapshot() { return output.snapshot(); }
    @Override public boolean isDone() { return completion.isDone(); }
    @Override public CommandResult await() throws IOException, InterruptedException {
        try { return completion.get(); }
        catch (ExecutionException failure) { throw unwrap(failure); }
    }

    @Override public StopResult stop() throws IOException, InterruptedException {
        synchronized (stopLock) {
            if (completion.isDone()) return new StopResult(false, await());
            cancellation.cancel();
            CommandResult result;
            try { result = completion.get(10, TimeUnit.SECONDS); }
            catch (ExecutionException failure) { throw unwrap(failure); }
            catch (TimeoutException failure) {
                IOException timeout = new IOException("MXC supervisor did not confirm cancellation", failure);
                try { host.stop(); } catch (Exception cleanup) { timeout.addSuppressed(cleanup); }
                throw timeout;
            }
            return new StopResult(result.exitCode() != 0 && !result.timedOut(), result);
        }
    }

    private IOException unwrap(ExecutionException failure) {
        return failure.getCause() instanceof IOException error ? error
                : new IOException("MXC command failed", failure.getCause());
    }
}