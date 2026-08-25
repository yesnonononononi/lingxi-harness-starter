package com.summit.harness.springbootautoconfigure.properties.tool;

import lombok.Data;
import org.springframework.boot.context.properties.ConfigurationProperties;

@Data
@ConfigurationProperties(prefix = "lingxi.agent.runtime.tool.terminal")
public class TerminalToolProperties {
    private boolean enabled;
    private Long timeout;
    /** 命令输出最大 token 数（估算，约 3 字符/token），超出部分截断，防止撑爆模型上下文 */
    private Integer maxOutput;
}
