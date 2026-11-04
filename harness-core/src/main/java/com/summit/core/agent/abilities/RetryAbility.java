package com.summit.core.agent.abilities;

import com.summit.core.conf.ModelRetryConfig;
import lombok.AllArgsConstructor;
import lombok.Data;

@AllArgsConstructor
@Data
public class RetryAbility implements Ability<ModelRetryConfig>{
  private final   ModelRetryConfig modelRetryConfig;

    @Override
    public String name() {
        return "MODEL_RETRY";
    }

    @Override
    public ModelRetryConfig apply() {
        return this.modelRetryConfig;
    }
}
