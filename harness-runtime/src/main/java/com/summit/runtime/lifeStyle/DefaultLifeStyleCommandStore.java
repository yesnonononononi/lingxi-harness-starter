package com.summit.runtime.lifeStyle;

import com.summit.core.runtime.LifeStyleCommandStore;
import com.summit.core.runtime.LoopCommand;
import lombok.Getter;

import java.util.Queue;
import java.util.concurrent.ArrayBlockingQueue;


public class DefaultLifeStyleCommandStore implements LifeStyleCommandStore {
    @Getter
    Queue<LoopCommand> commandQueue= new ArrayBlockingQueue<>(2000);
    public void resume(){
        commandQueue.offer(LoopCommand.RESUME);
    }
    public void pause(){
        commandQueue.offer(LoopCommand.PAUSE);
    }
    public void stop(){
        commandQueue.offer(LoopCommand.STOP);
    }
    public LoopCommand poll(){
        return commandQueue.poll();
    }

    @Override
    public void destroy() {
        commandQueue.clear();
    }
}
