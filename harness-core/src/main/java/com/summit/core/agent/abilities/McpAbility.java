package com.summit.core.agent.abilities;

import com.summit.core.conf.SensitiveDataSpec;
import lombok.AllArgsConstructor;
import lombok.Data;

@Data
@AllArgsConstructor
public class McpAbility implements Ability<SensitiveDataSpec>{
    /** Only the reference is persisted; the application resolves the exact MCP configuration revision. */
    private final SensitiveDataSpec mcpConfigSpec;

    @Override
    public String name() {
        return "MCP";
    }

    @Override
    public SensitiveDataSpec apply() {
        return this.mcpConfigSpec;
    }
}
