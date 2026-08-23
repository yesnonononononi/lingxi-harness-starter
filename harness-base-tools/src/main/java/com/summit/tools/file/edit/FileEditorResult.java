package com.summit.tools.file.edit;

import lombok.Builder;

import lombok.Getter;

@Getter
@Builder
public class FileEditorResult {
    private int code;
    private String errMsg;

    public boolean isSuccess() {
        return code == 1;
    }

    public static FileEditorResult success() {
        return FileEditorResult.builder().code(1).errMsg("success").build();
    }

    public static FileEditorResult err(String errMsg) {
        return FileEditorResult.builder().code(0).errMsg(errMsg).build();
    }
}
