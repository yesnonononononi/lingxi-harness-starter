package com.summit.harness.springbootautoconfigure.properties;

import lombok.Data;
import org.springframework.boot.context.properties.ConfigurationProperties;
@Data
@ConfigurationProperties(prefix = "lingxi.agent.runtime.tool.common")
public class CommonToolProperties {
    /**
     * 工具调用,返回事件的文本参数输出字符
     */
    private int maxToolOutputDisplay = 500;
}
