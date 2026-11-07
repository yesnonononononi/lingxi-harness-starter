package com.summit.core.audit;

import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

@Data
@Builder
@AllArgsConstructor
@NoArgsConstructor
public class DocumentElement<T> {
    private T tokenMeta;
    private double ratio;
    private String type;
    private AuditStatus status;
    private String auditDes;
}
