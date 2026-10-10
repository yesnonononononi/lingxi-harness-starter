package com.summit.core.memory;

import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class MemoryConfig {
    /**
     * Backend resource reference, retained under the original field name. A file loader interprets
     * it as a workspace path; a business loader may interpret it as a collection identifier.
     * This is not an authentication secret. Access is checked against the server-provided
     * attributes in {@link MemoryContext}, rather than granted by this value alone.
     */
    private String credential;
    /**
     * Permission for memory operations: NONE skips loading; READ_ONLY permits loading only;
     * ALLOW_WRITE also permits proposals reviewed by {@link MemoryHooker} before saving.
     * This does not change the permissions of unrelated file or command tools.
     */
    @Builder.Default
    private MemoryManagerMode mode = MemoryManagerMode.NONE;

    /**
     * Positive limit on the total memory text injected into one execution. The runtime must check
     * it before injection and after a hook replaces proposed content; it is not a storage quota.
     */
    @Builder.Default
    private Integer maxChars = 8000;
}
