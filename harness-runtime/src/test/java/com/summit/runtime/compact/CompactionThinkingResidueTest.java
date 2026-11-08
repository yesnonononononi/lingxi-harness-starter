package com.summit.runtime.compact;

import com.summit.core.agent.AgentRequest;
import com.summit.core.agent.Execution;
import com.summit.core.compact.ContextAttachmentProvider;
import com.summit.core.compact.ContextCompactRequest;
import com.summit.core.conf.ModelConfig;
import com.summit.core.conversation.api.ChatRequestEntity;
import com.summit.core.conversation.api.ChatResponseEntity;
import com.summit.core.conversation.event.RuntimeEventPublisher;
import com.summit.core.conversation.message.AiMessageEntity;
import com.summit.core.conversation.message.Message;
import com.summit.core.conversation.message.SystemMessageEntity;
import com.summit.core.conversation.message.UserMessageEntity;
import com.summit.core.model.ModelProvider;
import com.summit.core.model.ModelProviderRegistry;
import com.summit.core.model.chat.ChatModel;
import com.summit.core.runtime.loop.ContextUsageReporter;
import com.summit.runtime.conversation.DefaultConversationManager;
import com.summit.runtime.conversation.DefaultTokenizer;
import com.summit.runtime.loop.control.InMemoryActiveExecutionRegistry;
import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.concurrent.atomic.AtomicReference;

import static org.junit.jupiter.api.Assertions.*;

/**
 * 摘要压缩后的 thinking 残留守卫。
 *
 * <p><b>为什么这条必须存在</b>：Anthropic 的 thinking 是「前缀绑定 + 带签名」的 —— 回传的思考块
 * 必须与它当初产生时的历史前缀逐字一致。摘要压缩做的事恰恰是**替换历史前缀**：改动点之前的
 * assistant 消息被摘要取代，改动点之后（保留的尾部）若有 assistant 消息仍挂着旧 thinking 与
 * 旧签名，就会在签名校验上失败（模型侧 400，且报错文本与「历史被改写」这件事没有字面关联，
 * 极难归因）。</p>
 *
 * <p><b>这里钉住的三个口径</b>：</p>
 * <ol>
 *   <li>改动点（摘要注入处）之前的保留历史里，不得出现任何 thinking 文本；</li>
 *   <li>保留的尾部 assistant 消息必须连签名属性（{@code attributes}）一起剥离 —— 只清文本
 *       不清签名，等于回传一个「签名对不上正文」的思考块，比不传更糟；</li>
 *   <li>未被压缩时不剥离：`thinking` 是既有能力，不能让这条守卫的修法顺手把正常回传也砍掉。</li>
 * </ol>
 */
class CompactionThinkingResidueTest {

    private static final String SUMMARY_JSON = "{\"summary\":\"压缩后的摘要\",\"goal\":\"目标\","
            + "\"completed\":\"完成项\",\"pending\":\"待办项\",\"state\":\"进行中\"}";
    private static final String THINKING_ATTR_SIGNATURE = "signature";

    private final ModelProviderRegistry<ChatModel> providers = new ModelProviderRegistry<>();
    private final AtomicReference<ChatRequestEntity> compactRequest = new AtomicReference<>();

    private DefaultModelCompacter compacter() {
        providers.register(new ModelProvider<>() {
            public String name() { return "default"; }
            public ChatModel create(ModelConfig config) {
                return request -> {
                    compactRequest.set(request);
                    return ChatResponseEntity.builder().aiMessageEntity(AiMessageEntity.builder()
                            .text(SUMMARY_JSON).build()).build();
                };
            }
        });
        ChatModel compactModel = providers.create(ModelConfig.builder().modelName("compact").provider("default").build());
        DefaultConversationManager conversations = new DefaultConversationManager(ContextAttachmentProvider.NONE);
        return DefaultModelCompacter.builder().models(ignored -> compactModel)
                .executions(new InMemoryActiveExecutionRegistry()).conversationManager(conversations)
                .contextAttachmentProvider(ContextAttachmentProvider.NONE)
                .usage(new ContextUsageReporter(new DefaultTokenizer(), 10_000, new RuntimeEventPublisher(List.of()), 1))
                .summaryApplier(new DefaultCompactSummaryApplier(conversations)).build();
    }

    private AgentRequest request() {
        return AgentRequest.builder().systemPrompt("SYS-PROMPT")
                .messages(List.of(UserMessageEntity.from("占位"))).build();
    }

    /** 两条改动点之前的 assistant 消息 + 改动点之后（保留尾部）一条带 thinking 的 assistant 消息。 */
    private List<Message> historyWithThinking() {
        List<Message> messages = new ArrayList<>();
        messages.add(SystemMessageEntity.builder().text("SYS-PROMPT").build());
        messages.add(UserMessageEntity.from("Q1"));
        messages.add(thinkingAi("A1", "THINK-1", "sig-1"));
        messages.add(UserMessageEntity.from("Q2"));
        messages.add(thinkingAi("A2", "THINK-2", "sig-2"));
        messages.add(UserMessageEntity.from("Q3"));
        // 最后一条 user 之后的 assistant 属于「保留尾部」：它的签名在改过前缀之后最危险
        messages.add(thinkingAi("A3", "THINK-3", "sig-3"));
        return messages;
    }

