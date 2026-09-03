package com.summit.tools.arguments;

import com.summit.core.tool.CommandConfirmLevel;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class ExecuteCommandRequest  {
    private String command;
    private CommandConfirmLevel commandConfirmLevel;
}
