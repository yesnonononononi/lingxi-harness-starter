package com.summit.core.runtime;


public interface LifeStyleCommandStore {
     void resume();
     void pause();
     void stop();
     LoopCommand poll();
     default void start(){};
     default void destroy(){};
}
