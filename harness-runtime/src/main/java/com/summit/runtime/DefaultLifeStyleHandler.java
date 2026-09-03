package com.summit.runtime;

import com.summit.core.runtime.LifeStyleHandler;
import lombok.extern.slf4j.Slf4j;

@Slf4j
public class DefaultLifeStyleHandler implements LifeStyleHandler {
    @Override
    public void onPaused() {
        log.info("【loop-lifestyle】 paused");
    }

    @Override
    public void onStopped() {
        log.info("【loop-lifestyle】 stopped");
    }

    @Override
    public void onResumed() {
        log.info("【loop-lifestyle】 resumed");
    }
}
