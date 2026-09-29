package com.summit.core.workspace;

import java.io.Serializable;
import java.util.Map;

/** Desired workspace configuration, independent from a live runtime resource. */
public interface WorkspaceSpec extends Serializable {
    /**
     * Attribute carrying the principal a workspace belongs to.
     *
     * <p>Read through {@link #scope()}. The value must be an opaque identifier
     * — a user or tenant id — and never a live credential: providers persist it
     * where an operator can read it back.</p>
     */
    String PRINCIPAL = "principal";

    String provider();

    String workDir();

    /**
     * Isolation domain of the workspace.
     *
     * <p>Two specs that describe the same directories still denote two different
     * resources when their scopes differ: a directory is not an identity on its
     * own, two principals can be handed the same host path. Scope therefore
     * takes part in the identity the framework derives, so it must be supplied
     * whenever one deployment serves more than one principal. Leaving it blank
     * asks the framework to treat the whole deployment as a single principal.</p>
     *
     * <p>The default reads the {@linkplain #PRINCIPAL principal} attribute, so a
     * caller only has to set that one entry. Specs that model the principal as a
     * first-class field override this.</p>
     */
    default String scope() {
        return configuration().get(PRINCIPAL);
    }

    default Map<String, String> configuration() {
        return Map.of();
    }
}
