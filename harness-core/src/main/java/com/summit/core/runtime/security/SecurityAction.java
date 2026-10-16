package com.summit.core.runtime.security;

/** An admission decision; ASK must be resolved by the application before any side effect. */
public enum SecurityAction { ALLOW, ASK, DENY }
