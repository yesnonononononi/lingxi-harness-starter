package com.summit.tools.web;

import com.summit.core.conversation.api.ChatRequestEntity;
import com.summit.core.conversation.api.ChatResponseEntity;
import com.summit.core.conversation.message.SystemMessageEntity;
import com.summit.core.model.ChatModel;
import lombok.AllArgsConstructor;
import lombok.extern.slf4j.Slf4j;

import java.util.List;

@Slf4j
@AllArgsConstructor
public class WebResultSummarizer {
    private  final ChatModel chatModel;

    public String summary(String webSearchResultStr){
        try {
            ChatResponseEntity chatResponse = this.chatModel.chat(ChatRequestEntity.builder()
                    .messages(List.of(SystemMessageEntity.builder()
                            .text(webSearchResultStr).build()
                    ))
                    .build());
            return chatResponse.getAiMessageEntity().text();
        }catch (Exception e){
            log.warn("Summary the result of web content has happened a error.return original str to model",e);
            return webSearchResultStr;
        }

    }
}
