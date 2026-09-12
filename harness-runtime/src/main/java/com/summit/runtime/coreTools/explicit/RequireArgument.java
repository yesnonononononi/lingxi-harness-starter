package com.summit.runtime.coreTools.explicit;


import lombok.Data;

import java.io.Serializable;
import java.util.List;

@Data
public class RequireArgument implements Serializable {
    private String question;
    private List<String> choice;

}
