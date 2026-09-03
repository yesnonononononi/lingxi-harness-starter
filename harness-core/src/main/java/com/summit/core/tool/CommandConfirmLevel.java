package com.summit.core.tool;


import lombok.Getter;

@Getter
public enum CommandConfirmLevel  {
    /**
     * 完全访问 - 所有命令均可直接执行
     */
    FULL_ACCESS("完全访问", "无需任何确认"),

    /**
     * 执行前确认 - 执行命令前弹出确认框
     */
    PRE_EXEC_CONFIRM("执行前确认", "命令执行前确认"),

    /**
     * 危险命令拦截 - 危险命令直接拦截，需管理员审批
     */
    DANGEROUS_BLOCK("危险命令拦截", "危险命令需管理员审批");

    private final String name;
    private final String policy;
    CommandConfirmLevel(String name, String policy) {
        this.name = name;
        this.policy = policy;
    }

}
