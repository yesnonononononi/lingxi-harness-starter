package com.summit.runtime;

import com.summit.core.runtime.LifeStyleCommandRegistry;
import com.summit.core.runtime.LifeStyleCommandStore;

import java.io.Serializable;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ConcurrentMap;

/**
 * Default per-session command registry. One store per registered session; every
 * new execution registers a fresh store (overwriting the previous one of the
 * same session, which has normally already finished).
 */
public class DefaultLifeStyleCommandRegistry implements LifeStyleCommandRegistry {

    private final ConcurrentMap<String, LifeStyleCommandStore> stores = new ConcurrentHashMap<>();

    private String key(Serializable sessionId) {
        return sessionId == null ? "" : sessionId.toString();
    }

    @Override
    public LifeStyleCommandStore register(Serializable sessionId) {
        LifeStyleCommandStore store = new DefaultLifeStyleCommandStore();
        stores.put(key(sessionId), store);
        return store;
    }

    @Override
    public void unregister(Serializable sessionId, LifeStyleCommandStore store) {
        stores.remove(key(sessionId), store);
    }

    @Override
    public void pause(Serializable sessionId) {
        LifeStyleCommandStore store = stores.get(key(sessionId));
        if (store != null) {
            store.pause();
        }
    }

    @Override
    public void resume(Serializable sessionId) {
        LifeStyleCommandStore store = stores.get(key(sessionId));
        if (store != null) {
            store.resume();
        }
    }

    @Override
    public void stop(Serializable sessionId) {
        LifeStyleCommandStore store = stores.get(key(sessionId));
        if (store != null) {
            store.stop();
        }
    }

    @Override
    public void pauseAll() {
        stores.values().forEach(LifeStyleCommandStore::pause);
    }

    @Override
    public void resumeAll() {
        stores.values().forEach(LifeStyleCommandStore::resume);
    }

    @Override
    public void stopAll() {
        stores.values().forEach(LifeStyleCommandStore::stop);
    }

    @Override
    public int size() {
        return stores.size();
    }
}
