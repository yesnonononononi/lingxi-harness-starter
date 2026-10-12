package com.summit.core.conversation.message;

import lombok.*;

@ToString
@Builder
@NoArgsConstructor
@AllArgsConstructor
@Getter
public class TokenUsageEntity {
    private int totalTokens;
    @Builder.Default
    private InputTokens inputTokens = InputTokens.of(0);
    @Builder.Default
    private OutputTokens outputTokens = OutputTokens.of(0);

    @Data
    @AllArgsConstructor
    @NoArgsConstructor
    public static class InputTokens{
        private int inputTokens;
        private int cacheHitTokens;

        public static InputTokens of(int inputTokens){
            return new InputTokens(inputTokens, 0);
        }

        public void plus(InputTokens inputTokens){
             if (inputTokens == null) return;
             this.inputTokens += inputTokens.getInputTokens();
             this.cacheHitTokens += inputTokens.getCacheHitTokens();
        }
    }

    @Data
    @AllArgsConstructor
    @NoArgsConstructor
    public static class OutputTokens{
        private int outputTokens;
        private int reasoningTokens;

        public static OutputTokens of(int outputTokens){
            return new OutputTokens(outputTokens, 0);
        }

        public void plus(OutputTokens outputTokens){
             if (outputTokens == null) return;
             this.outputTokens += outputTokens.getOutputTokens();
             this.reasoningTokens += outputTokens.getReasoningTokens();
        }

    }


    public static TokenUsageEntity of(int totalTokens, int inputTokens, int outputTokens) {
        return new TokenUsageEntity(totalTokens, InputTokens.of(inputTokens), OutputTokens.of(outputTokens));
    }
    public static TokenUsageEntity empty(){
        return of(0, 0, 0);
    }

    /** Accumulates the given usage into this execution across model turns. */
    public void add(TokenUsageEntity other) {
        if (other == null) {
            return;
        }
        this.totalTokens += other.totalTokens;
        if (this.inputTokens == null) this.inputTokens = InputTokens.of(0);
        if (this.outputTokens == null) this.outputTokens = OutputTokens.of(0);
        this.inputTokens.plus(other.inputTokens);
        this.outputTokens.plus(other.outputTokens);
    }
}
