package com.summit.sandbox;

import java.util.concurrent.TimeUnit;

public record CommandTimeoutConfig(long timeout, TimeUnit timeUnit) {
    public CommandTimeoutConfig {
        if (timeout <= 0 || timeUnit == null) {
            throw new IllegalArgumentException("Timeout must be positive and have a time unit");
        }
    }
}
