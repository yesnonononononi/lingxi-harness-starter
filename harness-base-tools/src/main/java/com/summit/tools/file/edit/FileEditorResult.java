package com.summit.tools.file.edit;

import lombok.Builder;

import lombok.Getter;

@Getter
@Builder
public class FileEditorResult<T> {
    private int code;
    private String errMsg;
    private T data;

    public boolean isSuccess() {
        return code == 1;
    }

    public static <T>FileEditorResult<T> success(T data) {
        return FileEditorResult.<T>builder()
                .code(1)
                .data(data)
                .build();
    }
    public static <T>FileEditorResult<T> success() {
        return FileEditorResult.<T>builder()
                .code(1)
                .build();
    }

    public static <T>FileEditorResult<T> err(String errMsg) {
        return FileEditorResult.<T>builder().code(0).errMsg(errMsg).build();
    }
}
