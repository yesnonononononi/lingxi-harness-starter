package com.summit.runtime.model;

import com.summit.core.conversation.api.ChatResponseEntity;
import com.summit.core.model.ModelChatCommand;
import com.summit.core.model.ModelInvoker;
import com.summit.core.runtime.loop.suspension.ExecutionInterruptedException;
import com.summit.core.utils.RetryUtil;

import java.util.Objects;
import java.util.concurrent.CancellationException;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicReference;
import java.util.function.Supplier;

public abstract class AbstractInvoker implements ModelInvoker {

    protected abstract ChatResponseEntity action(ModelChatCommand command);
    @Override
    public ChatResponseEntity invoke(Supplier<ModelChatCommand> chatCommandSupplier) throws Exception {

        ModelChatCommand chatCommand = resolveCommand(chatCommandSupplier);

        if (!chatCommand.requireRetry()) return invokeAttempt(chatCommand);

        AtomicBoolean firstAttempt = new AtomicBoolean(true);
        AtomicReference<ModelChatCommand> currentAttempt = new AtomicReference<>(chatCommand);
        // Extracted so the behaviour stays explicit: the first attempt reuses the command that was
        // already resolved, later attempts resolve a brand new one from the supplier. The task is
        // a Supplier of RetryUtil.Task, so every retry hands the retry loop its own task instance.
        RetryUtil.Task<ChatResponseEntity> attempt = () -> {
            ModelChatCommand command = firstAttempt.getAndSet(false) ? chatCommand : resolveCommand(chatCommandSupplier);
            currentAttempt.set(command);
            return invokeAttempt(command);
        };
        RetryUtil.RetryConf<ChatResponseEntity> conf = ModelRequestFactory.buildRetryConfig(chatCommand,
                () -> attempt, currentAttempt::get);
        try {
            return RetryUtil.retry(conf);
        } catch (InterruptedException error) {
            Thread.currentThread().interrupt();
            throw new ExecutionInterruptedException("model retry interrupted", ExecutionInterruptedException.Kind.CANCEL, error);
        }
    }

    private ChatResponseEntity invokeAttempt(ModelChatCommand command) {
        if (command.signal() != null) command.signal().checkInterruption();
        if (Thread.currentThread().isInterrupted()) {
            throw new ExecutionInterruptedException(ExecutionInterruptedException.Kind.CANCEL, "model invocation interrupted");
        }
        if (command.beforeAttempt() != null) command.beforeAttempt().run();
        try {
            return action(command);
        } catch (RuntimeException error) {
            Throwable cause = ModelRetryPolicy.unwrap(error);
            if (cause instanceof CancellationException || cause instanceof InterruptedException) {
                if (cause instanceof InterruptedException) Thread.currentThread().interrupt();
                throw new ExecutionInterruptedException("model invocation cancelled", ExecutionInterruptedException.Kind.CANCEL, error);
            }
            throw error;
        }
    }

    private ModelChatCommand resolveCommand(Supplier<ModelChatCommand> chatCommandSupplier) {
        Objects.requireNonNull(chatCommandSupplier, "the supplier of the chatCommand cannot be null");
        return Objects.requireNonNull(chatCommandSupplier.get(), "chat command cannot be null");

    }
}
