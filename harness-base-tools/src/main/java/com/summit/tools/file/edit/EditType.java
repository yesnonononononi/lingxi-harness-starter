package com.summit.tools.file.edit;

import lombok.Getter;

@Getter
public enum EditType {
    INSERT_AFTER,
    INSERT_BEFORE,
    DELETE,
    REPLACE;
    public static EditType fromString(String type){
        for (EditType value : values()) {
            if (value.name().equalsIgnoreCase(type)) {
                return value;
            }
        }
        throw new IllegalArgumentException("Invalid EditType: " + type);
    }
}
