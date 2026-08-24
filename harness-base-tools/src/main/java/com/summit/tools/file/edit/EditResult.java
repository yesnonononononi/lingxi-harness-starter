package com.summit.tools.file.edit;

import lombok.Builder;

/**
 * affect number of rows in process
 * number of rows written
 * number of rows deleted
 */
@Builder
public record EditResult(int affectRows, int writeIn, int deleteNum) {


}