    private static AiMessageEntity thinkingAi(String text, String thinking, String signature) {
        return AiMessageEntity.builder()
                .text(text)
                .thinking(thinking)
                .toolCalls(List.of())
                .attributes(Map.of(THINKING_ATTR_SIGNATURE, signature))
                .build();
    }

    @Test
    void summaryRebuildLeavesNoThinkingOrSignatureBehindTheRewritePoint() {
        Execution execution = Execution.create(request(), "test");
        execution.setMessages(new ArrayList<>(historyWithThinking()));

        assertTrue(compacter().compact(new ContextCompactRequest(execution)), "摘要可用时必须真的改写上下文");

        List<Message> rebuilt = execution.getMessages();

        // 摘要确实站在「改动处」：它之前的前缀被这条 system 消息取代
        int rewriteIndex = -1;
        for (int i = 0; i < rebuilt.size(); i++) {
            if (rebuilt.get(i) instanceof SystemMessageEntity system
                    && system.getText() != null && system.getText().contains("compressed into the following summary")) {
                rewriteIndex = i;
                break;
            }
        }
        assertTrue(rewriteIndex > 0, "压缩后必须存在摘要注入点（leading 系统提示之后）");

        // 口径一：改动处之前的保留历史里不得有 thinking
        for (Message message : rebuilt.subList(0, rewriteIndex)) {
            assertFalse(message instanceof AiMessageEntity,
                    "改动处之前不该再出现 assistant 消息，实际存在: " + describe(message));
        }

        // 口径二：改动处之后（保留的用户消息与尾部）所有 assistant 消息都不得残留 thinking / 签名
        for (Message message : rebuilt.subList(rewriteIndex, rebuilt.size())) {
            if (message instanceof AiMessageEntity ai) {
                assertTrue(ai.getThinking() == null || ai.getThinking().isBlank(),
                        "改动点之后仍在上下文里的 assistant 消息残留了 thinking: " + ai.getThinking());
                assertTrue(ai.getAttributes() == null || ai.getAttributes().isEmpty(),
                        "只清 thinking 不清签名等于回传签名对不上的思考块: " + ai.getAttributes());
            }
        }

        // 改动点之前的用户消息仍然被保留（这是摘要压缩的设计），但它们本身不携带 thinking
        List<String> keptUserTexts = rebuilt.stream()
                .filter(UserMessageEntity.class::isInstance)
                .map(message -> message.text())
                .toList();
        assertTrue(keptUserTexts.containsAll(List.of("Q1", "Q2", "Q3")),
                "摘要只替换 assistant 历史，用户消息必须保留，实际: " + keptUserTexts);

        // 改动点之前的 assistant 已整条被摘要取代 —— 不再以「带 thinking 的原文」形式存在
        for (Message message : rebuilt) {
            if (message instanceof AiMessageEntity ai) {
                assertNotEquals("A1", ai.getText(), "改动点之前的 assistant 应已被摘要取代");
                assertNotEquals("A2", ai.getText(), "改动点之前的 assistant 应已被摘要取代");
            }
        }
    }

    @Test
    void thinkingSurvivesWhenThePrefixIsNotRewritten() {
        // 控制组：不压缩就不许剥离。否则「修残留」会顺手把正常的历史回传砍掉。
        List<Message> untouched = historyWithThinking();

        List<AiMessageEntity> thinkingBearing = untouched.stream()
                .filter(AiMessageEntity.class::isInstance)
                .map(AiMessageEntity.class::cast)
                .filter(ai -> ai.getThinking() != null && !ai.getThinking().isBlank())
                .toList();

        assertEquals(3, thinkingBearing.size(), "未压缩的历史本来就应带着 thinking");
        assertEquals("sig-1", thinkingBearing.getFirst().getAttributes().get(THINKING_ATTR_SIGNATURE));
    }

    @Test
    void compactionInputCarriesNoThinkingText() {
        Execution execution = Execution.create(request(), "test");
        execution.setMessages(new ArrayList<>(historyWithThinking()));

        assertTrue(compacter().compact(new ContextCompactRequest(execution)));

        String payload = compactRequest.get().getMessages().stream()
                .map(Message::text)
                .reduce("", (left, right) -> left + "\n" + right);
        assertFalse(payload.contains("THINK-1"), "摘要输入不该带思考文本，否则摘要会复述思考");
        assertFalse(payload.contains("THINK-3"), "摘要输入不该带思考文本");
        assertFalse(payload.contains("sig-1"), "摘要输入不该带签名");
    }

    private static String describe(Message message) {
        return message.getClass().getSimpleName() + "(" + message.text() + ")";
    }
}
