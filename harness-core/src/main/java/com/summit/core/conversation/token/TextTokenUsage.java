package com.summit.core.conversation.token;

import com.summit.core.conversation.message.TokenUsageEntity;
import lombok.NoArgsConstructor;
import lombok.ToString;
import lombok.experimental.SuperBuilder;
import lombok.extern.jackson.Jacksonized;

/** Text model usage; the shared base owns all counts for safe mixed accumulation. */
@SuperBuilder
@NoArgsConstructor
@ToString(callSuper = true)
@Jacksonized
public class TextTokenUsage extends TokenUsageEntity {
}
