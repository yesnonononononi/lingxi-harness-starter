package com.summit.core.runtime;

import java.io.IOException;

/** Receives decoded chunks on the drain thread, including output beyond the retained snapshot limit. */
@FunctionalInterface
public interface ProcessOutputListener {
    void onOutput(String text) throws IOException;
}