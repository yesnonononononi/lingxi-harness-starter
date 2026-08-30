package com.summit.core.tool;


import lombok.Builder;
import lombok.Getter;


import java.util.List;

@Getter
@Builder
public class DiffResult {
    private Integer code;
    private String errMsg;
    private List<String> diffs;



    public static DiffResult success(List<String> diffs) {
        return DiffResult.builder()
                .code(1)
                .diffs(diffs)
                .build();
    }

    public static DiffResult fail(String errMsg) {
        return DiffResult.builder()
                .code(0)
                .errMsg(errMsg)
                .build();
    }

    public boolean isSuccess() {
        return code == 1;
    }

    /**
     * Counts unified-diff lines starting with the given marker ('+' / '-'),
     * excluding the +++/--- file header lines (those are exactly one char).
     * Shared by the edit event and the pending-record DTO so the front-end
     * can render "+N -M" chips.
     */
    public static int countDiffLines(List<String> diffs, char marker) {
        if (diffs == null) {
            return 0;
        }
        return (int) diffs.stream()
                .filter(l -> l.length() > 1 && l.charAt(0) == marker)
                .count();
    }
}
