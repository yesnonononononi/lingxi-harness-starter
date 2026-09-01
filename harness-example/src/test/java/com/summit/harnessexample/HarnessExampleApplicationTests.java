package com.summit.harnessexample;


import com.summit.core.runtime.Workspace;
import com.summit.core.tool.ToolRegistry;
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
        demo.chat("""
                1
                """,true,null,null,workspace);
    }

}
