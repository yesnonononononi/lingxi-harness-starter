package com.summit.harnessexample;


import com.summit.harnesscore.agent.AgentRequest;
import com.summit.harnesscore.agent.Execution;
import com.summit.runtime.agent.ChatAgent;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Component;

@RequiredArgsConstructor
@Component
public class Demo {

    private final ChatAgent chatAgent;

    public void chat(String input) {
        Execution execute = chatAgent.execute(AgentRequest
                .builder()
                .input(input)
                .build()
        );
        System.out.println("agent result: " +execute.toString());
    }

}
