package com.summit.core.transaction;

import java.util.concurrent.Callable;

/**
 * Where the runtime asks the application for a transaction.
 *
 * <p>The runtime decides <em>what</em> has to be atomic; only the application knows whether it runs
 * behind a transaction manager. An application without one answers {@link #direct()}, which runs
 * the callback where it stands and joins nothing — the runtime must stay usable outside Spring and
 * without a database.</p>
 */
public interface RuntimeTransactionalSupport {
    <T>T execute(Callable<T> callback);
    /**
     * Run the callback under the given propagation behavior.
     *
     * <p>No default is offered on purpose: a support that cannot honour a propagation behavior must
     * say so at the call site, not answer {@code null} and turn the omission into an unrelated
     * {@link NullPointerException} later.</p>
     */
    default <T>T executeWith(Callable<T> callable,int propagationBehavior){
        throw new UnsupportedOperationException(
                "This runtime transactional support does not implement propagation semantics");
    }

    /**
     * No transaction manager: run the callback on the calling thread, join nothing.
     *
     * <p>Checked exceptions are wrapped rather than declared, so a caller can pass a lambda that
     * throws and still see the original instance when it is unchecked.</p>
     */
    static RuntimeTransactionalSupport direct() {
        return new RuntimeTransactionalSupport() {
            @Override
            public <T> T execute(Callable<T> callback) {
                try {
                    return callback.call();
                } catch (RuntimeException e) {
                    throw e;
                } catch (Exception e) {
                    throw new IllegalStateException(e);
                }
            }

            @Override
            public <T> T executeWith(Callable<T> callback, int propagationBehavior) {
                throw new UnsupportedOperationException("A direct runtime transactional support has no propagation semantics");
            }
        };
    }
}
