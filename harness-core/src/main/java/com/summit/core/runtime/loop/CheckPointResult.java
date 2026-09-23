package com.summit.core.runtime.loop;

public record CheckPointResult (
        CheckPointStatus status,
        String reason
) {

    public enum CheckPointStatus {
        CONTINUE,
        CANCEL
    }
    public boolean isContinue() {
        return status == CheckPointStatus.CONTINUE;
    }

    public boolean isCancelled() {
        return status == CheckPointStatus.CANCEL;
    }
    public static CheckPointResult continueWith(){
        return new CheckPointResult(CheckPointStatus.CONTINUE, "");
    }

    public static CheckPointResult cancelWith(String reason){
        return new CheckPointResult(CheckPointStatus.CANCEL, reason);
    }
}
