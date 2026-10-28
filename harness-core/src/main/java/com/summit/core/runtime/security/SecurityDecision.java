package com.summit.core.runtime.security;

/** Carries the matching rule for audit and user-facing explanations. */
public record SecurityDecision(SecurityAction action, String matchedRule) { }
