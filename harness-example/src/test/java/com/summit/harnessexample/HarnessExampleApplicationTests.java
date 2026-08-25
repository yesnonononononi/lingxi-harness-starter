package com.summit.harnessexample;


import com.summit.harnesscore.tool.ToolRegistry;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;

import java.util.Map;

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
        demo.chat("""
                
                [plugin:vite:vue] Attribute name cannot start with '='.
                D:/Code/starter/lingxi-harness-agent/harness-example/frontend/src/views/HomeView.vue:129:31
                127 |          <template v-for=(item, index) in events :key=index>
                128 |            <!-- 用户消息：右侧蓝色气泡 -->
                129 |            <div v-if=item.type === 'USER' class=row row-user>
                    |                                 ^
                130 |              <div class=bubble bubble-user>
                131 |                <div class=bubble-text>{{ item.text }}</div>
                
                修复这个错误
                """);
    }

}
