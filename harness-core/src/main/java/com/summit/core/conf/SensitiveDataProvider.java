package com.summit.core.conf;

/**
 * Resolves one exact configuration revision without attaching credentials to execution snapshots.
 * Applications must retain immutable revisions and reject missing or revoked versions rather than
 * substitute the latest configuration. A null result is treated as an unavailable revision.
 */
@FunctionalInterface
public interface SensitiveDataProvider<T> {
    T get(Number id, int version);
}
