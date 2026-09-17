package com.summit.core.runtime.lifstyle;


import com.summit.core.runtime.LoopCommand;

public interface LifeStyleCommandStore {
     void resume();
     void pause();
     void stop();
     LoopCommand poll();
     default void start(){};
     default void destroy(){};
}
