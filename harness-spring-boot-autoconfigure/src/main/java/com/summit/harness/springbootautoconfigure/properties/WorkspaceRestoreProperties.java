package com.summit.harness.springbootautoconfigure.properties;

import lombok.Data;
import org.springframework.boot.context.properties.ConfigurationProperties;

/** Startup adoption of resources the workspace providers still own from an earlier run. */
@Data
@ConfigurationProperties(prefix = "lingxi.agent.sandbox.restore")
public class WorkspaceRestoreProperties {

    /** Re-register what the providers recognise right after the application becomes ready. */
    private boolean enabled = true;

    /** Fail startup instead of falling back to provisioning on demand. */
    private boolean failFast = false;
}
