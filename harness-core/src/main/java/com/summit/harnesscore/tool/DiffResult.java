package com.summit.harnesscore.tool;

import com.github.difflib.patch.Patch;
import lombok.Builder;
import lombok.Getter;


import java.util.List;

@Getter
@Builder
public class DiffResult {
    private Integer code;
    private String errMsg;
    private List<String> diffs;
    private Patch<String> patch;


    public static DiffResult success(List<String> diffs, Patch<String> patch) {
        return DiffResult.builder()
                .code(1)
                .patch(patch)
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

}
