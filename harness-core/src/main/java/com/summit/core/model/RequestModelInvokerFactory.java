package com.summit.core.model;

import com.summit.core.conf.ModelConfig;

/** Resolves the model and invocation mode for a single agent request. */
public interface RequestModelInvokerFactory {
    Selection select(String providerName);

    Selection select(ModelConfig config);

    record Selection(String providerName, ModelInvoker invoker, boolean streaming) {
    }
}
