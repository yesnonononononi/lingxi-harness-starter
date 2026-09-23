package com.summit.harnessexample;


import com.summit.core.runtime.workspace.Workspace;
import com.summit.core.tool.ToolRegistry;
import com.summit.core.conversation.message.UserMessageEntity;
import java.util.List;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;

@SpringBootTest
public class HarnessExampleApplicationTests {
    @Autowired
    private Demo demo;
    @Autowired
    private ToolRegistry toolRegistry;

    @Test
    public void contextLoads() {
        // System.out.println(System.getProperty("user.dir"));
        //System.out.println(System.getProperty("os.name"));
        //System.out.println(System.getenv().get("TAVILY_APIKEY"));
        //System.out.println(System.getenv().get("DEEPSEEK_APIKEY"));
        // System.out.println(toolRegistry);
        Workspace workspace = new LocalWorkSpace();
        demo.chat(List.of(UserMessageEntity.from("1")), "test-execution",
                "default-streaming", workspace, null, null);
    }

}
