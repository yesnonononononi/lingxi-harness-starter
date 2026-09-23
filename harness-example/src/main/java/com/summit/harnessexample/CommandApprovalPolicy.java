package com.summit.harnessexample;

import java.util.Map;

/** How much freedom the terminal tool gets in <b>this</b> application. */
public enum CommandApprovalPolicy {

    /** Every command runs untouched. */
    FULL_ACCESS,

    /** Every command is shown to the user first. */
    PRE_EXEC_CONFIRM,

    /** Only commands the kernel flags as dangerous wait for the user; destructive ones are refused anyway. */
    DANGEROUS_BLOCK;

    /** Key this application uses inside the opaque per-run attributes. */
    public static final String ATTRIBUTE_KEY = "command.approval.policy";

    /** Default when a request says nothing. */
    public static final CommandApprovalPolicy DEFAULT = DANGEROUS_BLOCK;

    public static Map<String, Object> toAttributes(CommandApprovalPolicy policy) {
        CommandApprovalPolicy effective = policy == null ? DEFAULT : policy;
        return Map.of(ATTRIBUTE_KEY, effective.name());
    }

    public static CommandApprovalPolicy from(Map<String, Object> attributes) {
        if (attributes == null) {
            return DEFAULT;
        }
        Object raw = attributes.get(ATTRIBUTE_KEY);
        if (raw == null) {
            return DEFAULT;
        }
        try {
            return valueOf(String.valueOf(raw).trim().toUpperCase());
        } catch (IllegalArgumentException e) {
            return DEFAULT;
        }
    }
}
