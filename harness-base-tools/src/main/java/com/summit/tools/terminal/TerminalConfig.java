package com.summit.tools.terminal;

import lombok.Builder;
import lombok.Data;

@Data
@Builder
public class TerminalConfig {
    private Long timeout;
}
