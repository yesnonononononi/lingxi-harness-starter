package com.summit.harnessexample;


import com.summit.harnesscore.tool.ToolRegistry;
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
       System.out.println(System.getProperty("user.dir"));
       //System.out.println(toolRegistry);
       demo.chat("试一下这个read_file工具,读取pom.xml文件");
    }

}
