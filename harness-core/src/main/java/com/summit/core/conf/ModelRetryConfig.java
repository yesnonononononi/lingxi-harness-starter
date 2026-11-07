package com.summit.core.conf;

import lombok.Getter;
import lombok.experimental.SuperBuilder;
import lombok.extern.jackson.Jacksonized;

/** Request-level retry settings contain no executable task or runtime control callbacks. */
@Getter
@SuperBuilder
@Jacksonized
public class ModelRetryConfig extends RetryConfig{

}
