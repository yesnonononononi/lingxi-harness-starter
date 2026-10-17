package com.summit.core.runtime;

public interface CommandHandler {
    String outputSnapshot();

    String await();

    String stop();
}
