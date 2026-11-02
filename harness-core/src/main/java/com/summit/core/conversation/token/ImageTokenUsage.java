package com.summit.core.conversation.token;

import com.summit.core.conversation.message.TokenUsageEntity;
import lombok.NoArgsConstructor;
import lombok.ToString;
import lombok.experimental.SuperBuilder;
import lombok.extern.jackson.Jacksonized;

/** Image/text breakdowns are subsets of the shared input/output counts. */
@SuperBuilder
@NoArgsConstructor
@ToString(callSuper = true)
@Jacksonized
public class ImageTokenUsage extends TokenUsageEntity {
}
