package com.summit.core.conversation.message;

import lombok.*;
import lombok.experimental.SuperBuilder;
import com.fasterxml.jackson.annotation.JsonInclude;

@ToString
@SuperBuilder
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
        /** Total input count, including cached and image tokens. */
        private int inputTokens;
        private int cacheHitTokens;
        private int imageTokens;
        /** Optional provider-reported text breakdown; already included in inputTokens. */
        @JsonInclude(JsonInclude.Include.NON_NULL)
        private Integer textTokens;

        /** Provider-reported uncached input tokens, when available. */
        @JsonInclude(JsonInclude.Include.NON_NULL)
        private Integer cacheMissTokens;

        public InputTokens(int inputTokens, int cacheHitTokens, int imageTokens, Integer textTokens) {
            this(inputTokens, cacheHitTokens, imageTokens, textTokens, null);
        }

        public InputTokens(int inputTokens, int cacheHitTokens, int imageTokens) {
            this(inputTokens, cacheHitTokens, imageTokens, null);
        }

        public InputTokens(int inputTokens, int cacheHitTokens) {
            this(inputTokens, cacheHitTokens, 0);
        }

        public static InputTokens of(int inputTokens){
            return new InputTokens(inputTokens, 0,0);
        }

        public void plus(InputTokens inputTokens){
             if (inputTokens == null) return;
             this.inputTokens += inputTokens.getInputTokens();
             this.cacheHitTokens += inputTokens.getCacheHitTokens();
             this.cacheMissTokens = sumOptional(this.cacheMissTokens, inputTokens.getCacheMissTokens());
             this.imageTokens += inputTokens.getImageTokens();
             this.textTokens = sumOptional(this.textTokens, inputTokens.getTextTokens());
        }
    }

    @Data
    @AllArgsConstructor
    @NoArgsConstructor
    public static class OutputTokens{
        /** Total output count, including reasoning and image tokens. */
        private int outputTokens;
        private int reasoningTokens;
        private int imageTokens;
        /** Optional provider-reported text breakdown; already included in outputTokens. */
        @JsonInclude(JsonInclude.Include.NON_NULL)
        private Integer textTokens;

        public OutputTokens(int outputTokens, int reasoningTokens, int imageTokens) {
            this(outputTokens, reasoningTokens, imageTokens, null);
        }

        public OutputTokens(int outputTokens, int reasoningTokens) {
            this(outputTokens, reasoningTokens, 0);
        }

        public static OutputTokens of(int outputTokens){
            return new OutputTokens(outputTokens, 0,0);
        }

        public void plus(OutputTokens outputTokens){
             if (outputTokens == null) return;
             this.outputTokens += outputTokens.getOutputTokens();
             this.reasoningTokens += outputTokens.getReasoningTokens();
             this.imageTokens += outputTokens.getImageTokens();
             this.textTokens = sumOptional(this.textTokens, outputTokens.getTextTokens());
        }

    }


    public static TokenUsageEntity of(int totalTokens, int inputTokens, int outputTokens) {
        return new TokenUsageEntity(totalTokens, InputTokens.of(inputTokens), OutputTokens.of(outputTokens));
    }
    public static TokenUsageEntity empty(){
        return of(0, 0, 0);
    }

    private static Integer sumOptional(Integer first, Integer second) {
        if (first == null) return second;
        return second == null ? first : first + second;
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
