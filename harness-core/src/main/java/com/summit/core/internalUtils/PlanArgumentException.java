package com.summit.core.internalUtils;

/**
 * Thrown while parsing / validating the arguments of a plan kernel tool.
 *
 * <p>The message is written for the model: it is returned verbatim as the tool error output, so it
 * must state what is wrong <b>and</b> which values would be accepted.</p>
 */
public class PlanArgumentException extends RuntimeException {

    public PlanArgumentException(String message) {
        super(message);
    }
}
